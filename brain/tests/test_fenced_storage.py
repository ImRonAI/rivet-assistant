from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from strands import Agent
from strands.session import SnapshotSessionManager
from strands.storage import InMemoryStorage, LocalFileStorage
from strands.types.exceptions import StorageError

from rivet_brain.models.perplexity import build_perplexity_model
from rivet_brain.storage import FencedStorage, FencedWriteError, FenceHolder
from tests.sse import SSE, FakePerplexity


async def test_stale_generation_cannot_write_or_delete_but_can_read() -> None:
    backend = InMemoryStorage()
    fence = FenceHolder()
    old = FencedStorage(backend, fence)
    await old.write("session/a", b"1")
    assert fence.advance() == 1
    new = FencedStorage(backend, fence)
    assert not old.current and new.current

    with pytest.raises(FencedWriteError) as raised:
        await old.write("session/a", b"zombie")
    assert isinstance(raised.value, StorageError)
    with pytest.raises(FencedWriteError):
        await old.delete("session/a")
    assert await old.read("session/a") == b"1"
    await new.write("session/a", b"2")
    assert await backend.read("session/a") == b"2" and await new.list("session/") == ["session/a"]


class SlowStorage(InMemoryStorage):
    def __init__(self) -> None:
        super().__init__()
        self.release = asyncio.Event()

    async def write(self, key: str, data: bytes) -> None:
        await self.release.wait()
        await super().write(key, data)


async def test_drain_waits_for_a_write_that_passed_the_fence_before_it_moved() -> None:
    backend = SlowStorage()
    fence = FenceHolder()
    zombie = asyncio.create_task(FencedStorage(backend, fence).write("k", b"old"))
    await asyncio.sleep(0)
    assert fence.inflight(0) == 1
    fence.advance()
    assert await fence.drain(timeout=0.05) is False

    backend.release.set()
    assert await fence.drain(timeout=1) is True
    await zombie
    assert fence.inflight() == 0 and await backend.read("k") == b"old"


async def test_failed_write_still_leaves_the_inflight_count() -> None:
    class Failing(InMemoryStorage):
        async def write(self, key: str, data: bytes) -> None:
            raise StorageError("disk full")

    fence = FenceHolder()
    with pytest.raises(StorageError, match="disk full"):
        await FencedStorage(Failing(), fence).write("k", b"x")
    assert fence.inflight() == 0


def test_ephemeral_marker_is_preserved_only_for_ephemeral_backends(tmp_path: Path) -> None:
    fence = FenceHolder()
    memory = InMemoryStorage()
    assert FencedStorage(memory, fence)._ephemeral is memory._ephemeral  # type: ignore[attr-defined]
    assert not hasattr(FencedStorage(LocalFileStorage(str(tmp_path)), fence), "_ephemeral")


def _agent(fake: FakePerplexity, storage: FencedStorage) -> Agent:
    model = build_perplexity_model(
        api_key="k", model_id="m", native_tools=(), transport_factory=fake.transport_factory()
    )
    manager = SnapshotSessionManager("phone-1", storage=storage, save_latest_on="message")
    return Agent(model=model, session_manager=manager, callback_handler=None)


def test_replaced_generation_cannot_overwrite_the_session_and_the_new_one_restores_it(tmp_path: Path) -> None:
    backend = LocalFileStorage(str(tmp_path))
    fence = FenceHolder()
    fake = FakePerplexity(
        [
            SSE().text("Hi, I'm Rivet.").completed(),
            SSE("r2").text("zombie").completed(),
            SSE("r3").text("Still here.").completed(),
        ]
    )

    first = _agent(fake, FencedStorage(backend, fence))
    first("hello")
    assert len(first.messages) == 2

    fence.advance()
    second = _agent(fake, FencedStorage(backend, fence))
    assert [m["role"] for m in second.messages] == ["user", "assistant"]

    with pytest.raises(StorageError, match="generation 0 was replaced by 1"):
        first("are you still running?")

    third_view = _agent(fake, FencedStorage(backend, fence))
    assert len(third_view.messages) == 2  # the zombie's turn never reached storage
    second("still there?")
    assert len(second.messages) == 4
