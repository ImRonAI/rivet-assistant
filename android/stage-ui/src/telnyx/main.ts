import {
  TelnyxAIAgent,
  type AgentState,
  type ClientSideToolContext,
  type ClientSideToolErrorReason,
  type ClientSideToolMap,
} from "@telnyx/ai-agent-lib"
import type { INotification } from "@telnyx/webrtc"

/**
 * Ren's Telnyx AI Assistants, through Telnyx's own client (`@telnyx/ai-agent-lib`) in the app's WebView: the app
 * starts a voice or typed conversation with an assistant id, the `perm_*` grants as `X-` headers (Telnyx makes them
 * dynamic variables) and the names of the phone's tools, which the assistant calls as client-side tools. Each call
 * goes to the app, and the app's answer goes back to the assistant.
 */

/** What the app starts a conversation with. */
type Start = {
  /** The app's number for this conversation; every event the page sends for it carries it. */
  session: number
  agentId: string
  /** Typed: no microphone, and the assistant's voice isn't played. */
  chat: boolean
  headers: { name: string; value: string }[]
  tools: string[]
  first: string | null
}

/** What the page tells the app. */
type Event =
  | { type: "ready" }
  | { type: "user"; text: string }
  | { type: "reply"; text: string }
  | { type: "state"; state: AgentState }
  | { type: "tool"; id: string; name: string; arguments: string }
  /** The client answered the call [id] itself (a timeout, or the call ending), so the app's answer would go nowhere. */
  | { type: "dropped"; id: string; reason: ClientSideToolErrorReason }
  | { type: "ended" }
  | { type: "error"; message: string }

declare global {
  interface Window {
    renTelnyx?: {
      start: (options: Start) => void
      send: (text: string) => void
      result: (id: string, output: string) => void
      stop: () => void
    }
    /** The app's web message listener (WebViewCompat.addWebMessageListener). */
    RenTelnyx?: { postMessage: (message: string) => void }
  }
}

const ENDED = new Set(["hangup", "destroy", "purge"])

const voice = document.querySelector("audio")
let agent: TelnyxAIAgent | null = null
let active = false
let queued: string[] = []
const waiting = new Map<string, (output: string) => void>()

/** Tells the app [event], for the conversation [session] (null for none). */
function tell(event: Event, session: number | null) {
  window.RenTelnyx?.postMessage(JSON.stringify({ ...event, session }))
}

function error(e: unknown): Event {
  return { type: "error", message: e instanceof Error ? e.message : String(e) }
}

/** Each tool waits for the app's answer to its call. */
function tools(names: string[], say: (event: Event) => void): ClientSideToolMap {
  const handler = (_: unknown, context: ClientSideToolContext) =>
    new Promise<string>((answer) => {
      waiting.set(context.callId, answer)
      say({ type: "tool", id: context.callId, name: context.toolName, arguments: context.rawArguments })
    })
  return Object.fromEntries(names.map((name) => [name, handler]))
}

function stop() {
  const ending = agent
  agent = null
  active = false
  queued = []
  waiting.clear()
  if (voice) voice.srcObject = null
  if (!ending) return
  void ending.endConversation()
  void ending.disconnect()
}

function start(options: Start) {
  stop()
  const say = (event: Event) => tell(event, options.session)
  const failed = (e: unknown) => say(error(e))
  const live = new TelnyxAIAgent({ agentId: options.agentId, chatMode: options.chat, clientTools: tools(options.tools, say) })
  agent = live
  queued = options.first === null ? [] : [options.first]
  const mine = () => agent === live
  live.on("agent.connected", () => {
    if (mine()) live.startConversation({ customHeaders: options.headers }).catch(failed)
  })
  live.on("conversation.update", (update: INotification) => {
    const call = update.call
    if (!mine() || !call) return
    if (call.state === "active" && !active) {
      active = true
      if (!options.chat && voice) voice.srcObject = call.remoteStream
      queued.forEach((text) => live.sendConversationMessage(text))
      queued = []
    }
    if (ENDED.has(call.state)) {
      stop()
      say({ type: "ended" })
    }
  })
  live.on("transcript.item", (item) => {
    if (mine()) say(item.role === "user" ? { type: "user", text: item.content } : { type: "reply", text: item.content })
  })
  live.on("conversation.agent.state", ({ state }) => {
    if (mine()) say({ type: "state", state })
  })
  live.on("client.tool.error", ({ callId, reason }) => {
    if (!mine() || !waiting.delete(callId)) return
    say({ type: "dropped", id: callId, reason })
  })
  live.on("agent.error", (e) => {
    if (mine()) failed(e)
  })
  live.on("agent.disconnected", () => {
    if (!mine()) return
    stop()
    say({ type: "ended" })
  })
  live.connect().catch((e: unknown) => {
    if (mine()) failed(e)
  })
}

window.renTelnyx = {
  start,
  send(text) {
    if (agent && active) agent.sendConversationMessage(text)
    else queued.push(text)
  },
  result(id, output) {
    waiting.get(id)?.(output)
    waiting.delete(id)
  },
  stop,
}

tell({ type: "ready" }, null)
