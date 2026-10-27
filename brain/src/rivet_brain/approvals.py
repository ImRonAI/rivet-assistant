"""Deterministic human-approval policy for every tool call Rivet makes.

Risk is decided from data, never by a model: Telnyx operations by their HTTP method in the bundled
catalog (generated from OubliTelnyxMCP's own tool construction, see ``scripts/gen_telnyx_ops.py``),
local tools by the policy they are registered with. Anything the policy cannot classify asks.
Approvals and the per-turn taint live in ``agent.state``, so they persist with the session and survive
a supervisor rebuild; ``Confirm`` interrupt ids are derived from the tool use id, so a pending approval
keeps its id across a restart too.
"""

from __future__ import annotations

import ast
import json
import logging
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from functools import cache, partial
from importlib import resources
from typing import Any, Literal

from strands.hooks import AfterToolCallEvent, BeforeToolCallEvent
from strands.interventions import Confirm, InterventionHandler, Proceed
from strands.interventions.handler import OnError

logger = logging.getLogger(__name__)

Tier = Literal["read", "send", "write", "destructive", "purchase", "unknown"]
_RANK: dict[Tier, int] = {"read": 0, "send": 1, "write": 2, "destructive": 3, "purchase": 4, "unknown": 5}
HIGH_TIERS: frozenset[Tier] = frozenset({"destructive", "purchase", "unknown"})
REMEMBERABLE_TIERS: frozenset[Tier] = frozenset({"send"})

