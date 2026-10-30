"""Deterministic human-approval policy for every tool call Emrys makes.

Risk is decided from data, never by a model: Telnyx operations by their HTTP method in the bundled
catalog (generated from OubliTelnyxMCP's own tool construction, see ``scripts/gen_telnyx_ops.py``),
local tools by the policy they are registered with. Anything the policy cannot classify asks.
The per-turn taint lives in ``agent.state``, so it persists with the session and survives a supervisor rebuild;
``Confirm`` interrupt ids are derived from the tool use id, so a pending approval keeps its id across a restart too.
The user's standing "always allow" and "always decline" rules are the session's ``StandingRules`` (``rules.py``),
checked here before anything is asked; what each approval says is composed by ``alerts.py``.
"""

from __future__ import annotations

import ast
import json
import logging
import time
from collections import OrderedDict
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from functools import cache, partial
from importlib import resources
from typing import Any, Literal, TypedDict

from strands.agent.state import AgentState
from strands.hooks import AfterToolCallEvent, BeforeToolCallEvent
from strands.interventions import Confirm, Deny, InterventionHandler, Proceed, Transform
from strands.interventions.actions import LifecycleEvent
from strands.interventions.handler import OnError
from strands.types.tools import ToolResult

from .alerts import ALERT_TOOLS, Alert, alert_for, operation_summary
from .rules import (
    Decision,
    NoAllow,
    RuleOffer,
    Standing,
    StandingRule,
    StandingRules,
    Target,
    normalize,
    standing_of,
)

logger = logging.getLogger(__name__)

Tier = Literal["read", "send", "write", "destructive", "purchase", "unknown"]
_RANK: dict[Tier, int] = {"read": 0, "send": 1, "write": 2, "destructive": 3, "purchase": 4, "unknown": 5}
HIGH_TIERS: frozenset[Tier] = frozenset({"destructive", "purchase", "unknown"})
_NO_ALLOW_TIERS: dict[Tier, NoAllow] = {"purchase": "purchase", "destructive": "destructive", "unknown": "unknown"}

TURN_KEY = "emrys.turn"
"""``agent.state`` key the supervisor sets to the current turn id (unchanged when a turn resumes)."""
APPROVALS_KEY = "emrys.approvals"
"""``agent.state`` key holding the per-turn taint."""
PREVIEW_LIMIT = 2000
PROMPT_VERSION = 2
DECLINES_KEPT = 256
CONFIRMATION_FAILED = "CONFIRMATION_FAILED:"

STANDING_DENY = (
    "The user set Emrys to always decline this ({label}), so it was not done. Don't try it again or offer it. If the "
    "user asks for it, tell them it's blocked by a rule they can remove in Emrys › Settings › Approval rules."
)
DECLINED = "The user declined this ({headline}), so it was not done. Don't try it again unless they ask."
TIMED_OUT = "Nobody answered the approval in time, so it was not done. Ask the user before trying again."
SUPERSEDED = "The user sent a new message instead of answering, so this was not done."

AnswerReason = Literal["answered", "timed out", "superseded", "no answer"]
DeclineKind = Literal["standing", "answered", "timed out", "superseded", "no answer"]
_REASONS: dict[str, AnswerReason] = {
    "timed out": "timed out",
    "superseded by a new message": "superseded",
    "no answer": "no answer",
}

TELNYX_PREFIX = "telnyx_"
TELNYX_EXECUTE = f"{TELNYX_PREFIX}execute"

_SEND_OPERATIONS = frozenset(
    {
        "SendMessage",
        "CreateLongCodeMessage",
        "CreateNumberPoolMessage",
        "CreateShortCodeMessage",
        "CreateGroupMmsMessage",
        "SendRCSMessage",
        "SendWhatsappMessage",
        "SendAlphanumericSenderIdMessage",
        "SendAlphanumericSenderIdMessage_2",
        "ScheduleMessage",
        "DialCall",
        "SendFax",
    }
)
_CALL_ACTION_PREFIX = "/calls/{call_control_id}/actions/"
_CALL_RECORDING_ACTIONS = ("record_", "fork_", "siprec_", "streaming_", "transcription_")
_PURCHASE_PATH_PREFIXES = (
    "/number_orders",
    "/inexplicit_number_orders",
    "/number_block_orders",
    "/number_reservations",
    "/sim_card_orders",
    "/advanced_orders",
    "/actions/purchase/",
    "/machine-payments/",
    "/v2/payment/",
    "/payment/",
    "/x402/",
    "/bundle_pricing/user_bundles",
    "/messaging_hosted_number_orders",
    "/porting_orders",
)


