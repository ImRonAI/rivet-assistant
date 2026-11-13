"""Emrys's Strands agents for the phone: the harness for text and a BidiAgent for voice, behind FastAPI WebSockets.

The text agent runs on Gemini, NVIDIA NIM, Mistral, Telnyx or xAI, the phone's choice in Settings › Agents (`provider`,
`model`, the provider's default when left out, and `effort`, the reasoning level or `auto`). NIM and Mistral models are
built the way ren builds them, as is xAI's on ren's own `RonXAIModel`, and Telnyx's the way ren builds NIM's, on Telnyx's
OpenAI-compatible API (`providers.py`);
`GET /gemini/models` and `GET /gemini/efforts` list each provider's live models and each model's reasoning levels.
Voice is always Gemini Live; its helpers run on the chosen text model.

Text: the phone opens `/gemini/text` with the conversation's id, the provider, text model and effort, `user_id` and the
eight `perm_*` as query parameters, then sends each message as text. Every message runs the harness's `stream_async`; the phone gets the
model's reasoning as it streams (`{"reasoning": …}`, Gemini's thoughts included), the text as it streams (`{"data": …}`), each finished message (`{"message": …}`), and how the turn ended (`{"stop_reason": …,
"interrupts": […]}`). The reply after a `handoff_to_user` goes back as its `interruptResponse`.

Voice: the phone opens `/gemini/voice` with the same parameters and streams its microphone as 16 kHz PCM frames; it
gets Strands' bidi events as they are (`bidi_audio_delta` at 24 kHz, transcripts, `bidi_barge_in`, tool uses). The
subagent tool and `web_fetch` run their children on the text model, since the Live model only talks.

The instructions are the ElevenLabs agents' own prompts, read when the conversation opens and filled in the way
ElevenLabs fills its dynamic variables.
"""

import asyncio
import os
import re
from collections.abc import Mapping

import uvicorn
from elevenlabs import AsyncElevenLabs
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic_core import to_jsonable_python
from strands import tool
from strands.bidi import BidiAgent
from strands.bidi.types.io import InputStream, OutputStream
from strands.bidi.types.agent import BidiAgentInput
from strands.bidi.types.events import BidiOutputEvent
from strands.bidi.types.media import AudioDelta
from strands.interrupt import Interrupt
from strands.models import Model
from strands.models.gemini import GeminiModel
from strands.multiagent.spec import Fixed
from strands.tools.mcp import MCPClient, MCPServerConfig
from strands.types.agent import AgentInput
from strands.types.interrupt import InterruptResponse, InterruptResponseContent
from strands.types.tools import ToolContext
from strands.vended_tools import (
    edit,
    graph,
    handoff_to_user,
    http_request,
    make_mcp_router,
    make_subagent,
    make_web_fetch,
    notebook,
    python_repl,
    read,
    shell,
    sleep,
    stop,
    swarm,
    write,
)
from strands_harness import BuiltinToolsConfig, Effort, create_harness
from strands_harness.models import EFFORT_LEVELS
from strands_harness.tools.programmatic_tool_caller import programmatic_tool_caller

from agent.api.core.brave_tools import brave_tools
from agent.api.core.xai_model import XAIStatePruner

from live_model import RenGeminiLiveModel
from providers import ProviderName, build_model, list_provider_models, provider_default_model, reasoning_efforts

VOICE_AGENT = "agent_2901m620nzwrev2r7ekqpydcxe25"
TEXT_AGENT = "agent_9601m623qa72ffs9cyy3ezc0p6tb"
VOICE_MODEL = "gemini-3.8-live"
# Google's prebuilt voice described as "Smooth", like the ElevenLabs voice agent's "Eric - Smooth, Trustworthy".
VOICE = "Algieba"
PERMISSIONS = (
    "perm_use_apps",
    "perm_send_messages",
    "perm_read_messages",
    "perm_purchases",
    "perm_install_apps",
    "perm_cloud_coding",
    "perm_deploy",
    "perm_create_music",
)
HOME = "/config/emrys-gemini"
# Every agent here searches the web with ren desktop's Brave tools (`brave_tools()`: brave_answer, brave_web_search,
# brave_news_search, brave_image_search, brave_video_search, brave_local_search, brave_rich_answer), whatever its model.
# The harness's built-ins for Gemini. Its native web search is Google Search grounding, which a Gemini API key on the
# free tier is refused (429 on every request that carries it); `GEMINI_WEB_SEARCH=0` leaves it out for such a key.
GEMINI_BUILTINS: BuiltinToolsConfig = {"web_search": os.getenv("GEMINI_WEB_SEARCH", "1") != "0"}

