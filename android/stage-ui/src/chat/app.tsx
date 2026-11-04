import type { LucideIcon } from "lucide-react"
import { FileTextIcon, ImageIcon, MapPinIcon, NewspaperIcon, SearchIcon, SparklesIcon, VideoIcon, WrenchIcon } from "lucide-react"

import {
  ChainOfThought,
  ChainOfThoughtContent,
  ChainOfThoughtHeader,
  ChainOfThoughtImage,
  ChainOfThoughtSearchResult,
  ChainOfThoughtSearchResults,
  ChainOfThoughtStep,
} from "@/components/ai-elements/chain-of-thought"
import { Reasoning, ReasoningContent, ReasoningTrigger } from "@/components/ai-elements/reasoning"
import { Shimmer } from "@/components/ai-elements/shimmer"
import { Conversation, ConversationContent, ConversationScrollButton } from "@/components/ui/conversation"
import { Message, MessageContent } from "@/components/ui/message"
import { Response } from "@/components/ui/response"

import { type Part, type Step, type StepKind, tell, useChat } from "./model"

/* Body text at 16/24 (Material's Body Large, a point under iOS's 17 pt Body); steps, reasoning and badges keep the
   components' 14 and 12 px. The app scales the page with the system font size (WebView textZoom). */
const BODY = "text-base"

/* Links in a reply open like every other link on the page: the app hands them to the browser or the right app. */
const LINKS = { enabled: false }

const ICONS: Record<StepKind, LucideIcon> = {
  web: SearchIcon,
  news: NewspaperIcon,
  images: ImageIcon,
  videos: VideoIcon,
  places: MapPinIcon,
  context: FileTextIcon,
  answers: SparklesIcon,
  tool: WrenchIcon,
}

const STATUS = { running: "active", done: "complete", failed: "complete" } as const

export function ChatApp() {
  const chat = useChat()
  return (
    <Conversation className="h-dvh">
      <ConversationContent className="flex flex-col">
        {chat.messages.map((m) => {
          switch (m.from) {
            case "user":
              return (
                <Message from="user" key={m.id}>
                  <MessageContent variant="flat" className={`${BODY} rounded-[20px]`}>
                    <Response linkSafety={LINKS}>{m.text}</Response>
                  </MessageContent>
                </Message>
              )
            case "app":
              return (
                <p className="text-muted-foreground py-2 text-center text-xs" key={m.id}>
                  {m.text}
                </p>
              )
            case "assistant":
              return (
                <Message from="assistant" key={m.id}>
                  <MessageContent variant="flat" className={`${BODY} w-full`}>
                    {m.reasoning && (
                      <Reasoning isStreaming={m.reasoning.streaming} duration={m.reasoning.seconds ?? undefined}>
                        <ReasoningTrigger />
                        <ReasoningContent>{m.reasoning.text}</ReasoningContent>
                      </Reasoning>
                    )}
                    {runs(m.parts).map((run) =>
                      run.type === "text" ? (
                        <Response key={run.id} linkSafety={LINKS}>
                          {run.text}
                        </Response>
                      ) : (
                        <Steps key={run.steps[0].id} steps={run.steps} />
                      )
                    )}
                  </MessageContent>
                </Message>
              )
          }
        })}
        {chat.working && (
          <Message from="assistant">
            <MessageContent variant="flat" className={BODY}>
              <Shimmer>Thinking</Shimmer>
            </MessageContent>
          </Message>
        )}
        {chat.error && (
          <p className="text-destructive py-2 text-sm" role="alert">
            {chat.error}
          </p>
        )}
      </ConversationContent>
      <ConversationScrollButton />
    </Conversation>
  )
}

type Run = { type: "text"; id: string; text: string } | { type: "steps"; steps: Step[] }

/** The turn's parts with each stretch of tool calls in between replies gathered into one chain of thought. */
function runs(parts: Part[]): Run[] {
  const out: Run[] = []
  for (const part of parts) {
    const last = out.at(-1)
    if (part.type === "text") out.push(part)
    else if (last?.type === "steps") last.steps.push(part)
    else out.push({ type: "steps", steps: [part] })
  }
  return out
}

function Steps({ steps }: { steps: Step[] }) {
  return (
    <ChainOfThought defaultOpen>
      <ChainOfThoughtHeader>{steps.length === 1 ? steps[0].label : `${steps.length} steps`}</ChainOfThoughtHeader>
      <ChainOfThoughtContent>
        {steps.map((step) => (
          <ChainOfThoughtStep
            description={step.detail ?? undefined}
            icon={ICONS[step.kind]}
            key={step.id}
            label={step.label}
            status={STATUS[step.state]}
          >
            <Found step={step} />
          </ChainOfThoughtStep>
        ))}
      </ChainOfThoughtContent>
    </ChainOfThought>
  )
}

/** What a search found: pages and places as badges, images and videos as pictures. */
function Found({ step }: { step: Step }) {
  return (
    <>
      {step.links.length > 0 && (
        <ChainOfThoughtSearchResults>
          {step.links.map((link) => (
            <ChainOfThoughtSearchResult asChild key={link.url}>
              <a aria-label={link.title} href={link.url}>
                {link.name}
              </a>
            </ChainOfThoughtSearchResult>
          ))}
        </ChainOfThoughtSearchResults>
      )}
      {step.pictures.length > 0 && (
        <ChainOfThoughtImage>
          <div className="grid w-full grid-cols-3 gap-2">
            {step.pictures.map((picture) => (
              <button
                aria-label={picture.title}
                className="overflow-hidden rounded-md"
                key={picture.n}
                onClick={() => tell({ type: "image", step: step.id, n: picture.n })}
                type="button"
              >
                <img alt={picture.title} className="aspect-square w-full object-cover" loading="lazy" src={picture.thumbnail} />
              </button>
            ))}
          </div>
        </ChainOfThoughtImage>
      )}
      {step.videos.length > 0 && (
        <ChainOfThoughtImage>
          <div className="grid w-full grid-cols-2 gap-3">
            {step.videos.map((video) => (
              <a className="block min-w-0 text-left" href={video.url} key={video.url}>
                <span className="bg-background relative block aspect-video overflow-hidden rounded-md">
                  {video.thumbnail && <img alt="" className="size-full object-cover" loading="lazy" src={video.thumbnail} />}
                  {video.duration && (
                    <span className="absolute right-1 bottom-1 rounded bg-black/75 px-1 text-xs text-white">{video.duration}</span>
                  )}
                </span>
                <span className="mt-1 line-clamp-2 text-xs">{video.title}</span>
              </a>
            ))}
          </div>
        </ChainOfThoughtImage>
      )}
    </>
  )
}
