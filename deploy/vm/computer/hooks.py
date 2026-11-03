"""The agents' webhooks at https://hooks.oubliai.com: tasks that keep running after the owner hangs up or the phone dies.

`/api/task` is the agents' async `start_background_task` tool. `/api/post-call` takes ElevenLabs' post-call webhook, which
arrives however a conversation ended; from the voice and text agents it starts the task the `background_task` data
collection item found when the agent didn't start one during the conversation. Each task is a text conversation with the
Task agent, recorded in /config/workspace/jobs/<conversation>/<n>. The Task agent's own post-call webhook carries its
report, and once the conversation the task came from has ended, the voice agent calls the owner with it.
"""

import asyncio
import hmac
import logging
import os
from collections.abc import Coroutine, Mapping
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

import httpx
import uvicorn
from elevenlabs import AsyncElevenLabs
from elevenlabs.conversational_ai.conversations.client import AsyncConversationsClient
from elevenlabs.conversational_ai.sip_trunk.client import AsyncSipTrunkClient
from elevenlabs.core.api_error import ApiError
from elevenlabs.errors import BadRequestError
from elevenlabs.types import (
    AgentConfigOverrideInput,
    ConversationConfigClientOverrideInput,
    ConversationInitiationClientDataRequestInput,
)
from pydantic import BaseModel, TypeAdapter, ValidationError
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import Response
from starlette.routing import Route
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosedError

VOICE_AGENT = "agent_2901m620nzwrev2r7ekqpydcxe25"
TEXT_AGENT = "agent_9601m623qa72ffs9cyy3ezc0p6tb"
TASK_AGENT = "agent_4901m6gdwby2fetbqddcpfkevvpp"
CALLBACK_PROCEDURE = "agtprc_2801m6ge2jbzebp8126nvrbc7520"  # the voice agent's "Report background task results"
PHONE_NUMBER = "phnum_5501m6ademjmf39b1646vkbwyg4k"  # +17608769757 on the Telnyx SIP trunk
JOBS = Path("/config/workspace/jobs")
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
# Both agents end a conversation after 600 seconds, so a finished task never waits longer for the one it came from.
SOURCE_LIMIT = 600
RETRY_AFTER = 60

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("hooks")
elevenlabs = AsyncElevenLabs(api_key=os.environ["ELEVENLABS_API_KEY"])
# The SDK's lazy client properties have no annotations; these name what they return, so the calls through them are checked.
conversations: AsyncConversationsClient = elevenlabs.conversational_ai.conversations
sip_trunk: AsyncSipTrunkClient = elevenlabs.conversational_ai.sip_trunk
running: set[asyncio.Task[None]] = set()
# Set when the conversation of that id ends, for the tasks it started.
endings: dict[str, asyncio.Event] = {}

Value = str | int | float | bool | None
Variables = TypeAdapter(dict[str, Value])
Outcome = Literal["ready", "not_ready", "needs_owner"]
Outcomes = TypeAdapter(Outcome)


class TaskRequest(BaseModel):
    title: str = ""
    task: str
    done_when: str = ""
    conversation_id: str


class Started(BaseModel):
    status: Literal["started"] = "started"
    job_id: str


class Report(BaseModel):
    outcome: Outcome
    result: str
    where: str
    checked: str
    blocker: str


class Call(BaseModel):
    at: datetime
    success: bool
    message: str
    conversation_id: str | None


class Job(BaseModel):
    """A task's record, `job.json` in its folder."""

    id: str
    source: str
    title: str
    task: str
    done_when: str
    permissions: dict[str, str]
    session: str | None = None
    ended: str | None = None
    report: Report | None = None
    # What was said in the conversation the task came from, once that conversation has ended.
    source_summary: str | None = None
    calls: list[Call] = []


class DataCollectionResult(BaseModel):
    value: Value = None


class Analysis(BaseModel):
    data_collection_results: dict[str, DataCollectionResult] | None = None
    transcript_summary: str | None = None


class Metadata(BaseModel):
    termination_reason: str | None = None


class ClientData(BaseModel):
    dynamic_variables: dict[str, Value] | None = None


