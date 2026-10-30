from __future__ import annotations

import json

from strands.storage import InMemoryStorage

from emrys_brain.legacy import (
    LEGACY_MEMORY_PREFIX,
    MEMORY_PREFIX,
    adopt_environment,
    carry_over,
    legacy_session_key,
    read_with_legacy,
)
from emrys_brain.rules import RULES_FORMAT, StandingRule, StandingRules, Target


def test_rivet_variables_are_read_under_their_emrys_names_and_emrys_ones_win() -> None:
    environ = {
        "RIVET_DEVICE_TOKEN": "old-token",
        "RIVET_MODEL": "old-model",
        "EMRYS_MODEL": "new-model",
        "PATH": "/bin",
    }
    adopt_environment(environ)
    assert environ["EMRYS_DEVICE_TOKEN"] == "old-token"
    assert environ["EMRYS_MODEL"] == "new-model"
    assert environ["RIVET_DEVICE_TOKEN"] == "old-token"
    assert "EMRYS_PATH" not in environ


async def test_a_session_file_written_before_the_rename_is_read_until_the_new_one_exists() -> None:
    backend = InMemoryStorage()
    key = "session/phone-1/emrys/model.json"
    assert legacy_session_key(key) == "session/phone-1/rivet/model.json"
    assert await read_with_legacy(backend, key) is None
    await backend.write("session/phone-1/rivet/model.json", b'{"model": "groq"}')
    assert await read_with_legacy(backend, key) == b'{"model": "groq"}'
    await backend.write(key, b'{"model": "perplexity"}')
    assert await read_with_legacy(backend, key) == b'{"model": "perplexity"}'


async def test_approval_rules_saved_before_the_rename_still_apply() -> None:
    backend = InMemoryStorage()
    saved = StandingRules(backend, "phone-1")
    assert saved.key == "session/phone-1/emrys/approval-rules.json"
    deny = StandingRule(Target.of("sms_send", {"to": "+15550000000"}), "deny", "Texts to +15550000000", 1.0)
    document = {"v": RULES_FORMAT, "revision": 3, "rules": [deny.to_wire()]}
    await backend.write("session/phone-1/rivet/approval-rules.json", json.dumps(document).encode())
    rules = StandingRules(backend, "phone-1")
    await rules.load()
    assert rules.revision == 3
    assert [rule.label for rule in rules.rules()] == ["Texts to +15550000000"]


async def test_memories_are_carried_over_once_and_the_old_ones_stay() -> None:
    backend = InMemoryStorage()
    await backend.write(LEGACY_MEMORY_PREFIX + "dana.md", b"Dana is the dentist.")
    await backend.write(LEGACY_MEMORY_PREFIX + "coffee.md", b"Oat milk.")
    assert await carry_over(backend, LEGACY_MEMORY_PREFIX, MEMORY_PREFIX) == 2
    assert await backend.read(MEMORY_PREFIX + "dana.md") == b"Dana is the dentist."
    assert await backend.read(LEGACY_MEMORY_PREFIX + "dana.md") == b"Dana is the dentist."
    await backend.write(MEMORY_PREFIX + "dana.md", b"Dana moved to Provo.")
    assert await carry_over(backend, LEGACY_MEMORY_PREFIX, MEMORY_PREFIX) == 0
    assert await backend.read(MEMORY_PREFIX + "dana.md") == b"Dana moved to Provo."
