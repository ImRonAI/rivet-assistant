"""The text agent's model providers besides ElevenLabs: NVIDIA NIM, Mistral and Gemini.

Copied from ren (github.com/ImRonAI/TheBrowser, `agent/api/core/panel_agent.py` and `config.py` at `0c973a6`): the
provider keys and defaults, the per-model reasoning-effort tables, `build_model` for NIM and Mistral, and the live model
catalogues. Only the three providers Emrys offers are kept; `mistral_model.py`, `media_capabilities.py` and
`context_windows.py` are ren's files beside this one. Gemini's model itself is the harness's own (`google/<id>` with
`effort`), so `build_model` builds NIM and Mistral only.

The copies differ from ren in two ways. For Emrys's strict mypy: imports point at the files beside this one, the NIM
model's `params` is passed by name, two variables are renamed, and `mistral_model.py` hands the vended formatter the
image as the content block it types. For Strands 1.64.0, whose vended Mistral formatter already replays reasoning in
place (without its signature) and puts tool replies before the assistant message: `_replay_reasoning` follows that order
and only adds the signatures back, so thinking is not sent twice. Ren's own `test_mistral_tool_stream.py` and
`test_mistral_model.py` pass against these copies on 1.64.0. The service pins ren's `mistralai` 2.10.1 and `openai`
2.50.0 (`Dockerfile`).
"""

from __future__ import annotations

import os
from typing import Literal

from strands.models import Model

ProviderName = Literal["nim", "mistral", "gemini"]
PROVIDERS: tuple[ProviderName, ...] = ("nim", "mistral", "gemini")

# NVIDIA NIM hosted endpoint (OpenAI-compatible chat completions): https://docs.api.nvidia.com/nim/reference/
NVIDIA_NIM_BASE_URL = os.getenv("NVIDIA_NIM_BASE_URL") or "https://integrate.api.nvidia.com/v1"
NIM_DEFAULT_MODEL = os.getenv("NIM_MODEL") or "z-ai/glm-5.3-flash"
MISTRAL_DEFAULT_MODEL = os.getenv("MISTRAL_MODEL") or "mistral-large-latest"
GEMINI_DEFAULT_MODEL = "gemini-4-argon"

#: ``reasoning_effort`` values of the NIM chat-completions reference for each model
#: (https://docs.api.nvidia.com/nim/reference/z-ai-glm-5-3-flash-infer); unlisted ids take none.
NIM_REASONING_EFFORTS_BY_MODEL: dict[str, tuple[str, ...]] = {"z-ai/glm-5.3-flash": ("low", "high", "max")}

#: Per-model Mistral ``reasoning_effort`` values on ``POST /v1/chat/completions``.
#: Sources, which agree: https://docs.mistral.ai/studio/conversations/reasoning
#: (``mistral-small-latest`` / ``mistral-medium-3-5`` take ``none``/``high``;
#: ``mistral-large-4-0`` takes ``low``/``medium``/``high``; ``zai-glm-5-3`` takes ``low``/``high``/``max``, never ``none``),
#: the installed ``mistralai`` 2.10.1 ``ReasoningEffort`` literal, and a live probe
#: of every id ``client.models.list()`` returned on 2026-10-09: every other
#: generation model answers ``400 "reasoning_effort is not enabled for this
#: model"`` (code 3051). Ordered lowest to highest. ``zai-glm-5-2`` (deprecated
#: 2026-09-29) accepts the whole enum live but Mistral documents no effort for it,
#: so it is left out and reports no efforts. ``max`` is not in the pinned SDK
#: literal, so ``MistralModel`` sends it through ``_MistralReasoningEffortHook``
#: (the SDK marshaller drops ``UnrecognizedStr`` values). Longest prefix wins.
MISTRAL_REASONING_EFFORTS_BY_MODEL: dict[str, tuple[str, ...]] = {
    # Live 2026-11-10: none/minimal/xhigh/max -> 400 "supported values: high, low, medium"; the docs say the same
    # ("Mistral Large 4 does not accept none, minimal, xhigh, or max").
    "mistral-large-4": ("low", "medium", "high"),
    "mistral-medium": ("none", "high"),
    "mistral-small": ("none", "high"),
    "mistral-vibe-cli": ("none", "high"),
    "magistral-medium": ("none", "high"),
    "magistral-small": ("none", "high"),
    "zai-glm-5-3": ("low", "high", "max"),
    "zai-glm-5": ("low", "high", "max"),
    "zai-glm-latest": ("low", "high", "max"),
}

#: Mistral ids that match a family prefix above but reject the parameter live
#: (``mistral-large-latest`` -> ``mistral-large-2512`` = Mistral Large 3).
MISTRAL_REASONING_EFFORTS_EXCLUDED: tuple[str, ...] = (
    "mistral-large-2512",
    "mistral-large-latest",
    "mistral-large-3",
    "zai-glm-5-2",
    "glm-5-2",
)