@dataclass(frozen=True)
class TelnyxOperation:
    """One generated Telnyx tool, as OubliTelnyxMCP names it."""

    name: str
    method: str
    path: str
    summary: str

    @property
    def tier(self) -> Tier:
        """Risk tier from the HTTP method and path."""
        if self.method in ("GET", "HEAD"):
            return "read"
        if self.path.startswith(_PURCHASE_PATH_PREFIXES):
            return "purchase"
        if self.method == "DELETE":
            return "destructive"
        if self.name in _SEND_OPERATIONS:
            return "send"
        if self.path.startswith(_CALL_ACTION_PREFIX):
            return _call_action_tier(self.path.removeprefix(_CALL_ACTION_PREFIX))
        return "write"


def _call_action_tier(action: str) -> Tier:
    if action == "pay":
        return "purchase"
    if action.startswith(_CALL_RECORDING_ACTIONS):
        return "write"
    return "send"


class TelnyxCatalog:
    """Tool names reachable through ``telnyx_execute``'s ``call_tool``, with their routes."""

    def __init__(self, data: Mapping[str, Any]) -> None:
        self.operations = {
            name: TelnyxOperation(name, op["method"], op["path"], op.get("summary", ""))
            for name, op in data["operations"].items()
        }
        # Workspace entry points and the polling task tool only read; app-only backends are not
        # model-callable by bare name, and their hashed names are left unknown on purpose.
        self.read_helpers = frozenset(
            name for name, helper in data["helpers"].items() if helper.get("visibility") != ["app"]
        )
        self.sha256: str = data.get("sha256", "")

    @staticmethod
    @cache
    def bundled() -> TelnyxCatalog:
        """The catalog shipped in ``emrys_brain/data``."""
        text = resources.files("emrys_brain.data").joinpath("telnyx_operations.json").read_text("utf-8")
        return TelnyxCatalog(json.loads(text))

    def known(self, name: str) -> bool:
        """``name`` is a generated operation or a read helper."""
        return name in self.operations or name in self.read_helpers

    def describe(self, name: str | None) -> tuple[Tier, dict[str, str]]:
        """Tier and display fields for one ``call_tool`` target (``None`` = not a literal name)."""
        if name is None:
            return "unknown", {"name": "<computed>", "summary": "Tool name is not a string literal"}
        operation = self.operations.get(name)
        if operation is not None:
            return operation.tier, {
                "name": name,
                "method": operation.method,
                "path": operation.path,
                "summary": operation.summary,
            }
        if name in self.read_helpers:
            return "read", {"name": name, "summary": "Telnyx workspace or status poll"}
        return "unknown", {"name": name, "summary": "Not in the Telnyx catalog"}


def call_tool_targets(code: str) -> list[str | None]:
    """Tool names passed to ``call_tool`` in ``execute`` code; ``None`` marks one that is not literal.

    Any use of ``call_tool`` other than a direct call with a string-literal name (aliasing, a computed
    name, ``eval``/``exec``) yields ``None``, so the caller treats the code as unknown.
    """
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return [None]
    targets: list[str | None] = []
    direct_calls = 0
    references = 0
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id in ("eval", "exec", "compile", "globals", "locals", "vars"):
            targets.append(None)
        elif isinstance(node, ast.Name) and node.id == "call_tool":
            references += 1
        elif isinstance(node, ast.Constant) and node.value == "call_tool":
            targets.append(None)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "call_tool":
            direct_calls += 1
            targets.append(_literal_tool_name(node))
    if references > direct_calls:
        targets.append(None)
    return targets


def _literal_tool_name(call: ast.Call) -> str | None:
    candidates = [*call.args[:1], *(kw.value for kw in call.keywords if kw.arg == "tool_name")]
    if len(candidates) == 1 and isinstance(candidates[0], ast.Constant) and isinstance(candidates[0].value, str):
        return candidates[0].value
    return None