TURN_KEY = "rivet.turn"
"""``agent.state`` key the supervisor sets to the current turn id (unchanged when a turn resumes)."""
APPROVALS_KEY = "rivet.approvals"
MAX_REMEMBER_MINUTES = 24 * 60
PREVIEW_LIMIT = 2000

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
        """The catalog shipped in ``rivet_brain/data``."""
        text = resources.files("rivet_brain.data").joinpath("telnyx_operations.json").read_text("utf-8")
        return TelnyxCatalog(json.loads(text))

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
        summary: Short description shown on the approval sheet.
        untrusted_output: The result can carry third-party text (messages, mail, screens, web pages);
            reading it taints the turn, which suspends remembered approvals until the next turn.
        scope_arg: Input field (or fields) a remembered approval is keyed on (e.g. ``to``), so approving texts
            to one contact never covers another; ``("to", "cc")`` also keeps it from covering added recipients.
        assess: Optional ``(tool_input) -> (tier, summary)`` for tools whose risk depends on their input (the
            app operator's tap is a commit point or not by its label); it replaces ``tier`` and ``summary``.
    """

    tier: Tier
    summary: str = ""
    untrusted_output: bool = False
    scope_arg: str | tuple[str, ...] | None = None
    assess: Callable[[Any], tuple[Tier, str]] | None = None


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
    """The policy's verdict on one tool use."""

    tool: str
    tier: Tier
    summary: str
    operations: tuple[dict[str, str], ...] = ()
    scope: str = ""
    untrusted_output: bool = False
    preview: str = ""

    @property
    def high(self) -> bool:
        """Needs the strongest confirmation (biometric on the phone)."""
        return self.tier in HIGH_TIERS

    def prompt(self, *, tainted: bool) -> str:
        """The interrupt reason: compact JSON the phone renders as an approval sheet."""
        body: dict[str, Any] = {
            "v": 1,
            "tool": self.tool,
            "tier": self.tier,
            "high": self.high,
            "summary": self.summary,
            "preview": self.preview,
            "remember": self.tier in REMEMBERABLE_TIERS and not tainted,
        }
        if self.operations:
            body["operations"] = list(self.operations)
        if tainted:
            body["untrusted_turn"] = True
        return json.dumps(body, separators=(",", ":"), ensure_ascii=False)


def parse_approval(response: Any) -> tuple[bool, int]:
    """``(approved, remember_minutes)`` from an interrupt response; anything unrecognised is a denial."""
    if response is True or (isinstance(response, str) and response.strip().lower() in ("y", "yes")):
        return True, 0
    if not isinstance(response, Mapping) or response.get("approved") is not True:
        return False, 0
    minutes = response.get("remember_minutes", 0)
    if isinstance(minutes, bool) or not isinstance(minutes, int) or minutes <= 0:
        return True, 0
    return True, min(minutes, MAX_REMEMBER_MINUTES)


@dataclass
class _ApprovalState:
    """View over the approvals entry in ``agent.state``."""

    state: Any
    now: float
    data: dict[str, Any] = field(init=False)

    def __post_init__(self) -> None:
        stored = self.state.get(APPROVALS_KEY)
        self.data = stored if isinstance(stored, dict) else {}

    @property
    def turn(self) -> str | None:
        turn = self.state.get(TURN_KEY)
        return turn if isinstance(turn, str) else None

    def tainted(self) -> bool:
        taint = self.data.get("taint_turn")
        return taint is not None and taint == self.turn

    def taint(self) -> None:
        self.data["taint_turn"] = self.turn
        self._save()

    def covered(self, assessment: Assessment) -> bool:
        return any(
            rule.get("tool") == assessment.tool and rule.get("scope") == assessment.scope for rule in self._live_rules()
        )

    def remember(self, assessment: Assessment, minutes: int) -> None:
        rules = [
            rule
            for rule in self._live_rules()
            if not (rule.get("tool") == assessment.tool and rule.get("scope") == assessment.scope)
        ]
        rules.append({"tool": assessment.tool, "scope": assessment.scope, "expires_at": self.now + minutes * 60})
        self.data["rules"] = rules
        self._save()

    def _live_rules(self) -> list[dict[str, Any]]:
        rules = self.data.get("rules")
        if not isinstance(rules, list):
            return []
        return [
            rule
            for rule in rules
            if isinstance(rule, dict)
            and isinstance(rule.get("expires_at"), int | float)
            and rule["expires_at"] > self.now
        ]

    def _save(self) -> None:
        self.state.set(APPROVALS_KEY, self.data)


class RivetApprovals(InterventionHandler):
    """Asks the user before any tool call that is not provably read-only.

    Args:
        policies: Policies for local tools (device tools, task tools), merged over ``INTERNAL_POLICIES``.
        catalog: Telnyx catalog; defaults to the bundled one.
        clock: Wall-clock source for remembered-approval expiry.
    """

    def __init__(
        self,
        policies: Mapping[str, ToolPolicy] | None = None,
        *,
        catalog: TelnyxCatalog | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.policies = {**INTERNAL_POLICIES, **(policies or {})}
        self.catalog = catalog or TelnyxCatalog.bundled()
        self._clock = clock

    @property
    def name(self) -> str:
        """Interrupt name; with the tool use id it fixes each approval's interrupt id."""
        return "rivet-approval"

    @property
    def on_error(self) -> OnError:
        """A broken policy check denies the call."""
        return "deny"

    def assess(self, tool: str, tool_input: Any) -> Assessment:
        """Classify one tool use."""
        preview = _preview(tool_input)
        if tool == TELNYX_EXECUTE:
            return self._assess_execute(tool_input, preview)
        if tool in _TELNYX_READ_TOOLS:
            return Assessment(tool, "read", _TELNYX_READ_TOOLS[tool], preview=preview)
        policy = self.policies.get(tool)
        if policy is None:
            return Assessment(tool, "unknown", "Tool has no approval policy", preview=preview)
        scope = ""
        if policy.scope_arg is not None:
            data = tool_input if isinstance(tool_input, Mapping) else {}
            fields = (policy.scope_arg,) if isinstance(policy.scope_arg, str) else policy.scope_arg
            scope = ",".join(f"{name}={data.get(name)}" for name in fields)
        tier, summary = policy.tier, policy.summary or tool
        if policy.assess is not None:
            tier, summary = policy.assess(tool_input)
        return Assessment(
            tool,
            tier,
            summary,
            scope=scope,
            untrusted_output=policy.untrusted_output,
            preview=preview,
        )

    def _assess_execute(self, tool_input: Any, preview: str) -> Assessment:
        code = tool_input.get("code") if isinstance(tool_input, Mapping) else None
        if not isinstance(code, str):
            return Assessment(TELNYX_EXECUTE, "unknown", "Telnyx code is missing", preview=preview)
        tier: Tier = "read"
        operations: list[dict[str, str]] = []
        for target in call_tool_targets(code):
            target_tier, display = self.catalog.describe(target)
            tier = max(tier, target_tier, key=_RANK.__getitem__)
            if display not in operations:
                operations.append(display)
        summaries = [op.get("summary") or op["name"] for op in operations if op.get("method") != "GET"]
        summary = "Telnyx: " + ("; ".join(summaries) if summaries else "read-only calls")
        scope = ",".join(sorted({op["name"] for op in operations}))
        # Telnyx results can carry inbound message text and caller data.
        return Assessment(
            TELNYX_EXECUTE,
            tier,
            summary,
            tuple(operations),
            scope=scope,
            untrusted_output=True,
            preview=code[:PREVIEW_LIMIT],
        )

    def before_tool_call(self, event: BeforeToolCallEvent, **kwargs: Any) -> Proceed | Confirm:
        """Run reads; ask for everything else unless a live remembered approval covers it."""
        if event.selected_tool is None:
            return Proceed(reason="tool not found; the SDK reports the error")
        assessment = self.assess(event.tool_use["name"], event.tool_use.get("input"))
        if assessment.tier == "read":
            return Proceed()
        approvals = _ApprovalState(event.agent.state, self._clock())
        tainted = approvals.tainted()
        if assessment.tier in REMEMBERABLE_TIERS and not tainted and approvals.covered(assessment):
            logger.debug("tool=<%s>, scope=<%s> | remembered approval applies", assessment.tool, assessment.scope)
            return Proceed(reason="remembered approval")
        return Confirm(
            prompt=assessment.prompt(tainted=tainted), evaluate=partial(self._evaluate, event.agent.state, assessment)
        )

    def after_tool_call(self, event: AfterToolCallEvent, **kwargs: Any) -> Proceed:
        """Taint the turn once it has read third-party content."""
        assessment = self.assess(event.tool_use["name"], event.tool_use.get("input"))
        if event.cancel_message is None and assessment.untrusted_output:
            self.mark_untrusted(event.agent.state)
        return Proceed()

    def mark_untrusted(self, state: Any) -> None:
        """Taint the current turn (also called when Perplexity's native tools bring in web content)."""
        approvals = _ApprovalState(state, self._clock())
        if not approvals.tainted():
            approvals.taint()

    def _evaluate(self, state: Any, assessment: Assessment, response: Any) -> bool:
        approved, minutes = parse_approval(response)
        if approved and minutes and assessment.tier in REMEMBERABLE_TIERS:
            _ApprovalState(state, self._clock()).remember(assessment, minutes)
        logger.info(
            "tool=<%s>, tier=<%s>, approved=<%s> | approval answered", assessment.tool, assessment.tier, approved
        )
        return approved


def _preview(tool_input: Any) -> str:
    try:
        text = json.dumps(tool_input, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = repr(tool_input)
    return text[:PREVIEW_LIMIT]
