import { useSyncExternalStore } from "react"

/* The chat as the app sends it (ui/ChatView.kt, `ChatPage.payload`), and what the page tells the app back. */
export type ToolState = "running" | "done" | "failed"

/** A search tool's kind (core/Search.kt `SearchKind.wire`), or "tool" for every other tool. */
export type StepKind = "web" | "news" | "images" | "videos" | "places" | "context" | "answers" | "tool"

/** A result that opens a page or a map: its badge reads [name]. */
export type Link = { title: string; url: string; name: string }

/** An image result; [n] is its number in the step, which the app opens full size. */
export type Picture = { n: number; title: string; thumbnail: string }

export type Video = { title: string; url: string; thumbnail: string | null; duration: string | null }

/** One tool call; [links], [pictures] and [videos] hold what a search found. */
export type Step = {
  id: string
  kind: StepKind
  label: string
  detail: string | null
  state: ToolState
  links: Link[]
  pictures: Picture[]
  videos: Video[]
}

export type Part = { type: "text"; id: string; text: string } | ({ type: "tool" } & Step)

export type Reasoning = { text: string; streaming: boolean; seconds: number | null }

export type Message =
  | { from: "user"; id: string; text: string }
  | { from: "app"; id: string; text: string }
  | { from: "assistant"; id: string; reasoning: Reasoning | null; parts: Part[] }

export type ChatModel = {
  messages: Message[]
  /** The user's message is in and nothing of the reply has arrived yet. */
  working: boolean
  error: string | null
}

/** What the page asks of the app. Links open through the WebView's own navigation. */
export type Intent = { type: "ready" } | { type: "image"; step: string; n: number }

declare global {
  interface Window {
    renChat?: { show: (model: ChatModel) => void }
    /** The app's web message listener (WebViewCompat.addWebMessageListener). */
    RenChat?: { postMessage: (message: string) => void }
  }
}

const EMPTY: ChatModel = { messages: [], working: false, error: null }
let current = EMPTY
const listeners = new Set<() => void>()

window.renChat = {
  show(model) {
    current = model
    listeners.forEach((changed) => changed())
  },
}

export function useChat(): ChatModel {
  return useSyncExternalStore(
    (changed) => {
      listeners.add(changed)
      return () => listeners.delete(changed)
    },
    () => current
  )
}

export function tell(intent: Intent) {
  window.RenChat?.postMessage(JSON.stringify(intent))
}