@dataclass(frozen=True)
class ToolPolicy:
    """How a local (non-Telnyx) tool is approved.

    Attributes:
        tier: Risk tier; ``read`` runs without asking.
        summary: Short description of what the tool does (the approval headline comes from ``alerts.py``).
        untrusted_output: The result can carry third-party text (messages, mail, screens, web pages);
            reading it taints the turn, which suspends standing allows until the next turn.
        standing: How the tool's calls become "don't ask again" targets; None when they can't.
        assess: Optional ``(tool_input) -> (tier, summary)`` for tools whose risk depends on their input (the
            app operator's tap is a commit point or not by its label); it replaces ``tier`` and ``summary``.
    """

    tier: Tier
    summary: str = ""
    untrusted_output: bool = False
    standing: Standing | None = None
    assess: Callable[[object], tuple[Tier, str]] | None = None


INTERNAL_POLICIES: dict[str, ToolPolicy] = {
    "todo_write": ToolPolicy("read", "Update the task list"),
    "skills": ToolPolicy("read", "Load a skill"),
    "search_memory": ToolPolicy("read", "Search memory", untrusted_output=True),
    "add_memory": ToolPolicy("read", "Save to memory"),
    "retrieve_context": ToolPolicy("read", "Re-read an offloaded tool result", untrusted_output=True),
    "retrieve_offloaded_content": ToolPolicy("read", "Re-read an earlier tool result", untrusted_output=True),
}
_TELNYX_READ_TOOLS = {
    f"{TELNYX_PREFIX}search": "Search the Telnyx catalog",
    f"{TELNYX_PREFIX}get_schema": "Read Telnyx tool schemas",
    f"{TELNYX_PREFIX}tags": "List Telnyx categories",
    f"{TELNYX_PREFIX}search_prefab_components": "Search UI components",
    f"{TELNYX_PREFIX}generate_prefab_ui": "Render a UI (sandboxed, no tool access)",
}


@dataclass(frozen=True)
class Assessment:
    """The policy's verdict on one tool use.

    Attributes:
        tool: Tool name.
        tier: Risk tier.
        summary: The approval headline, or what a read does.
        operations: For ``telnyx_execute``, each ``call_tool`` target as the sheet shows it.
        untrusted_output: Its result taints the turn.
        preview: The raw input, for the sheet's technical details.
        target: What a standing rule about this call would be about; None when it can't have one.
        alert: What the approval says.
        changes: For ``telnyx_execute``, the non-read operations it runs (each can be standing-denied).
    """

    tool: str
    tier: Tier
    summary: str
    operations: tuple[dict[str, str], ...] = ()
    untrusted_output: bool = False
    preview: str = ""
    target: Target | None = None
    alert: Alert = Alert("")
    changes: tuple[str, ...] = ()

    @property
    def high(self) -> bool:
        """Needs the strongest confirmation (biometric on the phone)."""
        return self.tier in HIGH_TIERS

    def prompt(self, *, tainted: bool, offer: RuleOffer | None) -> str:
        """The interrupt reason: compact JSON the phone renders as an approval sheet."""
        body: dict[str, object] = {
            "v": PROMPT_VERSION,
            "tool": self.tool,
            "tier": self.tier,
            "high": self.high,
            "headline": self.alert.headline,
            "blocks": list(self.alert.blocks),
            "preview": self.preview,
        }
        if offer is not None:
            body["offer"] = offer.to_wire()
        if self.operations:
            body["operations"] = list(self.operations)
        if tainted:
            body["untrusted_turn"] = True
        return json.dumps(body, separators=(",", ":"), ensure_ascii=False)


@dataclass(frozen=True)
class Answer:
    """The user's answer to one approval.

    Attributes:
        approved: Run the call.
        standing: Also make it a standing rule (``allow`` only with an approval, ``deny`` only with a denial).
        reason: Why a denial happened when the user did not answer it themselves.
    """

    approved: bool
    standing: Decision | None = None
    reason: AnswerReason = "answered"


