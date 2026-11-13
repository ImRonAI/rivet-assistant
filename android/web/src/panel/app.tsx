/*
 * The conversation the way ren desktop's agent panel draws it (src/components/agent-panel/AgentPanel.tsx at b112adf:
 * `MessageBubble`, `PartView`, `ReasoningChain` and `SearchNativeCard`), on its own components in src/ren.
 */
import { useMemo } from "react"
import { ChainOfThought, ChainOfThoughtContent, ChainOfThoughtHeader, ChainOfThoughtImage, ChainOfThoughtSearchResult, ChainOfThoughtSearchResults, ChainOfThoughtStep } from "@/components/ai-elements/chain-of-thought"
import { Conversation, ConversationContent, ConversationScrollButton } from "@/components/ai-elements/conversation"
import { Message, MessageContent } from "@/components/ai-elements/message"
import { MessageResponse } from "@/components/ai-elements/response"
import { Tool, ToolHeader } from "@/components/ai-elements/tool"
import { cn } from "@/utils/cn"
import { type Message as ChatMessage, type Reasoning, type Step, tell, useChat } from "./model"

export function PanelChat() {
  const chat = useChat()
  return (
    <Conversation className="h-dvh">
      <ConversationContent className="gap-5 px-4 py-5">
        {chat.messages.map((message) => <Bubble key={message.id} message={message} />)}
        {chat.working && (
          <div role="status" className="flex items-center gap-2 text-xs text-ink-muted dark:text-ink-inverse-muted">
            <span className="h-1.5 w-1.5 rounded-full bg-volt" />
            Ren is working
          </div>
        )}
        {chat.error !== null && (
          <p role="alert" className="text-xs text-red-600 dark:text-red-400">
            {chat.error}
          </p>
        )}
      </ConversationContent>
      <ConversationScrollButton />
    </Conversation>
  )
}

/** One message, as ren's `MessageBubble`: the user's in a contained bubble, Ren's flat. */
function Bubble({ message }: { message: ChatMessage }) {
  switch (message.from) {
    case "user":
      return (
        <Message from="user">
          <MessageContent
            variant="contained"
            className="min-w-0 w-full group-[.is-user]:bg-surface-100 group-[.is-user]:text-ink dark:group-[.is-user]:bg-surface-800 dark:group-[.is-user]:text-ink-inverse"
          >
            <p className="text-body-sm leading-relaxed whitespace-pre-wrap">{message.text}</p>
          </MessageContent>
        </Message>
      )
    case "app":
      return <p className="text-center text-xs text-ink-muted dark:text-ink-inverse-muted">{message.text}</p>
    case "assistant":
      return (
        <Message from="assistant">
          <MessageContent variant="flat" className="min-w-0 w-full">
            {message.reasoning !== null && <ReasoningChain reasoning={message.reasoning} />}
            {message.parts.map((part) =>
              part.type === "text" ? (
                <div className="my-2" key={part.id}>
                  <MessageResponse>{part.text}</MessageResponse>
                </div>
              ) : part.kind === "tool" ? (
                <ToolCard key={part.id} step={part} />
              ) : (
                <SearchCard key={part.id} step={part} />
              ),
            )}
          </MessageContent>
        </Message>
      )
  }
}

/** Reasoning split at its bold labels into steps, as ren's `reasoningSteps`. */
function reasoningSteps(text: string): { label: string; body: string }[] {
  const steps: { label: string; body: string }[] = []
  const pattern = /\*\*([^*\n]+)\*\*\s*/g
  let match: RegExpExecArray | null
  let cursor = 0
  let label = ""
  while ((match = pattern.exec(text)) !== null) {
    const body = text.slice(cursor, match.index).trim()
    if (label || body) steps.push({ label: label || "Thinking", body })
    label = (match[1] ?? "").trim()
    cursor = pattern.lastIndex
  }
  const tail = text.slice(cursor).trim()
  if (label || tail) steps.push({ label: label || "Thinking", body: tail })
  return steps
}

function ReasoningChain({ reasoning }: { reasoning: Reasoning }) {
  const steps = useMemo(() => reasoningSteps(reasoning.text), [reasoning.text])
  return (
    <ChainOfThought className="my-3 space-y-2 text-xs" defaultOpen={reasoning.streaming} isStreaming={reasoning.streaming}>
      <ChainOfThoughtHeader>{reasoning.streaming ? "Thinking…" : "Chain of Thought"}</ChainOfThoughtHeader>
      <ChainOfThoughtContent>
        {steps.map((step, index) => (
          <ChainOfThoughtStep
            key={`${index}-${step.label}`}
            label={step.label}
            status={reasoning.streaming && index === steps.length - 1 ? "active" : "complete"}
          >
            {step.body && <p className="whitespace-pre-wrap break-words text-xs text-ink-muted dark:text-ink-inverse-muted">{step.body}</p>}
          </ChainOfThoughtStep>
        ))}
      </ChainOfThoughtContent>
    </ChainOfThought>
  )
}

