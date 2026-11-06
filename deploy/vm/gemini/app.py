"""Emrys's Gemini agents for the phone, in Strands: the harness for text and a BidiAgent for voice, behind FastAPI
WebSockets.

Text: the phone opens `/gemini/text` with the conversation's id, the text model, `user_id` and the eight `perm_*` as
query parameters, then sends each message as text. Every message runs the harness's `stream_async`; the phone gets the text as
it streams (`{"data": …}`), each finished message (`{"message": …}`), and how the turn ended (`{"stop_reason": …,
"interrupts": […]}`). The reply after a `handoff_to_user` goes back as its `interruptResponse`.

Voice: the phone opens `/gemini/voice` with the same parameters and streams its microphone as 16 kHz PCM frames; it
gets Strands' bidi events as they are (`bidi_audio_delta` at 24 kHz, transcripts, `bidi_barge_in`, tool uses). The
multi-agent tools and `web_fetch` run their children on the text model, since the Live model only talks.

The instructions are the ElevenLabs agents' own prompts, read when the conversation opens and filled in the way
ElevenLabs fills its dynamic variables.
"""

import os
import re
from collections.abc import Mapping
from typing import Literal

import uvicorn
from elevenlabs import AsyncElevenLabs
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic_core import to_jsonable_python
from strands import tool
from strands.bidi import BidiAgent
from strands.bidi.types.io import InputStream, OutputStream
from strands.bidi.models.google import GoogleGeminiLiveModel
from strands.bidi.types.agent import BidiAgentInput
from strands.bidi.types.events import BidiOutputEvent
from strands.bidi.types.media import AudioDelta
from strands.interrupt import Interrupt
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
    make_graph,
    make_mcp_router,
    make_subagent,
    make_swarm,
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
from strands_harness import create_harness
from strands_harness.tools.programmatic_tool_caller import programmatic_tool_caller

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
TextModel = Literal["gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite"]
HOME = "/config/emrys-gemini"

# The ElevenLabs agents' MCP servers plus the phone's own, unprefixed so the tool names match the prompt.
SERVERS: dict[str, MCPServerConfig] = {
    "phone": {
        "url": "${PHONE_MCP_URL}",
        "headers": {"Authorization": "Bearer ${PHONE_MCP_TOKEN}"},
        "prefix": "",
    },
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


@app.websocket("/gemini/voice")
async def voice(websocket: WebSocket, model: TextModel) -> None:
    await websocket.accept()
    helper = GeminiModel(model_id=model)
    agent = BidiAgent(
        model=GoogleGeminiLiveModel(model_id=VOICE_MODEL, voice=VOICE, params={"enable_affective_dialog": True}),
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
            make_subagent(model=Fixed(helper)),
            make_swarm(model=Fixed(helper)),
            make_graph(model=Fixed(helper)),
            make_mcp_router(servers=SERVERS),
            end_conversation,
        ],
    )
    try:
        await agent.run(inputs=[PhoneAudio(websocket)], outputs=[Phone(websocket)])
    except WebSocketDisconnect:
        pass


@app.websocket("/gemini/text")
async def text(websocket: WebSocket, conversation: str, model: TextModel) -> None:
    await websocket.accept()
    agent = create_harness(
        model=f"google/{model}",
        instructions=await instructions(TEXT_AGENT, variables(websocket, text_only=True)),
        tools=[python_repl, http_request, notebook, sleep, stop, handoff_to_user, swarm, graph, make_mcp_router(servers=SERVERS)],
        mcp_servers=SERVERS,
        session={"id": conversation, "dir": f"{HOME}/sessions"},
        memory={"dir": f"{HOME}/memory"},
    )
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
                async for event in agent.stream_async(prompt):
                    if "data" in event:
                        await websocket.send_json({"data": event["data"]})
                    elif "message" in event:
                        await websocket.send_json({"message": to_jsonable_python(event["message"], bytes_mode="base64")})
                    elif "result" in event:
                        pending = list(event["result"].interrupts or [])
                        await websocket.send_json(
                            {"stop_reason": event["result"].stop_reason, "interrupts": [str(interrupt.reason) for interrupt in pending]}
                        )
    except WebSocketDisconnect:
        pass


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8100)
