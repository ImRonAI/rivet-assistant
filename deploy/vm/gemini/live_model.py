"""Gemini Live's tool declarations as ren desktop writes them.

Strands 1.64.0's ``GoogleGeminiLiveModel`` validates each tool's input schema as a genai ``Schema``, which has no place
for JSON Schema keywords such as ``$schema`` or ``additionalProperties``, so one MCP tool that carries them (Desktop
Commander's do) stops the voice agent before it connects. ren desktop's Strands patch (``deploy/strands-bidi.patch`` in
``ImRonAI/TheBrowser`` at ``b112adf``) passes the schema as JSON Schema instead. The two methods here are that patch's
``_format_tools_for_live_api`` and ``_parameters_json_schema``; the rest of the patch does not apply to Strands 1.64.0.
"""

from __future__ import annotations

from google.genai import types as genai_types
from strands.bidi.models.google import GoogleGeminiLiveModel
from strands.types.tools import ToolSpec

#: Only models whose id contains this get ``NON_BLOCKING`` declarations (ren: Gemini 3.8 Live).
_NON_BLOCKING_MODEL_MARKER = "gemini-3.8-live"
_EMPTY_OBJECT_SCHEMA_KEYS = frozenset({"type", "properties", "required", "title", "description"})


class RenGeminiLiveModel(GoogleGeminiLiveModel):
    """``GoogleGeminiLiveModel`` with ren desktop's tool declarations."""

    def _format_tools_for_live_api(self, tool_specs: list[ToolSpec]) -> list[genai_types.Tool]:
        """Format tool specs for Gemini Live API.

        Input schemas are passed as JSON Schema so constraints such as ``additionalProperties``
        reach the model intact. A parameterless tool is declared without a schema, matching the
        provider SDK's own declarations: an empty object schema measurably suppresses calls to the
        tool on Gemini 3.8 Live. Gemini 3.8 Live models get an explicit ``NON_BLOCKING`` behavior on
        every declaration; other models keep the provider default.
        """
        if not tool_specs:
            return []

        behavior = genai_types.Behavior.NON_BLOCKING if self._requires_non_blocking_tools() else None
        return [
            genai_types.Tool(
                function_declarations=[
                    genai_types.FunctionDeclaration(
                        description=tool_spec["description"],
                        name=tool_spec["name"],
                        parameters_json_schema=self._parameters_json_schema(tool_spec),
                        behavior=behavior,
                    )
                    for tool_spec in tool_specs
                ],
            ),
        ]

    @staticmethod
    def _parameters_json_schema(tool_spec: ToolSpec) -> dict[str, object] | None:
        """Return the tool's JSON Schema, or ``None`` for a tool that declares no parameters.

        Only a bare empty object schema is omitted; a schema that constrains its input in any
        other way (``additionalProperties``, ``$ref``, ``oneOf``, ...) is forwarded unchanged.
        """
        schema: dict[str, object] = tool_spec["inputSchema"]["json"]
        if (
            set(schema) <= _EMPTY_OBJECT_SCHEMA_KEYS
            and schema.get("type") == "object"
            and not schema.get("properties")
            and not schema.get("required")
        ):
            return None
        return schema

    def _requires_non_blocking_tools(self) -> bool:
        """Whether the configured model requires explicit non-blocking function declarations."""
        return _NON_BLOCKING_MODEL_MARKER in self._config["model_id"]