class Conversation(BaseModel):
    agent_id: str
    conversation_id: str
    analysis: Analysis | None = None
    metadata: Metadata | None = None
    conversation_initiation_client_data: ClientData | None = None

    def variables(self) -> dict[str, Value]:
        client_data = self.conversation_initiation_client_data
        return (client_data.dynamic_variables if client_data else None) or {}

    def collected(self, name: str) -> str:
        results = (self.analysis.data_collection_results if self.analysis else None) or {}
        found = results.get(name)
        return found.value.strip() if found and isinstance(found.value, str) else ""

    def summary(self) -> str:
        return (self.analysis.transcript_summary if self.analysis else None) or ""

    def report(self) -> Report:
        """The Task agent's report. A session that ended without one isn't ready, and says how it ended."""
        try:
            outcome, blocker = Outcomes.validate_python(self.collected("outcome")), self.collected("blocker")
        except ValidationError:
            reason = (self.metadata.termination_reason if self.metadata else None) or "no reason given"
            outcome, blocker = "not_ready", f"The Task agent stopped without reporting back: {reason}"
        return Report(
            outcome=outcome,
            result=self.collected("result") or self.summary(),
            where=self.collected("where"),
            checked=self.collected("checked"),
            blocker=blocker,
        )


class PostCallEvent(BaseModel):
    type: str
    data: Conversation


class InitiationMetadata(BaseModel):
    conversation_id: str


class Ping(BaseModel):
    event_id: int


class AgentResponse(BaseModel):
    agent_response: str


class ServerEvent(BaseModel):
    """A message from the conversation WebSocket; the task needs only these events and ignores the rest."""

    type: str
    conversation_initiation_metadata_event: InitiationMetadata | None = None
    ping_event: Ping | None = None
    agent_response_event: AgentResponse | None = None


class Initiation(BaseModel):
    type: Literal["conversation_initiation_client_data"] = "conversation_initiation_client_data"
    dynamic_variables: dict[str, str]


class UserMessage(BaseModel):
    type: Literal["user_message"] = "user_message"
    text: str


class Pong(BaseModel):
    type: Literal["pong"] = "pong"
    event_id: int


def load(job_id: str) -> Job:
    return Job.model_validate_json((JOBS / job_id / "job.json").read_text())


def save(job: Job) -> None:
    (JOBS / job.id / "job.json").write_text(job.model_dump_json(indent=2))


def by_session(conversation: str) -> Job | None:
    """The job whose Task agent conversation this is."""
    jobs = (Job.model_validate_json(path.read_text()) for path in JOBS.glob("*/*/job.json"))
    return next((job for job in jobs if job.session == conversation), None)


def permissions(variables: Mapping[str, Value]) -> dict[str, str]:
    """The owner's permissions as the conversation had them. One that's missing asks first."""
    return {name: value if isinstance(value := variables.get(name), str) else "prompt" for name in PERMISSIONS}


def start(work: Coroutine[None, None, None]) -> None:
    started = asyncio.create_task(work)
    running.add(started)
    started.add_done_callback(running.discard)


def ending(conversation: str) -> asyncio.Event:
    return endings.setdefault(conversation, asyncio.Event())


def begin(source: str, title: str, task: str, done_when: str, allowed: dict[str, str], summary: str | None) -> Job:
    jobs = JOBS / source
    jobs.mkdir(parents=True, exist_ok=True)
    job = Job(
        id=f"{source}/{len(list(jobs.iterdir())) + 1}",
        source=source,
        title=title,
        task=task,
        done_when=done_when,
        permissions=allowed,
        source_summary=summary,
    )
    (JOBS / job.id).mkdir()
    save(job)
    log.info("job %s started: %s", job.id, title or task[:80])
    start(work(job.id))
    return job


async def converse(job: Job) -> None:
    """The task as a text conversation with the Task agent, until the agent ends it."""
    brief = "\n".join(
        f"{label}: {text}" for label, text in (("Title", job.title), ("Task", job.task), ("Done when", job.done_when)) if text
    )
    signed = await conversations.get_signed_url(agent_id=TASK_AGENT)
    with (JOBS / job.id / "session.log").open("a") as transcript:
        # No size limit: the agent's events can carry whole tool results.
        async with connect(signed.signed_url, max_size=None) as websocket:
            await websocket.send(Initiation(dynamic_variables=job.permissions).model_dump_json())
            try:
                async for message in websocket:
                    event = ServerEvent.model_validate_json(message)
                    if event.conversation_initiation_metadata_event:
                        current = load(job.id)
                        current.session = event.conversation_initiation_metadata_event.conversation_id
                        save(current)
                        log.info("job %s is conversation %s", job.id, current.session)
                        await websocket.send(UserMessage(text=brief).model_dump_json())
                    elif event.ping_event:
                        await websocket.send(Pong(event_id=event.ping_event.event_id).model_dump_json())
                    elif event.agent_response_event:
                        transcript.write(f"{event.agent_response_event.agent_response}\n\n")
                        transcript.flush()
                ended = f"closed {websocket.close_code} {websocket.close_reason}"
            except ConnectionClosedError as error:
                ended = f"connection lost: {error}"
    current = load(job.id)
    current.ended = ended
    save(current)
    log.info("job %s session %s", job.id, ended)