def parse_approval(response: object) -> Answer:
    """The answer in an interrupt response; anything unrecognised, or a standing that contradicts it, is a denial."""
    if response is True or (isinstance(response, str) and response.strip().lower() in ("y", "yes")):
        return Answer(True)
    if not isinstance(response, Mapping):
        return Answer(False)
    raw_reason = response.get("reason")
    reason: AnswerReason = _REASONS.get(raw_reason, "answered") if isinstance(raw_reason, str) else "answered"
    approved = response.get("approved") is True
    if response.get("standing") is None:
        return Answer(approved, reason=reason)
    standing = standing_of(response)
    if standing is None:
        return Answer(False, reason=reason)
    return Answer(approved, standing, reason)


class RuleApplied(TypedDict):
    """``rules.applied``: a standing rule decided a call without asking."""

    type: Literal["rules.applied"]
    decision: Decision
    key: str
    label: str
    tool: str


class _Taint:
    """View over the taint entry in ``agent.state``."""

    def __init__(self, state: AgentState) -> None:
        self.state = state
        stored = state.get(APPROVALS_KEY)
        self.data: dict[str, object] = dict(stored) if isinstance(stored, dict) else {}

    def tainted(self) -> bool:
        turn = self.state.get(TURN_KEY)
        taint = self.data.get("taint_turn")
        return isinstance(turn, str) and taint == turn

    def taint(self) -> None:
        turn = self.state.get(TURN_KEY)
        self.data = {"taint_turn": turn if isinstance(turn, str) else None}
        self.state.set(APPROVALS_KEY, self.data)