#: Per-model Gemini ``thinking_config.thinking_level`` values on ``generateContent``.
#: Source: the "Controlling thinking" table at
#: https://ai.google.dev/gemini-api/docs/thinking plus the per-model pages for
#: the Nano Banana image models (``minimal``/``high``; Nano Banana 2.1 adds
#: ``medium``), confirmed live on 2026-10-09 with ``include_thoughts=True`` for
#: every flash id, the ``-latest`` aliases and ``gemini-robotics-er-2-preview``
#: (``MINIMAL`` is rejected with ``400 "Thinking level MINIMAL is not supported"``
#: exactly where the table omits it). TTS, transcribe and computer-use ids answer
#: ``400 "Thinking level is not supported for this model"`` and return no efforts.
#: Ordered lowest to highest. Longest prefix wins.
GEMINI_THINKING_LEVELS_BY_MODEL: dict[str, tuple[str, ...]] = {
    "gemini-3.8-flash": ("low", "medium", "high"),
    "gemini-3.7-flash": ("low", "medium", "high"),
    "gemini-3.6-flash": ("minimal", "low", "medium", "high"),
    "gemini-3.5-flash-lite": ("minimal", "low", "medium", "high"),
    "gemini-3.5-flash": ("minimal", "low", "medium", "high"),
    "gemini-3.1-pro-preview": ("low", "medium", "high"),
    "gemini-3.1-flash-lite-image": ("minimal", "high"),
    "gemini-3.1-flash-lite": ("minimal", "low", "medium", "high"),
    "gemini-3.1-flash-image": ("minimal", "high"),
    "gemini-3-flash-preview": ("minimal", "low", "medium", "high"),
    "gemini-3-pro-preview": ("low", "high"),
    # Nano Banana Pro: model page says thinking is supported but documents no
    # levels, and the Interactions image guide lists only 2.1 / 3.1 Flash Image /
    # 3.1 Flash Lite Image as level-configurable.
    "gemini-3-pro-image": (),
    "gemini-nano-banana-2.1": ("minimal", "medium", "high"),
    "gemini-2.5-pro": ("low", "medium", "high"),
    "gemini-2.5-flash-lite": ("low", "medium", "high"),
    "gemini-2.5-flash-image": (),
    "gemini-2.5-flash": ("low", "medium", "high"),
    "gemini-robotics-er-2-preview": ("minimal", "low", "medium", "high"),
    # Aliases: "Latest release of Gemini Flash/Flash-Lite/Pro" (models.list). Live:
    # flash-latest rejects MINIMAL (= 3.8 Flash), flash-lite-latest accepts all
    # four (= 3.5 Flash-Lite); pro-latest follows 3.1 Pro.
    "gemini-flash-lite-latest": ("minimal", "low", "medium", "high"),
    "gemini-flash-latest": ("low", "medium", "high"),
    "gemini-pro-latest": ("low", "medium", "high"),
}

#: Gemini ids that match a family prefix above but have no thinking control
#: (speech, transcription, image-only and computer-use surfaces).
GEMINI_THINKING_LEVELS_EXCLUDED_SUFFIXES: tuple[str, ...] = (
    "-tts",
    "-tts-preview",
    "-transcribe",
)

#: Documented popular Gemini models used when live listing is unavailable.
GEMINI_FALLBACK_MODELS = (
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
)

#: Documented Mistral example id used when live listing is unavailable.
MISTRAL_FALLBACK_MODELS = ("mistral-large-latest",)


def provider_api_key(provider: str) -> str | None:
    """The provider's key from the service's environment; Gemini's is the harness's `GOOGLE_API_KEY`."""
    name = {"nim": "NVIDIA_NIM_API_KEY", "mistral": "MISTRAL_API_KEY", "gemini": "GOOGLE_API_KEY"}.get(provider)
    return os.getenv(name) if name else None


def provider_default_model(provider: ProviderName) -> str:
    return {
        "nim": NIM_DEFAULT_MODEL,
        "mistral": MISTRAL_DEFAULT_MODEL,
        "gemini": GEMINI_DEFAULT_MODEL,
    }[provider]


def configured_providers() -> list[ProviderName]:
    return [name for name in PROVIDERS if provider_api_key(name)]


def _efforts_by_family(table: dict[str, tuple[str, ...]], model_id: str) -> tuple[str, ...]:
    """Longest-prefix lookup shared by the per-provider effort tables."""
    lowered = model_id.lower()
    for family in sorted(table, key=len, reverse=True):
        if lowered == family or lowered.startswith((family + "-", family + ".")):
            return table[family]
    return ()


def mistral_reasoning_efforts(model_id: str) -> tuple[str, ...]:
    """Documented ``reasoning_effort`` values for a Mistral model id; empty if the model rejects it."""
    lowered = model_id.lower()
    if lowered.startswith("ft:"):
        # Fine-tuned ids embed their base model: ft:<base>:<org>:<date>:<hash>.
        parts = lowered.split(":")
        lowered = parts[1] if len(parts) > 1 else lowered
    if lowered in MISTRAL_REASONING_EFFORTS_EXCLUDED:
        return ()
    return _efforts_by_family(MISTRAL_REASONING_EFFORTS_BY_MODEL, lowered)


