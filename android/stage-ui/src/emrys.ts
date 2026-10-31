import { useEffect, useSyncExternalStore } from "react"

/* The robot page's bridge (android/robot/bridge.js, typed in robot/page.d.ts) publishes these shapes. */
export type Mood = "cy" | "amb" | "ok" | "rec"
export type Turn = "listening" | "speaking" | "none"

/** Where Emrys stands, in stage pixels. */
export type Anchor = { x: number; y: number; feetX: number; feetY: number }

/** What Emrys is doing and where (bridge.js `stageInfo`). */
export type StageInfo = {
  state: string
  mood: Mood | null
  talking: boolean
  /** Whose turn it is in an ElevenLabs voice session. */
  turn: Turn
  /** Whether the session has reported the agent's audio level. */
  voiced: boolean
  lowFx: boolean
  width: number
  height: number
  short: boolean
  anchor: Anchor | null
}

/** What is being said now (bridge.js `speech`): the viseme and its weight, and the agent's audio level. */
export type Speech = { viseme: string; weight: number; talking: boolean; level: number | null }

/** The robot page's view of an armor slot (docs/design/EMRYS_MATRIX.md §3). */
export type SlotState = { visible: boolean; facing: number }

type Bridge = {
  stageInfo?: () => StageInfo
  speech?: () => Speech
  slots?: () => Partial<Record<string, SlotState>>
}

declare global {
  interface Window {
    emrys?: Bridge
  }
}

const RESTING: StageInfo = {
  state: "idle",
  mood: null,
  talking: false,
  turn: "none",
  voiced: false,
  lowFx: false,
  width: 0,
  height: 0,
  short: false,
  anchor: null,
}

const SILENT: Speech = { viseme: "sil", weight: 0, talking: false, level: null }

function subscribeInfo(changed: () => void) {
  document.addEventListener("emrys:stage", changed)
  return () => document.removeEventListener("emrys:stage", changed)
}

/** The bridge's latest stage info (one object until it publishes a new one), re-rendering when it does. */
export function useStageInfo(): StageInfo {
  return useSyncExternalStore(subscribeInfo, () => window.emrys?.stageInfo?.() ?? RESTING)
}

export function readSpeech(): Speech {
  return window.emrys?.speech?.() ?? SILENT
}

export function readSlots(): Partial<Record<string, SlotState>> {
  return window.emrys?.slots?.() ?? {}
}

function useMedia(query: string): boolean {
  return useSyncExternalStore(
    (changed) => {
      const list = matchMedia(query)
      list.addEventListener("change", changed)
      return () => list.removeEventListener("change", changed)
    },
    () => matchMedia(query).matches
  )
}

export const useReducedMotion = () => useMedia("(prefers-reduced-motion: reduce)")

/** False while the page is hidden (app in the background, screen off). */
export function usePageVisible(): boolean {
  return useSyncExternalStore(
    (changed) => {
      document.addEventListener("visibilitychange", changed)
      return () => document.removeEventListener("visibilitychange", changed)
    },
    () => !document.hidden
  )
}

/** The activities that read as working, with the caption the stage shows for each. */
export const WORKING: Partial<Record<string, string>> = {
  thinking: "Thinking",
  searching: "Searching",
  working: "Working",
  texting: "Writing a text",
  emailing: "Writing an email",
  calling: "On a call",
}

function darkTheme(): boolean {
  const theme = document.documentElement.getAttribute("data-theme")
  return theme ? theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches
}

/**
 * Mirrors the page's theme (the app's `emrys.theme`, else the system's) into the `dark` class on <html>, the
 * dark-mode switch the ElevenLabs components read (the Orb inverts its luminance, unlit Matrix dots dim further).
 */
export function useDarkClass() {
  useEffect(() => {
    const html = document.documentElement
    const apply = () => html.classList.toggle("dark", darkTheme())
    apply()
    const observer = new MutationObserver(apply)
    observer.observe(html, { attributes: true, attributeFilter: ["data-theme"] })
    const media = matchMedia("(prefers-color-scheme: dark)")
    media.addEventListener("change", apply)
    return () => {
      observer.disconnect()
      media.removeEventListener("change", apply)
      html.classList.remove("dark")
    }
  }, [])
}
