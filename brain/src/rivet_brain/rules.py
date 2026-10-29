"""Standing approval rules: the user's "don't ask again" answers, kept per session.

A rule is about one target: a tool plus a ``scope`` of normalized input fields (the action and its recipient), so
"always allow texts to Dana" never covers a text to anyone else. Which tools can have rules, and which of their
fields make up the scope, is declared per tool with a ``Standing`` on its ``ToolPolicy``.

Rules are written only through ``admit``, which refuses an ``allow`` the brain's own offer did not grant: purchases,
destructive and unrecognised actions, deny-only kinds of action (app taps, Telnyx code) and turns that read
third-party content can only ever be standing denies. A stored allow is followed only where that offer would grant
one at the time of the call, so a rule that reached storage some other way fails closed. ``StandingRules`` keeps
one session's rules in the session's ``Storage`` backend, so they move with everything else the session stores.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Literal, NotRequired, TypedDict, TypeGuard
from urllib.parse import quote

from strands.storage import InMemoryStorage, Storage

logger = logging.getLogger(__name__)

Decision = Literal["allow", "deny"]
NoAllow = Literal["purchase", "destructive", "unknown", "kind", "untrusted"]
Normalizer = Literal["phone", "emails", "text", "flag"]

RULES_LIMIT = 200
RULES_FORMAT = 1
_PHONE_NOISE = str.maketrans("", "", " \t\n\r\f\x0b().-")
_DECISIONS: frozenset[str] = frozenset({"allow", "deny"})
_NO_ALLOW: frozenset[str] = frozenset({"purchase", "destructive", "unknown", "kind", "untrusted"})

LIMIT_REACHED = f"Rivet keeps at most {RULES_LIMIT} rules. Remove one in Settings › Approval rules first."
NOT_ALLOWABLE = "This kind of action can't be always allowed."
NOT_DENIABLE = "Rivet can't make a rule for this."


class RuleRefusedError(ValueError):
    """A rule was refused; the message is plain text shown on the phone."""


def normalize(normalizer: Normalizer, value: object) -> str | None:
    """One scope value in its comparable form; ``None`` when the value's type makes the call ineligible."""
    if normalizer == "flag":
        if value is None:
            return "false"
        return ("true" if value else "false") if isinstance(value, bool) else None
    if value is None:
        return ""
    if not isinstance(value, str):
        return None
    if normalizer == "phone":
        return value.translate(_PHONE_NOISE)
    if normalizer == "emails":
        return ",".join(sorted(part.strip().lower() for part in value.split(",") if part.strip()))
    return value.strip()


@dataclass(frozen=True)
class Target:
    """Action + recipient: what a standing rule is about."""

    tool: str
    scope: tuple[tuple[str, str], ...]
    """Normalized fields, sorted by field name."""

    @classmethod
    def of(cls, tool: str, scope: Mapping[str, str]) -> Target:
        """A target from a scope mapping."""
        return cls(tool, tuple(sorted(scope.items())))

    @property
    def key(self) -> str:
        """Opaque id the phone revokes a rule by."""
        fields = "&".join(f"{quote(name, safe='')}={quote(value, safe='')}" for name, value in self.scope)
        return f"{self.tool}?{fields}"

    def scope_dict(self) -> dict[str, str]:
        """The scope as a mapping."""
        return dict(self.scope)


@dataclass(frozen=True)
class Standing:
    """How a tool's calls become rule targets.

    Attributes:
        fields: Scope fields and their normalizers, in this order.
        required: Fields that must be non-empty after normalizing.
        allow: False when this kind of action can only be standing-denied.
        label: Noun phrase for a target, from its normalized scope ("Texts to +18015550100").
        when: Raw input fields that must hold one of these string values for a call to be eligible.
        allow_only: Normalized scope values a target must have to be standing-allowed; any other target of this
            tool can only be standing-denied.
    """

    fields: tuple[tuple[str, Normalizer], ...]
    required: frozenset[str]
    allow: bool
    label: Callable[[Mapping[str, str]], str]
    when: tuple[tuple[str, frozenset[str]], ...] = ()
    allow_only: tuple[tuple[str, str], ...] = ()

    def allows(self, target: Target) -> bool:
        """Whether ``target`` may be standing-allowed at all."""
        scope = target.scope_dict()
        return self.allow and all(scope.get(name) == value for name, value in self.allow_only)

    def target(self, tool: str, tool_input: object) -> Target | None:
        """The rule target of one call, or ``None`` when the call can't have a rule."""
        if not isinstance(tool_input, Mapping):
            return None
        for name, allowed in self.when:
            value = tool_input.get(name)
            if not isinstance(value, str) or value not in allowed:
                return None
        scope: dict[str, str] = {}
        for name, normalizer in self.fields:
            value = normalize(normalizer, tool_input.get(name))
            if value is None:
                return None
            scope[name] = value
        if any(not scope.get(name) for name in self.required):
            return None
        return Target.of(tool, scope)

    def input_for(self, scope: Mapping[str, str]) -> dict[str, object] | None:
        """A tool input equivalent to ``scope`` (for a rule the phone asks for), or ``None`` if it can't be one."""
        if set(scope) != {name for name, _ in self.fields}:
            return None
        data: dict[str, object] = {}
        for name, allowed in self.when:
            if len(allowed) == 1:
                data[name] = next(iter(allowed))
        for name, normalizer in self.fields:
            value = scope[name]
            if normalizer == "flag":
                if value not in ("true", "false"):
                    return None
                data[name] = value == "true"
            else:
                data[name] = value
        return data


