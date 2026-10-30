"""Runtime settings, read from the environment once at startup."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Literal

DEFAULT_TELNYX_MCP_URL = "https://oubli-catolabs.fastmcp.app/mcp"
DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"
DEFAULT_GROQ_CONTEXT_WINDOW = 131_072  # qwen/qwen3.8-27b on Groq (its model card)

Provider = Literal["perplexity", "groq"]
"""Which API Rivet's own agent runs on."""
PROVIDERS: dict[str, Provider] = {"perplexity": "perplexity", "groq": "groq"}


def _env_list(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = os.environ.get(name)
    if not raw:
        return default
    return tuple(item.strip() for item in raw.split(",") if item.strip())


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    return float(raw) if raw else default


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    return int(raw) if raw else default


@dataclass(frozen=True)
class WatchdogSettings:
    """Phase deadlines the supervisor enforces, in seconds.

    ``idle`` is generous because Perplexity runs its server-side tools (search, fetch) without
    streaming tokens; the model subclass emits activity chunks for those events, so a long but
    healthy search still refreshes the heartbeat.
    """

    first_chunk: float = 90.0
    idle: float = 120.0
    model_total: float = 600.0
    tool_default: float = 120.0
    telnyx_execute: float = 150.0  # covers a foreground await_telnyx_resource poll, bounded at 120 s server-side
    soft_grace: float = 15.0
    hard_grace: float = 15.0
    build: float = 120.0
    cleanup: float = 15.0
    drain: float = 10.0
    poll: float = 0.25


@dataclass(frozen=True)
class BrainSettings:
    """Everything the brain needs to build agents. Secrets stay in the environment."""

    perplexity_api_key: str | None
    model_provider: Provider = "perplexity"
    groq_api_key: str | None = field(default=None, repr=False)
    groq_model_id: str = DEFAULT_GROQ_MODEL
    groq_context_window: int = DEFAULT_GROQ_CONTEXT_WINDOW
    horizon_api_key_env: str = "HORIZON_API_KEY"
    perplexity_base_url: str = "https://api.perplexity.ai/v1"
    model_id: str = "openai/gpt-5.6-sol"
    fallback_models: tuple[str, ...] = ()
    aux_model_id: str = "openai/gpt-5.6-luna"
    context_window_limit: int = 400_000
    max_steps: int = 8
    telnyx_mcp_url: str = DEFAULT_TELNYX_MCP_URL
    session_dir: str = "./.rivet/sessions"
    skills_dir: str = "./.rivet/skills"
    max_restarts: int = 4
    restart_window: float = 600.0
    retry_attempts: int = 6
    retry_initial_delay: int = 4
    retry_max_delay: int = 60
    restart_backoff: float = 2.0
    approval_timeout: float = 600.0
    turn_limits: dict[str, int] = field(default_factory=lambda: {"turns": 40, "total_tokens": 2_000_000})
    watchdog: WatchdogSettings = field(default_factory=WatchdogSettings)

    @classmethod
    def from_env(cls) -> BrainSettings:
        """Build settings from ``RIVET_*`` and provider environment variables."""
        provider = os.environ.get("RIVET_MODEL_PROVIDER", "perplexity").strip() or "perplexity"
        if provider not in PROVIDERS:
            raise ValueError("RIVET_MODEL_PROVIDER must be perplexity or groq")
        return cls(
            perplexity_api_key=os.environ.get("PERPLEXITY_API_KEY"),
            model_provider=PROVIDERS[provider],
            groq_api_key=os.environ.get("GROQ_API_KEY"),
            groq_model_id=os.environ.get("GROQ_MODEL") or DEFAULT_GROQ_MODEL,
            groq_context_window=_env_int("GROQ_CONTEXT_WINDOW", DEFAULT_GROQ_CONTEXT_WINDOW),
            perplexity_base_url=os.environ.get("PERPLEXITY_BASE_URL", "https://api.perplexity.ai/v1"),
            model_id=os.environ.get("RIVET_MODEL", "openai/gpt-5.6-sol"),
            fallback_models=_env_list("RIVET_FALLBACK_MODELS", ()),
            aux_model_id=os.environ.get("RIVET_AUX_MODEL", "openai/gpt-5.6-luna"),
            context_window_limit=_env_int("RIVET_CONTEXT_WINDOW", 400_000),
            max_steps=_env_int("RIVET_MAX_STEPS", 8),
            telnyx_mcp_url=os.environ.get("TELNYX_MCP_URL", DEFAULT_TELNYX_MCP_URL),
            session_dir=os.environ.get("RIVET_SESSION_DIR", "./.rivet/sessions"),
            skills_dir=os.environ.get("RIVET_SKILLS_DIR", "./.rivet/skills"),
            max_restarts=_env_int("RIVET_MAX_RESTARTS", 4),
            restart_window=_env_float("RIVET_RESTART_WINDOW", 600.0),
            retry_attempts=_env_int("RIVET_RETRY_ATTEMPTS", 6),
            retry_initial_delay=_env_int("RIVET_RETRY_INITIAL_DELAY", 4),
            retry_max_delay=_env_int("RIVET_RETRY_MAX_DELAY", 60),
            restart_backoff=_env_float("RIVET_RESTART_BACKOFF", 2.0),
            approval_timeout=_env_float("RIVET_APPROVAL_TIMEOUT", 600.0),
            watchdog=WatchdogSettings(
                first_chunk=_env_float("RIVET_T_FIRST", 90.0),
                idle=_env_float("RIVET_T_IDLE", 120.0),
                model_total=_env_float("RIVET_T_MODEL_TOTAL", 600.0),
                tool_default=_env_float("RIVET_T_TOOL", 120.0),
                soft_grace=_env_float("RIVET_G_SOFT", 15.0),
                hard_grace=_env_float("RIVET_G_HARD", 15.0),
                telnyx_execute=_env_float("RIVET_T_TELNYX_EXECUTE", 150.0),
                build=_env_float("RIVET_T_BUILD", 120.0),
            ),
        )


def available_providers(settings: BrainSettings) -> tuple[Provider, ...]:
    """The providers whose API key is set."""
    keys: dict[Provider, str | None] = {"perplexity": settings.perplexity_api_key, "groq": settings.groq_api_key}
    return tuple(provider for provider, key in keys.items() if key)


def default_provider(settings: BrainSettings) -> Provider:
    """``model_provider`` when its key is set, else the first provider that has one."""
    available = available_providers(settings)
    return settings.model_provider if settings.model_provider in available or not available else available[0]