# The ElevenLabs agents' MCP servers plus Ren's hub, unprefixed so the tool names match the prompt. The hub serves
# the phone's tools under their own names and ren desktop's under `desktop_`, whichever of the two is attached.
SERVERS: dict[str, MCPServerConfig] = {
    "ren-hub": {"url": "http://hub:8300/mcp", "prefix": ""},
    "emrys-computer": {"url": "http://computer:8080/computer/mcp", "prefix": ""},
    "oubliai-telnyx": {
        "url": "https://oubli-catolabs.fastmcp.app/mcp",
        "headers": {"Authorization": "${TELNYX_MCP_AUTHORIZATION}"},
        "prefix": "",
    },
}

elevenlabs = AsyncElevenLabs(api_key=os.environ["ELEVENLABS_API_KEY"])
app = FastAPI()


async def instructions(agent_id: str, values: Mapping[str, str]) -> str:
    """The agent's prompt from ElevenLabs with its `{{variables}}` filled in."""
    config = (await elevenlabs.conversational_ai.agents.get(agent_id)).conversation_config.agent
    if config is None or config.prompt is None or config.prompt.prompt is None:
        raise RuntimeError(f"{agent_id} has no prompt")
    return re.sub(r"\{\{(\w+)\}\}", lambda found: values.get(found.group(1), found.group(0)), config.prompt.prompt)


def variables(websocket: WebSocket, text_only: bool) -> dict[str, str]:
    """The dynamic variables the prompt names, as ElevenLabs sets them for a conversation in the Emrys app."""
    query = websocket.query_params
    return {
        "system__channel": "unknown",
        "system__is_text_only": "true" if text_only else "false",
        "user_id": query.get("user_id", ""),
        **{name: query.get(name, "prompt") for name in PERMISSIONS},
    }


@tool(context=True)
def end_conversation(tool_context: ToolContext[BidiAgent]) -> str:
    """End the conversation when the user asks to stop or says goodbye."""
    tool_context.agent.cancel()
    return "Ending conversation."


class PhoneAudio(InputStream):
    """The phone's microphone, one WebSocket frame of 16 kHz PCM at a time."""

    def __init__(self, websocket: WebSocket) -> None:
        self.websocket = websocket

    async def __call__(self) -> BidiAgentInput:
        return AudioDelta(format="pcm", source={"bytes": await self.websocket.receive_bytes()})


class Phone(OutputStream):
    """Every bidi event to the phone, as JSON."""

    def __init__(self, websocket: WebSocket) -> None:
        self.websocket = websocket

    async def __call__(self, event: BidiOutputEvent) -> None:
        await self.websocket.send_json(to_jsonable_python(event, fallback=str))


def text_model(provider: ProviderName, model: str, effort: str) -> Model:
    """The text model the phone chose: Gemini as Strands builds it, NIM, Mistral, Telnyx and xAI as `providers.py` does."""
    if provider == "gemini":
        return GeminiModel(model_id=model)
    return build_model(provider, model, None if effort == "auto" else effort)


def harness_effort(effort: str) -> Effort:
    """The phone's reasoning level as the harness names it; it checks the level against the Gemini model itself."""
    for level in EFFORT_LEVELS:
        if level == effort:
            return level
    raise ValueError(f"Unknown effort {effort!r}")


@app.get("/gemini/models")
async def models(provider: ProviderName) -> dict[str, object]:
    """The provider's live model catalogue and its default model, as ren's `GET /models` lists them."""
    return {"models": await asyncio.to_thread(list_provider_models, provider), "default": provider_default_model(provider)}


@app.get("/gemini/efforts")
async def efforts(provider: ProviderName, model: str) -> dict[str, list[str]]:
    """The model's reasoning levels, lowest first, as ren's `GET /models/{id}/efforts` answers them."""
    return {"efforts": list(reasoning_efforts(provider, model))}