class OfferWire(TypedDict):
    """A rule offer as the approval sheet receives it."""

    key: str
    tool: str
    scope: dict[str, str]
    label: str
    allow: bool
    deny: bool
    no_allow: NotRequired[NoAllow]


class RuleWire(TypedDict):
    """A standing rule as stored and as the phone receives it."""

    key: str
    tool: str
    scope: dict[str, str]
    decision: Decision
    label: str
    created_at: float


class RulesEvent(TypedDict):
    """The ``rules`` event: every standing rule of the session."""

    type: Literal["rules"]
    revision: int
    rules: list[RuleWire]


@dataclass(frozen=True)
class RuleOffer:
    """The "don't ask again" choices an approval sheet shows for one call."""

    target: Target
    label: str
    allow: bool
    deny: bool
    no_allow: NoAllow | None
    """Why ``allow`` is False; None when it is True."""

    def to_wire(self) -> OfferWire:
        """The offer inside an approval prompt."""
        wire: OfferWire = {
            "key": self.target.key,
            "tool": self.target.tool,
            "scope": self.target.scope_dict(),
            "label": self.label,
            "allow": self.allow,
            "deny": self.deny,
        }
        if self.no_allow is not None:
            wire["no_allow"] = self.no_allow
        return wire

    @classmethod
    def from_wire(cls, data: object) -> RuleOffer | None:
        """An offer read back from an approval prompt the brain wrote; ``None`` if it is malformed."""
        if not isinstance(data, Mapping):
            return None
        tool, label, scope = data.get("tool"), data.get("label"), _str_map(data.get("scope"))
        allow, deny, no_allow = data.get("allow"), data.get("deny"), data.get("no_allow")
        if not isinstance(tool, str) or not isinstance(label, str) or scope is None:
            return None
        if not isinstance(allow, bool) or not isinstance(deny, bool):
            return None
        reason: NoAllow | None = None
        if no_allow is not None:
            if not _is_no_allow(no_allow):
                return None
            reason = no_allow
        return cls(Target.of(tool, scope), label, allow, deny, reason)


@dataclass(frozen=True)
class StandingRule:
    """One "always allow" or "always decline"."""

    target: Target
    decision: Decision
    label: str
    created_at: float

    def to_wire(self) -> RuleWire:
        """The rule as stored and sent."""
        return {
            "key": self.target.key,
            "tool": self.target.tool,
            "scope": self.target.scope_dict(),
            "decision": self.decision,
            "label": self.label,
            "created_at": self.created_at,
        }

    @classmethod
    def from_wire(cls, data: object) -> StandingRule | None:
        """A stored rule; ``None`` if it fails validation."""
        if not isinstance(data, Mapping):
            return None
        tool, label, scope = data.get("tool"), data.get("label"), _str_map(data.get("scope"))
        decision, created_at = data.get("decision"), data.get("created_at")
        if not isinstance(tool, str) or not tool or not isinstance(label, str) or scope is None:
            return None
        if isinstance(created_at, bool) or not isinstance(created_at, int | float):
            return None
        if _is_decision(decision):
            return cls(Target.of(tool, scope), decision, label, float(created_at))
        return None


def admit(offer: RuleOffer, decision: Decision, *, now: float) -> StandingRule:
    """The rule a user's answer makes from ``offer``; refuses a decision the offer did not grant."""
    if decision == "allow" and not offer.allow:
        raise RuleRefusedError(NOT_ALLOWABLE)
    if decision == "deny" and not offer.deny:
        raise RuleRefusedError(NOT_DENIABLE)
    return StandingRule(offer.target, decision, offer.label, now)


def standing_of(response: object) -> Decision | None:
    """The standing decision an approval answer carries, when it matches the answer itself.

    Only ``allow`` on an approval and ``deny`` on a denial count.
    """
    if not isinstance(response, Mapping):
        return None
    standing, approved = response.get("standing"), response.get("approved") is True
    if standing == "allow" and approved:
        return "allow"
    if standing == "deny" and not approved:
        return "deny"
    return None


