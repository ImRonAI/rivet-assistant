import { useEffect, useState, useSyncExternalStore } from "react"

/** Where Emrys stands, in stage pixels (bridge.js `anchor`). */
export type Anchor = { x: number; y: number; feetX: number; feetY: number }

/** What the bridge publishes for this layer (bridge.js `stageInfo`). */
export type StageInfo = {
  state: string
  mood: "cy" | "amb" | "ok" | "rec" | null
  talking: boolean
  turn: "listening" | "speaking" | null
  hud: boolean
  lowFx: boolean
  move: string | null
  width: number
  height: number
  short: boolean
  anchor: Anchor | null
}

/** What is being said right now (bridge.js `speech`); levels are real ones from the app, or null. */
export type Speech = {
  key: string
  weight: number
  at: number
  talking: boolean
  turn: "listening" | "speaking" | null
  output: number | null
  input: number | null
  levelAt: number
}

/** The robot page's armor slots and music (the slot contract in docs/design/EMRYS_MATRIX.md §3). */
export type SlotState = { visible: boolean; facing: number }
export type Scene = { move: string; beat: { bpm: number; phase: number } | null }

type Emrys = {
  stageInfo?: () => StageInfo
  speech?: () => Speech
  slots?: () => Partial<Record<string, SlotState>>
  scene?: () => Scene
}

declare global {
  interface Window {
    emrys?: Emrys
  }
}

const RESTING: StageInfo = {
  state: "idle",
  mood: null,
  talking: false,
  turn: null,
  hud: false,
  lowFx: false,
  move: null,
  width: 0,
  height: 0,
  short: false,
  anchor: null,
}

const SILENT: Speech = {
  key: "sil",
  weight: 0,
  at: 0,
  talking: false,
  turn: null,
  output: null,
  input: null,
  levelAt: 0,
}

let info: StageInfo = RESTING

function subscribeInfo(changed: () => void) {
  const heard = (event: Event) => {
    const detail = (event as CustomEvent<StageInfo>).detail
    if (detail) {
      info = detail
      changed()
    }
  }
  document.addEventListener("emrys:stage", heard)
  return () => document.removeEventListener("emrys:stage", heard)
}

/** The bridge's latest stage info, re-rendering when it publishes a new one. */
export function useStageInfo(): StageInfo {
  return useSyncExternalStore(subscribeInfo, () => {
    if (info === RESTING) info = window.emrys?.stageInfo?.() ?? RESTING
    return info
  })
}

export function readSpeech(): Speech {
  return window.emrys?.speech?.() ?? SILENT
}

export function readSlots(): Partial<Record<string, SlotState>> | null {
  try {
    return window.emrys?.slots?.() ?? null
  } catch {
    return null
  }
}

export function readScene(): Scene | null {
  try {
    return window.emrys?.scene?.() ?? null
  } catch {
    return null
  }
}

function useMedia(query: string): boolean {
  const [matches, setMatches] = useState(() => matchMedia(query).matches)
  useEffect(() => {
    const list = matchMedia(query)
    const changed = () => setMatches(list.matches)
    list.addEventListener("change", changed)
    changed()
    return () => list.removeEventListener("change", changed)
  }, [query])
  return matches
}

export const useReducedMotion = () => useMedia("(prefers-reduced-motion: reduce)")

/** False while the page is hidden (app in the background, screen off). */
export function usePageVisible(): boolean {
  const [visible, setVisible] = useState(() => !document.hidden)
  useEffect(() => {
    const changed = () => setVisible(!document.hidden)
    document.addEventListener("visibilitychange", changed)
    return () => document.removeEventListener("visibilitychange", changed)
  }, [])
  return visible
}

/** True once `key` has stayed the same for `ms`; false at once when it changes. */
export function useSettled(key: string, ms: number): boolean {
  const [settled, setSettled] = useState<string | null>(null)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(key), ms)
    return () => clearTimeout(timer)
  }, [key, ms])
  return settled === key
}

/** True once the value has held for `ms`; false at once when it goes. */
export function useHeld(value: boolean, ms: number): boolean {
  const [held, setHeld] = useState(value)
  useEffect(() => {
    if (!value) {
      setHeld(false)
      return
    }
    const timer = setTimeout(() => setHeld(true), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return held
}

/** The activities that read as working, with the caption the stage shows for each. */
export const WORKING: Record<string, string> = {
  thinking: "Thinking",
  searching: "Searching",
  working: "Working",
  texting: "Writing a text",
  emailing: "Writing an email",
  calling: "On a call",
  video: "On a video call",
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