@app.websocket("/gemini/voice")
async def voice(
    websocket: WebSocket, provider: ProviderName = "gemini", model: str | None = None, effort: str = "auto"
) -> None:
    await websocket.accept()
    helper = text_model(provider, model or provider_default_model(provider), effort)
    agent = BidiAgent(
        model=RenGeminiLiveModel(model_id=VOICE_MODEL, voice=VOICE),
        system_prompt=await instructions(VOICE_AGENT, variables(websocket, text_only=False)),
        tools=[
            *MCPClient.load_servers(SERVERS, continue_on_error=True),
            programmatic_tool_caller,
            shell,
            read,
            write,
            edit,
            make_web_fetch(model=helper),
            python_repl,
            http_request,
            notebook,
            sleep,
            # swarm and graph stay out: Strands 1.64.0 sends their schemas to Gemini Live as `additional_properties`,
            # which the Live API refuses with 1007.
            make_subagent(model=Fixed(helper)),
            make_mcp_router(servers=SERVERS),
            *brave_tools(),
            end_conversation,
        ],
    )
    try:
        await agent.run(inputs=[PhoneAudio(websocket)], outputs=[Phone(websocket)])
    except WebSocketDisconnect:
        pass


@app.websocket("/gemini/text")
async def text(
    websocket: WebSocket, conversation: str, provider: ProviderName = "gemini", model: str | None = None, effort: str = "auto"
) -> None:
    await websocket.accept()
    chosen = model or provider_default_model(provider)
    agent = create_harness(
        model=f"google/{chosen}" if provider == "gemini" else text_model(provider, chosen, effort),
        effort=harness_effort(effort) if provider == "gemini" else "auto",
        instructions=await instructions(TEXT_AGENT, variables(websocket, text_only=True)),
        tools=[python_repl, http_request, notebook, sleep, stop, handoff_to_user, swarm, graph, make_mcp_router(servers=SERVERS), *brave_tools()],
        builtin_tools=GEMINI_BUILTINS if provider == "gemini" else None,
        mcp_servers=SERVERS,
        session={"id": conversation, "dir": f"{HOME}/sessions"},
        memory={"dir": f"{HOME}/memory"},
    )
    if isinstance(agent.model, GeminiModel):
        # Gemini thinks either way but sends its thoughts only when asked to, which the harness doesn't ask.
        config = agent.model.get_config()
        params = config.get("params", {})
        thinking = params.get("thinking_config", {})
        agent.model.update_config(model_id=config["model_id"], params={**params, "thinking_config": {**thinking, "include_thoughts": True}})
    if provider == "xai":
        # As ren adds it: strands_xai appends the whole encrypted protobuf history to every assistant turn and
        # only ever reads the newest copy; keep one so history/snapshots/token estimates stay linear.
        agent.hooks.add_hook(XAIStatePruner())
    pending: list[Interrupt] = []
    try:
        async with agent:
            while True:
                said = await websocket.receive_text()
                prompt: AgentInput = (
                    [InterruptResponseContent(interruptResponse=InterruptResponse(interruptId=interrupt.id, response=said)) for interrupt in pending]
                    if pending
                    else said
                )
                try:
                    async for event in agent.stream_async(prompt):
                        if "reasoningText" in event:
                            await websocket.send_json({"reasoning": event["reasoningText"]})
                        elif "data" in event:
                            await websocket.send_json({"data": event["data"]})
                        elif "message" in event:
                            await websocket.send_json({"message": to_jsonable_python(event["message"], bytes_mode="base64")})
                        elif "result" in event:
                            pending = list(event["result"].interrupts or [])
                            await websocket.send_json(
                                {"stop_reason": event["result"].stop_reason, "interrupts": [str(interrupt.reason) for interrupt in pending]}
                            )
                except WebSocketDisconnect:
                    raise
                except Exception as error:
                    # A turn the provider failed (no quota, a model it has retired) is told to the phone as it is,
                    # and the conversation stays open for the next message.
                    await websocket.send_json({"error": f"{type(error).__name__}: {error}"})
    except WebSocketDisconnect:
        pass


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8100)
