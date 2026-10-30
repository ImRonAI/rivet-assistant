"""Names from before the assistant was renamed Rivet to Emrys, read so existing deployments keep working.

New code writes only the Emrys names. A deployment set up earlier still has ``RIVET_*`` secrets, session files
under ``session/<id>/rivet/`` and memories under ``memory/rivet/``; these helpers read those when the Emrys
names are missing, and never delete them.
"""

from __future__ import annotations

import logging
import os
from collections.abc import MutableMapping

from strands.storage import Storage

logger = logging.getLogger(__name__)

ENV_PREFIX = "RIVET_"
MEMORY_PREFIX = "memory/emrys/"
LEGACY_MEMORY_PREFIX = "memory/rivet/"


def adopt_environment(environ: MutableMapping[str, str] = os.environ) -> None:
    """Expose each ``RIVET_*`` variable under its ``EMRYS_*`` name; an ``EMRYS_*`` variable that is set wins."""
    for name, value in list(environ.items()):
        if name.startswith(ENV_PREFIX):
            environ.setdefault("EMRYS_" + name[len(ENV_PREFIX) :], value)


def legacy_session_key(key: str) -> str:
    """The pre-rename key of a session file: ``session/<id>/emrys/x`` was ``session/<id>/rivet/x``."""
    return key.replace("/emrys/", "/rivet/", 1)


async def read_with_legacy(backend: Storage, key: str) -> bytes | None:
    """Read ``key``, or the same file under its pre-rename key when ``key`` was never written."""
    raw = await backend.read(key)
    if raw is not None:
        return raw
    return await backend.read(legacy_session_key(key))


async def carry_over(backend: Storage, old_prefix: str, new_prefix: str) -> int:
    """Copy every key under ``old_prefix`` to ``new_prefix`` once, when nothing is under ``new_prefix`` yet.

    Returns how many keys were copied. The old keys stay where they are.
    """
    if await backend.list(new_prefix):
        return 0
    copied = 0
    for key in await backend.list(old_prefix):
        data = await backend.read(key)
        if data is not None:
            await backend.write(new_prefix + key[len(old_prefix) :], data)
            copied += 1
    if copied:
        logger.info("copied=<%d>, old=<%s>, new=<%s> | carried pre-rename data over", copied, old_prefix, new_prefix)
    return copied