const TOOL_STATES = { running: "input-available", done: "output-available", failed: "output-error" } as const

/** Any tool but a search, as ren's tool part. */
function ToolCard({ step }: { step: Step }) {
  return (
    <Tool className="my-3 overflow-hidden border-surface-200 dark:border-surface-700">
      <ToolHeader type="dynamic-tool" toolName={step.label} title={step.label} state={TOOL_STATES[step.state]} />
      {step.detail !== null && <p className="px-3 pb-2 text-xs text-ink-muted dark:text-ink-inverse-muted">{step.detail}</p>}
    </Tool>
  )
}

/** A search, as ren's `SearchNativeCard`: what it searched for, the sources it found, and its pictures. */
function SearchCard({ step }: { step: Step }) {
  const pending = step.state === "running"
  const failed = step.state === "failed"
  const found = step.links.length + step.pictures.length + step.videos.length
  return (
    <ChainOfThought className="my-3 space-y-2 text-xs" defaultOpen={pending || found > 0} isStreaming={pending}>
      <ChainOfThoughtHeader>{pending && found === 0 ? `${step.label}…` : failed ? `${step.label} · failed` : step.label}</ChainOfThoughtHeader>
      <ChainOfThoughtContent>
        {step.detail !== null && !failed && (
          <ChainOfThoughtStep label="Searching for" status={pending && found === 0 ? "active" : "complete"}>
            <ChainOfThoughtSearchResults>
              <ChainOfThoughtSearchResult>{step.detail}</ChainOfThoughtSearchResult>
            </ChainOfThoughtSearchResults>
          </ChainOfThoughtStep>
        )}
        {step.links.length > 0 && (
          <ChainOfThoughtStep label={`Sources (${step.links.length})`} status={pending ? "active" : "complete"}>
            <ChainOfThoughtSearchResults>
              {step.links.map((link) => (
                <ChainOfThoughtSearchResult key={link.url} title={link.title}>
                  <a href={link.url} className="hover:underline">
                    {link.name}
                  </a>
                </ChainOfThoughtSearchResult>
              ))}
            </ChainOfThoughtSearchResults>
          </ChainOfThoughtStep>
        )}
        {step.pictures.length > 0 && (
          <ChainOfThoughtStep label="Images" status={pending ? "active" : "complete"}>
            <div className="grid grid-cols-2 gap-3">
              {step.pictures.map((picture) => (
                <ChainOfThoughtImage key={picture.n} caption={picture.title}>
                  <button type="button" aria-label={picture.title} className="block max-h-full max-w-full" onClick={() => tell({ type: "image", step: step.id, n: picture.n })}>
                    <img src={picture.thumbnail} alt={picture.title} loading="lazy" referrerPolicy="no-referrer" className="max-h-full max-w-full object-contain" />
                  </button>
                </ChainOfThoughtImage>
              ))}
            </div>
          </ChainOfThoughtStep>
        )}
        {step.videos.length > 0 && (
          <ChainOfThoughtStep label="Videos" status={pending ? "active" : "complete"}>
            <div className="grid grid-cols-2 gap-3">
              {step.videos.map((video) => (
                <ChainOfThoughtImage key={video.url} caption={video.title}>
                  <a href={video.url} aria-label={video.title} className={cn("relative block max-h-full max-w-full", video.thumbnail === null && "aspect-video w-full")}>
                    {video.thumbnail !== null && (
                      <img src={video.thumbnail} alt="" loading="lazy" referrerPolicy="no-referrer" className="max-h-full max-w-full object-contain" />
                    )}
                    {video.duration !== null && (
                      <span className="absolute right-1 bottom-1 rounded bg-black/75 px-1 text-xs text-white">{video.duration}</span>
                    )}
                  </a>
                </ChainOfThoughtImage>
              ))}
            </div>
          </ChainOfThoughtStep>
        )}
        {failed && <p className="text-xs text-red-600 dark:text-red-400">{step.detail ?? `${step.label} failed.`}</p>}
      </ChainOfThoughtContent>
    </ChainOfThought>
  )
}
