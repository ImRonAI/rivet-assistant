"""Bearer auth for the Telnyx MCP server on Prefect Horizon."""

from __future__ import annotations

import os
from collections.abc import Generator
from typing import override

import httpx


class MissingCredentialError(RuntimeError):
    """The environment variable holding a credential is unset or empty."""


class HorizonBearer(httpx.Auth):
    """Sends a Horizon API key as ``Authorization: Bearer``, read from the environment on every request.

    Reading per request means a rotated key applies on the next call without rebuilding the MCP client.
    The key never appears in logs, reprs, or tool output.

    Args:
        env_var: Environment variable holding the key.
    """

    def __init__(self, env_var: str = "HORIZON_API_KEY") -> None:
        self.env_var = env_var

    @override
    def __repr__(self) -> str:
        return f"HorizonBearer(env_var={self.env_var!r})"

    def token(self) -> str:
        """The current key; raises if it is not configured."""
        value = os.environ.get(self.env_var, "").strip()
        if not value:
            raise MissingCredentialError(f"{self.env_var} is not set; the Telnyx MCP server requires it")
        return value

    @override
    def auth_flow(self, request: httpx.Request) -> Generator[httpx.Request, httpx.Response, None]:
        """Attach the bearer to the request."""
        request.headers["Authorization"] = f"Bearer {self.token()}"
        yield request
