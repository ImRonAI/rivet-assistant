/*
 * The app's side of the robot page. The app drives it with window.ren.{state,mouth,quiet,viseme,theme,focus,voice,
 * level} (core/RobotDirector.kt, ui/RobotView.kt) and hears `ready` through the RenHost web message listener. The
 * scene reads `stage` every frame, the way CatoWeb's scene reads its bus.
 */
export type Focus = "stage" | "card" | "greet"
export type Theme = "light" | "dark"
type Turn = "listening" | "speaking" | "none"

/** What the app is doing, as the move Ren plays for it (the moves are Cato's own). */
const STATE_MOVE: Readonly<Record<string, string>> = {
  idle: "idle", listening: "idle", thinking: "think", searching: "think", working: "desk",
  texting: "text", emailing: "email", calling: "phone", waiting: "idle", done: "cheer", failed: "shrug",
}

/** One spoken word being mouthed: its letters, when it began (ms) and how long each letter lasts. */
type Word = { letters: string; start: number; step: number }

export const stage = {
  move: "idle",
  /** Bumped on every state call, so a repeated state (two "done" in a row) plays again. */
  moveSeq: 0,
  focus: "stage" as Focus,
  theme: "light" as Theme,
  word: null as Word | null,
  /** Visemes set directly by the app since the last frame. */
  visemes: new Map<string, number>(),
  turn: "none" as Turn,
  /** The agent's audio level, 0..1, as the ElevenLabs SDK reports it; null until it reports one. */
  level: null as number | null,
}

const listeners = new Set<() => void>()
/** Runs [changed] whenever the theme changes (the canvas background is React state). */
export function onTheme(changed: () => void): () => void {
  listeners.add(changed)
  return () => listeners.delete(changed)
}

function state(name: string) {
  stage.move = STATE_MOVE[name] ?? "idle"
  stage.moveSeq++
}

/** Lip sync for one spoken word: the app calls this from the agent's word timings. */
function mouth(word: string, seconds: number) {
  const letters = String(word).toLowerCase().replace(/[^a-z]/g, "")
  if (!letters) return
  const step = Math.max(40, ((seconds > 0 ? seconds : letters.length / 13) * 1000) / letters.length)
  stage.word = { letters, start: performance.now(), step }
}

function quiet() {
  stage.word = null
  stage.level = null
}

function viseme(key: string, value: number) {
  stage.visemes.set(String(key), Number(value) || 0)
}

function theme(name: string) {
  stage.theme = name === "dark" ? "dark" : "light"
  document.documentElement.setAttribute("data-theme", stage.theme)
  listeners.forEach((changed) => changed())
}

function focus(mode: string) {
  if (mode === "stage" || mode === "card" || mode === "greet") stage.focus = mode
}

function voice(turn: string) {
  stage.turn = turn === "listening" || turn === "speaking" ? turn : "none"
  if (stage.turn !== "speaking") stage.level = null
}

function level(value: number) {
  stage.level = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0
}

const api = { state, mouth, quiet, viseme, theme, focus, voice, level }

declare global {
  interface Window {
    ren: typeof api
    /** The app's web message listener (WebViewCompat.addWebMessageListener). */
    RenHost?: { postMessage(message: string): void }
  }
}

window.ren = api

/** Tells the app the robot is drawn and listening. */
export function ready() {
  window.RenHost?.postMessage(JSON.stringify({ type: "ready" }))
}