async def work(job_id: str) -> None:
    job = load(job_id)
    try:
        await converse(job)
    except Exception as error:
        log.exception("job %s session failed", job_id)
        current = load(job_id)
        current.ended = f"failed: {error!r}"
        save(current)
        # A session that started reports through its post-call webhook; one that never started has nobody to report.
        if current.session is None:
            blocker = f"The Task agent couldn't start: {error!r}"
            await finish(job_id, Report(outcome="not_ready", result="", where="", checked="", blocker=blocker))


async def finish(job_id: str, report: Report) -> None:
    job = load(job_id)
    if job.report:
        return  # a retried delivery
    job.report = report
    save(job)
    log.info("job %s finished: %s", job_id, report.outcome)
    if job.source_summary is None:
        try:
            await asyncio.wait_for(ending(job.source).wait(), SOURCE_LIMIT)
        except TimeoutError:
            log.info("job %s calls while its conversation %s may still be on", job_id, job.source)
    await call_back(job_id)


async def call_back(job_id: str) -> None:
    job = load(job_id)
    report = job.report
    if report is None:
        return
    # The names the callback procedure reads.
    variables = {
        "original_request": job.task,
        "source_summary": job.source_summary or "",
        "job_outcome": report.outcome,
        "job_result": report.result,
        "job_where": report.where,
        "job_checked": report.checked,
        "job_blocker": report.blocker,
    }
    for attempt in range(2):
        if attempt:
            await asyncio.sleep(RETRY_AFTER)
        try:
            response = await sip_trunk.outbound_call(
                agent_id=VOICE_AGENT,
                agent_phone_number_id=PHONE_NUMBER,
                to_number=os.environ["EMRYS_OWNER_NUMBER"],
                conversation_initiation_client_data=ConversationInitiationClientDataRequestInput(
                    # No greeting: the agent waits for the owner's hello, then follows the callback procedure.
                    conversation_config_override=ConversationConfigClientOverrideInput(
                        agent=AgentConfigOverrideInput(first_message=""),
                    ),
                    dynamic_variables=variables,
                    start_procedure_ids=[CALLBACK_PROCEDURE],
                ),
            )
            call = Call(
                at=datetime.now(UTC),
                success=response.success,
                message=response.message,
                conversation_id=response.conversation_id,
            )
        except (ApiError, httpx.HTTPError) as error:
            call = Call(at=datetime.now(UTC), success=False, message=str(error), conversation_id=None)
        current = load(job_id)
        current.calls.append(call)
        save(current)
        log.info("job %s callback %s: %s %s", job_id, call.success, call.message, call.conversation_id)
        if call.success:
            return


def conversation_ended(conversation: Conversation) -> None:
    """A voice or text conversation is over: its tasks may call back, and one it didn't start begins now."""
    jobs = JOBS / conversation.conversation_id
    if jobs.is_dir():
        for folder in jobs.iterdir():
            job = load(f"{conversation.conversation_id}/{folder.name}")
            job.source_summary = conversation.summary()
            save(job)
        ending(conversation.conversation_id).set()
        return
    task = conversation.collected("background_task")
    variables = conversation.variables()
    # A callback never starts a task by itself, so a misread report can't call the owner in a loop.
    if task and variables.get("job_outcome", "none") == "none":
        begin(conversation.conversation_id, "", task, "", permissions(variables), conversation.summary())


async def start_task(request: Request) -> Response:
    expected = f"Bearer {os.environ['EMRYS_HOOKS_TOKEN']}"
    if not hmac.compare_digest(request.headers.get("authorization", ""), expected):
        return Response(status_code=401)
    body = await request.body()
    try:
        task = TaskRequest.model_validate_json(body)
        variables = Variables.validate_json(body)
    except ValidationError as error:
        return Response(error.json(include_url=False), status_code=422, media_type="application/json")
    job = begin(task.conversation_id, task.title, task.task, task.done_when, permissions(variables), None)
    return Response(Started(job_id=job.id).model_dump_json(), media_type="application/json")


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
    if event.type != "post_call_transcription":
        return Response()
    if conversation.agent_id == TASK_AGENT:
        if job := by_session(conversation.conversation_id):
            start(finish(job.id, conversation.report()))
    elif conversation.agent_id in (VOICE_AGENT, TEXT_AGENT):
        conversation_ended(conversation)
    return Response()


app = Starlette(
    routes=[
        Route("/api/task", start_task, methods=["POST"]),
        Route("/api/post-call", post_call, methods=["POST"]),
    ]
)

uvicorn.run(app, host="0.0.0.0", port=8090)