class StandingRules:
    """One session's standing rules: durable in ``backend``, cached in memory.

    Args:
        backend: The session storage backend (not a fenced generation view: rules outlive generations).
        session_id: The session the rules belong to.
    """

    def __init__(self, backend: Storage, session_id: str) -> None:
        self._backend = backend
        self.session_id = session_id
        self.key = f"session/{session_id}/rivet/approval-rules.json"
        self._rules: dict[Target, StandingRule] = {}
        self._revision = 0
        self._lock = asyncio.Lock()

    @classmethod
    def in_memory(cls) -> StandingRules:
        """Rules that live only as long as the process (tests and defaults)."""
        return cls(InMemoryStorage(), "local")

    async def load(self) -> None:
        """Read the stored rules; an unreadable document is set aside and the session starts with none."""
        async with self._lock:
            self._rules, self._revision = {}, 0
            raw = await self._backend.read(self.key)
            if raw is None:
                return
            document = _document(raw)
            if document is None:
                logger.warning("session_id=<%s> | approval rules unreadable; starting empty", self.session_id)
                await self._backend.write(f"{self.key}.corrupt-{int(time.time())}", raw)
                return
            revision, items = document
            self._revision = revision
            for item in items:
                rule = StandingRule.from_wire(item)
                if rule is not None:
                    self._rules.pop(rule.target, None)
                    self._rules[rule.target] = rule

    @property
    def revision(self) -> int:
        """Bumped by every change; the phone ignores a rules event older than the one it has."""
        return self._revision

    def rules(self) -> tuple[StandingRule, ...]:
        """Every rule, oldest first."""
        return tuple(self._rules.values())

    def match(self, target: Target) -> StandingRule | None:
        """The rule about exactly this target."""
        return self._rules.get(target)

    async def put(self, rule: StandingRule) -> None:
        """Store ``rule``, replacing any rule about the same target."""
        async with self._lock:
            rules = {target: existing for target, existing in self._rules.items() if target != rule.target}
            if len(rules) >= RULES_LIMIT:
                raise RuleRefusedError(LIMIT_REACHED)
            rules[rule.target] = rule
            await self._write(rules)

    async def revoke(self, key: str) -> bool:
        """Remove the rule with this key; False when there is none."""
        async with self._lock:
            rules = {target: rule for target, rule in self._rules.items() if target.key != key}
            if len(rules) == len(self._rules):
                return False
            await self._write(rules)
            return True

    def event(self) -> RulesEvent:
        """The ``rules`` event for the phone."""
        return {"type": "rules", "revision": self._revision, "rules": [rule.to_wire() for rule in self.rules()]}

    async def _write(self, rules: dict[Target, StandingRule]) -> None:
        revision = self._revision + 1
        body = {"v": RULES_FORMAT, "revision": revision, "rules": [rule.to_wire() for rule in rules.values()]}
        await self._backend.write(self.key, json.dumps(body, ensure_ascii=False).encode())
        self._rules, self._revision = rules, revision


async def apply_answers(
    rules: StandingRules,
    pending: Sequence[Mapping[str, object]],
    answers: Mapping[str, object],
    *,
    now: float,
    on_refused: Callable[[str], None] | None = None,
) -> bool:
    """Write the rules a batch of approval answers asks for, from the offers in the brain's own prompts.

    An answer whose standing does not match it, or that the sheet did not offer, writes nothing; the answer itself
    still goes to the supervisor. Returns True when any rule was written.
    """
    sheets = {sheet.get("id"): sheet for sheet in pending}
    changed = False
    for answer_id, answer in answers.items():
        decision = standing_of(answer)
        sheet = sheets.get(answer_id)
        if decision is None or sheet is None:
            continue
        request = sheet.get("request")
        offer = RuleOffer.from_wire(request.get("offer") if isinstance(request, Mapping) else None)
        if offer is None:
            continue
        try:
            await rules.put(admit(offer, decision, now=now))
        except RuleRefusedError as error:
            logger.info("tool=<%s>, decision=<%s> | standing rule refused", offer.target.tool, decision)
            if on_refused is not None:
                on_refused(str(error))
            continue
        changed = True
    return changed


def _document(raw: bytes) -> tuple[int, Iterable[object]] | None:
    try:
        document = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(document, dict) or document.get("v") != RULES_FORMAT:
        return None
    revision, items = document.get("revision"), document.get("rules")
    if isinstance(revision, bool) or not isinstance(revision, int) or not isinstance(items, list):
        return None
    return revision, items


def _str_map(value: object) -> dict[str, str] | None:
    if not isinstance(value, Mapping):
        return None
    result: dict[str, str] = {}
    for name, field in value.items():
        if not isinstance(name, str) or not isinstance(field, str):
            return None
        result[name] = field
    return result


def _is_decision(value: object) -> TypeGuard[Decision]:
    return isinstance(value, str) and value in _DECISIONS


def _is_no_allow(value: object) -> TypeGuard[NoAllow]:
    return isinstance(value, str) and value in _NO_ALLOW