class EmrysApprovals(InterventionHandler):
    """Asks the user before any tool call that is not provably read-only, unless a standing rule answers.

    Args:
        policies: Policies for local tools (device tools, task tools), merged over ``INTERNAL_POLICIES``.
        catalog: Telnyx catalog; defaults to the bundled one.
        clock: Wall-clock source for rule timestamps.
        rules: The session's standing rules; defaults to rules held only in memory.
        on_rule: Told when a standing rule decides a call (the gateway sends it to the phone's activity log).
    """

    def __init__(
        self,
        policies: Mapping[str, ToolPolicy] | None = None,
        *,
        catalog: TelnyxCatalog | None = None,
        clock: Callable[[], float] = time.time,
        rules: StandingRules | None = None,
        on_rule: Callable[[RuleApplied], None] | None = None,
    ) -> None:
        self.policies = {**INTERNAL_POLICIES, **(policies or {})}
        self.catalog = catalog or TelnyxCatalog.bundled()
        self._clock = clock
        self.rules = rules if rules is not None else StandingRules.in_memory()
        self._on_rule = on_rule
        self._declines: OrderedDict[str, DeclineKind] = OrderedDict()
        self._telnyx_standing = Standing(
            fields=(("operation", "text"),), required=frozenset({"operation"}), allow=False, label=self._telnyx_label
        )

    def bound(self, rules: StandingRules, on_rule: Callable[[RuleApplied], None]) -> EmrysApprovals:
        """A handler for one session: the same policies, catalog and clock over that session's rules."""
        return EmrysApprovals(self.policies, catalog=self.catalog, clock=self._clock, rules=rules, on_rule=on_rule)

    @property
    def name(self) -> str:
        """Interrupt name; with the tool use id it fixes each approval's interrupt id."""
        return "emrys-approval"

    @property
    def on_error(self) -> OnError:
        """A broken policy check denies the call."""
        return "deny"

    def standing_for(self, tool: str) -> Standing | None:
        """How ``tool``'s calls become rule targets; None when they can't."""
        if tool == TELNYX_EXECUTE:
            return self._telnyx_standing
        policy = self.policies.get(tool)
        return policy.standing if policy is not None else None

    def assess(self, tool: str, tool_input: object) -> Assessment:
        """Classify one tool use."""
        preview = _preview(tool_input)
        if tool == TELNYX_EXECUTE:
            return self._assess_execute(tool_input, preview)
        if tool in _TELNYX_READ_TOOLS:
            summary = _TELNYX_READ_TOOLS[tool]
            return Assessment(tool, "read", summary, preview=preview, alert=Alert(summary))
        policy = self.policies.get(tool)
        if policy is None:
            alert = alert_for(tool, tool_input, "unknown", ())
            return Assessment(tool, "unknown", alert.headline, preview=preview, alert=alert)
        tier, summary = policy.tier, policy.summary or tool
        if policy.assess is not None:
            tier, summary = policy.assess(tool_input)
        alert = Alert(summary)
        if tier != "read" and tool in ALERT_TOOLS:
            alert = alert_for(tool, tool_input, tier, ())
        return Assessment(
            tool,
            tier,
            alert.headline,
            untrusted_output=policy.untrusted_output,
            preview=preview,
            target=policy.standing.target(tool, tool_input) if policy.standing is not None else None,
            alert=alert,
        )

    def _assess_execute(self, tool_input: object, preview: str) -> Assessment:
        code = tool_input.get("code") if isinstance(tool_input, Mapping) else None
        if not isinstance(code, str):
            alert = alert_for(TELNYX_EXECUTE, tool_input, "unknown", ())
            return Assessment(TELNYX_EXECUTE, "unknown", alert.headline, preview=preview, alert=alert)
        tier: Tier = "read"
        operations: list[dict[str, str]] = []
        tiers: list[Tier] = []
        targets = call_tool_targets(code)
        for name in targets:
            target_tier, display = self.catalog.describe(name)
            tier = max(tier, target_tier, key=_RANK.__getitem__)
            if display not in operations:
                operations.append(display)
                tiers.append(target_tier)
        changes = tuple(op["name"] for op, op_tier in zip(operations, tiers, strict=True) if op_tier != "read")
        alert = alert_for(
            TELNYX_EXECUTE, tool_input, tier, [{**op, "tier": t} for op, t in zip(operations, tiers, strict=True)]
        )
        known = all(
            name is not None and (name in self.catalog.operations or name in self.catalog.read_helpers)
            for name in targets
        )
        rule_target = None
        if known and len(changes) == 1:
            rule_target = Target.of(TELNYX_EXECUTE, {"operation": changes[0]})
        # Telnyx results can carry inbound message text and caller data.
        return Assessment(
            TELNYX_EXECUTE,
            tier,
            alert.headline,
            tuple(operations),
            untrusted_output=True,
            preview=code[:PREVIEW_LIMIT],
            target=rule_target,
            alert=alert,
            changes=tuple(name for name in changes if name in self.catalog.operations),
        )

    def offer(self, assessment: Assessment, *, tainted: bool) -> RuleOffer | None:
        """The "don't ask again" choices for one call, or None when it can't have a rule."""
        standing = self.standing_for(assessment.tool)
        if standing is None or assessment.target is None:
            return None
        no_allow: NoAllow | None = _NO_ALLOW_TIERS.get(assessment.tier)
        if no_allow is None and not standing.allows(assessment.target):
            no_allow = "kind"
        if no_allow is None and tainted:
            no_allow = "untrusted"
        label = standing.label(assessment.target.scope_dict())
        return RuleOffer(assessment.target, label, allow=no_allow is None, deny=True, no_allow=no_allow)

    def offer_for(self, tool: str, scope: Mapping[str, str]) -> RuleOffer | None:
        """The offer for a rule the phone asks for directly (its own approvals), or None when it can't be one."""
        if tool == TELNYX_EXECUTE:
            if set(scope) != {"operation"}:
                return None
            name = normalize("text", scope["operation"]) or ""
            operation = self.catalog.operations.get(name)
            if operation is None or operation.tier == "read":
                return None
            only = Target.of(TELNYX_EXECUTE, {"operation": name})
            return self.offer(Assessment(TELNYX_EXECUTE, operation.tier, "", target=only), tainted=False)
        standing = self.standing_for(tool)
        data = standing.input_for(scope) if standing is not None else None
        if standing is None or data is None:
            return None
        target = standing.target(tool, data)
        tier = self.assess(tool, data).tier
        if target is None or tier == "read":
            return None
        return self.offer(Assessment(tool, tier, "", target=target), tainted=False)

    def before_tool_call(self, event: BeforeToolCallEvent, **kwargs: object) -> Proceed | Deny | Confirm:
        """Run reads; follow a standing rule; ask for everything else."""
        if event.selected_tool is None:
            return Proceed(reason="tool not found; the SDK reports the error")
        assessment = self.assess(event.tool_use["name"], event.tool_use.get("input"))
        if assessment.tier == "read":
            return Proceed()
        tainted = _Taint(event.agent.state).tainted()
        denied = self._deny_rule(assessment)
        if denied is not None:
            self._applied(denied)
            return Deny(reason=STANDING_DENY.format(label=denied.label))
        offer = self.offer(assessment, tainted=tainted)
        allowed = self.rules.match(assessment.target) if assessment.target is not None else None
        # A stored allow counts only where the offer would grant one now, whatever the store holds.
        if allowed is not None and allowed.decision == "allow" and offer is not None and offer.allow:
            self._applied(allowed)
            return Proceed(reason="standing rule")
        return Confirm(
            prompt=assessment.prompt(tainted=tainted, offer=offer),
            evaluate=partial(self._evaluate, str(event.tool_use["toolUseId"]), assessment),
        )

    def after_tool_call(self, event: AfterToolCallEvent, **kwargs: object) -> Proceed | Transform:
        """Taint the turn once it has read third-party content; tell the model plainly why a call didn't run."""
        use = event.tool_use
        assessment = self.assess(use["name"], use.get("input"))
        if event.cancel_message is None and assessment.untrusted_output:
            self.mark_untrusted(event.agent.state)
        if event.cancel_message is None or not event.cancel_message.startswith(CONFIRMATION_FAILED):
            return Proceed()
        tool_use_id = str(use["toolUseId"])
        result: ToolResult = {
            "toolUseId": tool_use_id,
            "status": "error",
            "content": [{"text": self.decline_text(self._declines.pop(tool_use_id, "no answer"), assessment)}],
        }

        def apply(target: LifecycleEvent) -> None:
            if isinstance(target, AfterToolCallEvent):
                target.result = result

        return Transform(apply=apply, reason="plain decline result")

    def decline_text(self, kind: DeclineKind, assessment: Assessment) -> str:
        """What the model is told about a call the user (or nobody) declined."""
        if kind == "standing":
            offer = self.offer(assessment, tainted=False)
            if offer is not None:
                return STANDING_DENY.format(label=offer.label)
        if kind == "timed out":
            return TIMED_OUT
        if kind == "superseded":
            return SUPERSEDED
        return DECLINED.format(headline=assessment.alert.headline)

    def mark_untrusted(self, state: AgentState) -> None:
        """Taint the current turn (also called when Perplexity's native tools bring in web content)."""
        taint = _Taint(state)
        if not taint.tainted():
            taint.taint()

    def _deny_rule(self, assessment: Assessment) -> StandingRule | None:
        targets = [assessment.target] if assessment.target is not None else []
        targets += [Target.of(TELNYX_EXECUTE, {"operation": name}) for name in assessment.changes]
        for target in targets:
            rule = self.rules.match(target)
            if rule is not None and rule.decision == "deny":
                return rule
        return None

    def _applied(self, rule: StandingRule) -> None:
        logger.info("tool=<%s>, decision=<%s> | standing rule applied", rule.target.tool, rule.decision)
        if self._on_rule is not None:
            self._on_rule(
                {
                    "type": "rules.applied",
                    "decision": rule.decision,
                    "key": rule.target.key,
                    "label": rule.label,
                    "tool": rule.target.tool,
                }
            )

    def _evaluate(self, tool_use_id: str, assessment: Assessment, response: object) -> bool:
        answer = parse_approval(response)
        if not answer.approved:
            self._declines[tool_use_id] = "standing" if answer.standing == "deny" else answer.reason
            while len(self._declines) > DECLINES_KEPT:
                self._declines.popitem(last=False)
        logger.info(
            "tool=<%s>, tier=<%s>, approved=<%s> | approval answered", assessment.tool, assessment.tier, answer.approved
        )
        return answer.approved

    def _telnyx_label(self, scope: Mapping[str, str]) -> str:
        name = scope.get("operation", "")
        operation = self.catalog.operations.get(name)
        return f"{operation_summary(name, operation.summary if operation is not None else '')} on your Telnyx account"


def _preview(tool_input: object) -> str:
    try:
        text = json.dumps(tool_input, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = repr(tool_input)
    return text[:PREVIEW_LIMIT]
