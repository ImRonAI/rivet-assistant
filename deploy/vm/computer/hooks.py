"""The agents' webhooks at https://hooks.oubliai.com: tasks that keep running after the owner hangs up or the phone dies.

`/api/task` is the agents' async webhook tool. `/api/post-call` takes ElevenLabs' post-call webhook, which arrives however
the conversation ended, and starts the task the `background_task` data collection item found when the agent didn't start
one during the conversation. OpenCode does the work in /config/workspace/jobs/<conversation>/<n>, then the voice agent
calls the owner with that folder as `job_dir`.
"""

import asyncio
import hmac
import os
from pathlib import Path

import uvicorn
from elevenlabs import AsyncElevenLabs
from elevenlabs.errors import BadRequestError
from elevenlabs.types import ConversationInitiationClientDataRequestInput
from pydantic import BaseModel, ValidationError
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

VOICE_AGENT = "agent_2901m620nzwrev2r7ekqpydcxe25"
TEXT_AGENT = "agent_9601m623qa72ffs9cyy3ezc0p6tb"
PHONE_NUMBER = "phnum_5501m6ademjmf39b1646vkbwyg4k"  # +17608769757 on the Telnyx SIP trunk
OPENCODE = "/config/.opencode/bin/opencode"
JOBS = Path("/config/workspace/jobs")

elevenlabs = AsyncElevenLabs(api_key=os.environ["ELEVENLABS_API_KEY"])
running: set[asyncio.Task[None]] = set()

Value = str | int | float | bool | None


class TaskRequest(BaseModel):
    task: str
    conversation_id: str


class DataCollectionResult(BaseModel):
    value: Value = None


class Analysis(BaseModel):
    data_collection_results: dict[str, DataCollectionResult] | None = None


class ClientData(BaseModel):
    dynamic_variables: dict[str, Value] | None = None


class Conversation(BaseModel):
    agent_id: str
    conversation_id: str
    analysis: Analysis | None = None
    conversation_initiation_client_data: ClientData | None = None


class PostCallEvent(BaseModel):
    type: str
    data: Conversation


async def run(job: Path) -> None:
    with (job / "opencode.log").open("ab") as log:
        # A private server, so the job gets this environment rather than a shared service started in another container.
        opencode = await asyncio.create_subprocess_exec(
            OPENCODE, "run", "--standalone", "--auto", (job / "TASK.md").read_text(),
            cwd=job, stdin=asyncio.subprocess.DEVNULL, stdout=log, stderr=asyncio.subprocess.STDOUT,
            # What Desktop Commander gives OpenCode; the ElevenLabs key and the webhook secrets stay in this process.
            env={name: os.environ[name] for name in ("HOME", "PATH", "MISTRAL_API_KEY", "npm_config_cache")},
        )
        log.write(f"\nopencode exited with code {await opencode.wait()}\n".encode())
    await elevenlabs.conversational_ai.sip_trunk.outbound_call(
        agent_id=VOICE_AGENT,
        agent_phone_number_id=PHONE_NUMBER,
        to_number=os.environ["EMRYS_OWNER_NUMBER"],
        conversation_initiation_client_data=ConversationInitiationClientDataRequestInput(
            dynamic_variables={"job_dir": str(job)},
        ),
    )


def start(conversation: str, task: str) -> Path:
    jobs = JOBS / conversation
    jobs.mkdir(parents=True, exist_ok=True)
    job = jobs / str(len(list(jobs.iterdir())) + 1)
    job.mkdir()
    (job / "TASK.md").write_text(task)
    started = asyncio.create_task(run(job))
    running.add(started)
    started.add_done_callback(running.discard)
    return job


async def start_task(request: Request) -> Response:
    expected = f"Bearer {os.environ['EMRYS_HOOKS_TOKEN']}"
    if not hmac.compare_digest(request.headers.get("authorization", ""), expected):
        return Response(status_code=401)
    try:
        body = TaskRequest.model_validate_json(await request.body())
    except ValidationError as error:
        return JSONResponse({"error": error.errors(include_url=False)}, status_code=422)
    job = start(body.conversation_id, body.task)
    return JSONResponse({"status": "started", "job_dir": str(job)})


async def post_call(request: Request) -> Response:
    body = (await request.body()).decode()
    try:
        elevenlabs.webhooks.construct_event(
            rawBody=body,
            sig_header=request.headers.get("elevenlabs-signature", ""),
            secret=os.environ["ELEVENLABS_WEBHOOK_SECRET"],
        )
    except BadRequestError:
        return Response(status_code=401)
    event = PostCallEvent.model_validate_json(body)
    conversation = event.data
    client_data = conversation.conversation_initiation_client_data
    variables = (client_data.dynamic_variables if client_data else None) or {}
    results = (conversation.analysis.data_collection_results if conversation.analysis else None) or {}
    found = results.get("background_task")
    if (
        event.type != "post_call_transcription"
        or conversation.agent_id not in (VOICE_AGENT, TEXT_AGENT)
        # A callback reporting a job never starts one, so a misread report can't call the owner in a loop.
        or variables.get("job_dir", "none") != "none"
        # The agent started it during the conversation, or this is a retried delivery.
        or (JOBS / conversation.conversation_id).exists()
        or found is None
        or not isinstance(found.value, str)
        or not found.value.strip()
    ):
        return Response()
    start(conversation.conversation_id, found.value)
    return Response()


app = Starlette(
    routes=[
        Route("/api/task", start_task, methods=["POST"]),
        Route("/api/post-call", post_call, methods=["POST"]),
    ]
)

uvicorn.run(app, host="0.0.0.0", port=8090)
