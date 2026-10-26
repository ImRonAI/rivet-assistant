"""Generation-fenced storage: one writer per session, even while an abandoned agent is still running.

The supervisor gives each agent generation its own ``FencedStorage`` view over the same backend. When it
replaces a hung generation it advances the fence, so every later write or delete from the old view raises
``StorageError`` instead of overwriting the new generation's snapshot, memory, or context stash. Writes
that had already passed the check are counted, and ``FenceHolder.drain`` waits them out before the next
generation reads the session back.

Reads are not fenced: a stale reader cannot corrupt anything.
"""

from __future__ import annotations

import asyncio
import builtins
import logging
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager

from strands.storage import Storage
from strands.storage.storage import StorageSearchResult
from strands.types.exceptions import StorageError

logger = logging.getLogger(__name__)


class FencedWriteError(StorageError):
    """A write or delete came from a generation that has been replaced."""


class FenceHolder:
    """The current writer generation for one session, shared by all of its ``FencedStorage`` views.

    Thread-safe: writes may arrive from the event loop or from tool threads. The lock is never held
    across an ``await``.
    """

    def __init__(self, generation: int = 0) -> None:
        self._generation = generation
        self._inflight: dict[int, int] = {}
        self._lock = threading.Lock()

    @property
    def generation(self) -> int:
        """The generation allowed to write."""
        with self._lock:
            return self._generation

    def advance(self) -> int:
        """Fence off every existing view and return the new current generation."""
        with self._lock:
            self._generation += 1
            return self._generation

    def inflight(self, generation: int | None = None) -> int:
        """Writes in progress for ``generation``, or for every generation."""
        with self._lock:
            if generation is None:
                return sum(self._inflight.values())
            return self._inflight.get(generation, 0)

    @contextmanager
    def writing(self, generation: int, key: str) -> Iterator[None]:
        """Admit one write from ``generation`` if it is current, counting it until it finishes."""
        with self._lock:
            if generation != self._generation:
                raise FencedWriteError(
                    f"generation {generation} was replaced by {self._generation}; refusing to write '{key}'"
                )
            self._inflight[generation] = self._inflight.get(generation, 0) + 1
        try:
            yield
        finally:
            with self._lock:
                remaining = self._inflight[generation] - 1
                if remaining:
                    self._inflight[generation] = remaining
                else:
                    del self._inflight[generation]

    async def drain(self, *, below: int | None = None, timeout: float = 10.0, interval: float = 0.02) -> bool:
        """Wait until no generation older than ``below`` (default: the current one) has a write in flight.

        Returns:
            True once drained, False if writes were still running at ``timeout``.
        """
        limit = self.generation if below is None else below
        deadline = time.monotonic() + timeout
        while True:
            with self._lock:
                pending = {g: n for g, n in self._inflight.items() if g < limit}
            if not pending:
                return True
            if time.monotonic() >= deadline:
                logger.warning("pending=<%s> | stale writes still running after drain timeout", pending)
                return False
            await asyncio.sleep(interval)


class FencedStorage:
    """A ``Storage`` view that may write only while its generation is current.

    It defines no ``namespace()``, so SDK constructs that scope storage (the snapshot session manager,
    file memory stores, the context stash) wrap it in their own prefixed view, which still writes
    through this one. An ephemeral backend stays marked ephemeral.

    Args:
        storage: The real backend (``LocalFileStorage``, ``S3Storage``, ...).
        fence: The session's fence.
        generation: The generation this view writes for; defaults to the fence's current one.
    """

    def __init__(self, storage: Storage, fence: FenceHolder, generation: int | None = None) -> None:
        self._storage = storage
        self.fence = fence
        self.generation = fence.generation if generation is None else generation
        if hasattr(storage, "_ephemeral"):
            self._ephemeral = storage._ephemeral

    @property
    def backend(self) -> Storage:
        """The unfenced backend, for the supervisor's own recovery reads and checkpoint writes."""
        return self._storage

    @property
    def current(self) -> bool:
        """Whether this view may still write."""
        return self.generation == self.fence.generation

    async def write(self, key: str, data: bytes) -> None:
        """Store data under key if this generation is current."""
        with self.fence.writing(self.generation, key):
            await self._storage.write(key, data)

    async def delete(self, key: str) -> None:
        """Delete key if this generation is current."""
        with self.fence.writing(self.generation, key):
            await self._storage.delete(key)

    async def read(self, key: str) -> bytes | None:
        """Read key from the backend."""
        return await self._storage.read(key)

    async def list(self, query: str = "") -> builtins.list[str]:
        """List keys with the given prefix."""
        return await self._storage.list(query)

    async def search(self, query: str) -> builtins.list[StorageSearchResult]:
        """Search the backend."""
        return await self._storage.search(query)
