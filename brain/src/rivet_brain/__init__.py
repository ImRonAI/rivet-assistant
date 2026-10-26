"""Rivet's brain: a Strands harness agent on Perplexity's Agent API, wired to Telnyx MCP.

The phone app is a client of this service. Every agent is built with ``strands_harness.create_harness``
and run by a :class:`~rivet_brain.supervisor.SessionSupervisor`, which keeps one logical session alive
across hangs, context overflow, provider outages, MCP drops, and process restarts.
"""

from rivet_brain.config import BrainSettings
from rivet_brain.models.perplexity import (
    PerplexityAgentModel,
    PerplexityTransientError,
    build_perplexity_model,
)

__all__ = [
    "BrainSettings",
    "PerplexityAgentModel",
    "PerplexityTransientError",
    "build_perplexity_model",
]