def gemini_thinking_levels(model_id: str) -> tuple[str, ...]:
    """Documented ``thinking_level`` values for a Gemini model id; empty if the model has no thinking control."""
    lowered = model_id.lower().removeprefix("models/")
    if lowered.endswith(GEMINI_THINKING_LEVELS_EXCLUDED_SUFFIXES) or "computer-use" in lowered:
        return ()
    return _efforts_by_family(GEMINI_THINKING_LEVELS_BY_MODEL, lowered)


def reasoning_efforts(provider: ProviderName, model_id: str) -> tuple[str, ...]:
    """The model's reasoning levels, lowest first, as ren's ``GET /models/{id}/efforts`` answers them."""
    if provider == "nim":
        return NIM_REASONING_EFFORTS_BY_MODEL.get(model_id, ())
    if provider == "mistral":
        return mistral_reasoning_efforts(model_id)
    return gemini_thinking_levels(model_id)


def build_model(provider: Literal["nim", "mistral"], model_id: str, reasoning_effort: str | None = None) -> Model:
    """Construct the SDK model instance for ``provider``.

    ``context_window_limit`` is the model's real window from its provider's own metadata (``context_windows``)
    when the provider publishes one; otherwise the SDK's own resolution applies.
    """
    from context_windows import provider_context_window

    model = _provider_model(provider, model_id, reasoning_effort)
    window = provider_context_window(provider, model_id)
    if window:
        model.update_config(context_window_limit=window)
    return model


def _provider_model(provider: Literal["nim", "mistral"], model_id: str, reasoning_effort: str | None) -> Model:
    if provider == "nim":
        if not provider_api_key("nim"):
            raise ValueError("NVIDIA_NIM_API_KEY is not configured.")
        from strands.models.openai import OpenAIModel

        supported = NIM_REASONING_EFFORTS_BY_MODEL.get(model_id, ())
        if reasoning_effort and reasoning_effort not in supported:
            raise ValueError(f"{model_id} does not accept reasoning_effort={reasoning_effort!r}; "
                             + (f"supported: {', '.join(supported)}" if supported else "no efforts are documented"))
        # The SDK's OpenAI chat-completions model against NIM's OpenAI-compatible endpoint.
        return OpenAIModel(
            client_args={"api_key": provider_api_key("nim"), "base_url": NVIDIA_NIM_BASE_URL},
            model_id=model_id,
            params={"reasoning_effort": reasoning_effort} if reasoning_effort else None,
        )

    if not provider_api_key("mistral"):
        raise ValueError("MISTRAL_API_KEY is not configured.")
    from mistral_model import RonMistralModel

    if reasoning_effort:
        supported = mistral_reasoning_efforts(model_id)
        if reasoning_effort not in supported:
            raise ValueError(
                f"{model_id} does not accept reasoning_effort={reasoning_effort!r}; "
                + (f"supported: {', '.join(supported)}" if supported else "this model rejects the parameter")
            )
    return RonMistralModel(
        api_key=provider_api_key("mistral"),
        model_id=model_id,
        reasoning_effort=reasoning_effort or None,
    )


def _strip_model_prefix(name: str) -> str:
    return name.removeprefix("models/") if name.startswith("models/") else name


def list_provider_models(provider: ProviderName) -> list[dict[str, str]]:
    """Live catalogue for one provider. Raises on hard failures; callers log and skip."""
    if provider == "nim":
        if not provider_api_key("nim"):
            return []
        from openai import OpenAI

        with OpenAI(api_key=provider_api_key("nim"), base_url=NVIDIA_NIM_BASE_URL) as client:
            return [{"id": item.id, "owned_by": getattr(item, "owned_by", None) or "nvidia", "provider": "nim"}
                    for item in client.models.list()]

    if provider == "gemini":
        if not provider_api_key("gemini"):
            return []
        from google import genai

        google = genai.Client(api_key=provider_api_key("gemini"))
        models = []
        for item in google.models.list():
            model_id = _strip_model_prefix(getattr(item, "name", "") or "")
            actions = getattr(item, "supported_actions", None) or []
            if not model_id.startswith(("gemini", "gemma")):
                continue
            if actions and "generateContent" not in actions:
                continue
            models.append({"id": model_id, "owned_by": "google", "provider": "gemini"})
        return models or [
            {"id": model_id, "owned_by": "google", "provider": "gemini"}
            for model_id in GEMINI_FALLBACK_MODELS
        ]

    if not provider_api_key("mistral"):
        return []
    from mistralai.client import Mistral

    mistral = Mistral(api_key=provider_api_key("mistral"))
    payload = mistral.models.list()
    items = getattr(payload, "data", None) or []
    mistral_models = []
    for item in items:
        mistral_id = getattr(item, "id", None) or getattr(item, "name", None)
        if not mistral_id:
            continue
        owned_by = getattr(item, "owned_by", None) or "mistral"
        mistral_models.append({"id": str(mistral_id), "owned_by": str(owned_by), "provider": "mistral"})
    return mistral_models or [
        {"id": model_id, "owned_by": "mistral", "provider": "mistral"}
        for model_id in MISTRAL_FALLBACK_MODELS
    ]
