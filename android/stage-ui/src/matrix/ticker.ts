import { useCallback, useSyncExternalStore } from "react"

import type { Frame } from "@/components/ui/matrix"

import { readScene, readSpeech } from "@/emrys"

import { OPEN, visemeName } from "./armor"
import type { Live, Tick } from "./program"

/**
 * One clock for every live show, about 15 times a second on animation frames (so it stops while the page is hidden):
 * the speech envelope (the app's real output level when it sends one, else how open the current viseme is, with a
 * short release), the viseme itself, the real input level, and the music's beat from `emrys.scene()`.
 */
const INTERVAL = 66
/** A level older than this is stale (the app sends levels only while they change). */
const FRESH_MS = 1500
/** The page's own music tempo, for a page that doesn't report its beat. */
const HOUSE_BPM = 118

let tick: Tick = { now: 0, env: 0, viseme: "sil", weight: 0, talking: false, input: null, beats: 0 }
const listeners = new Set<() => void>()
let frame = 0
let last = 0
let beatBase = 0
let lastPhase = -1

function sample(now: number) {
  const speech = readSpeech()
  const fresh = speech.levelAt > 0 && now - speech.levelAt < FRESH_MS
  const recent = now - speech.at < 180 && speech.weight > 0
  const name = recent ? visemeName(speech.key) : "sil"
  let target = 0
  if (fresh && speech.output !== null) target = speech.output
  else if (speech.talking && recent) target = (OPEN[name] ?? 0.5) * Math.max(0.6, speech.weight)
  const env = target >= tick.env ? target : tick.env + (target - tick.env) * 0.4

  const scene = readScene()
  let beats: number
  if (scene?.beat && scene.beat.bpm > 0) {
    // The phase may be a running count or a fraction of the current beat; count the wraps of a fraction.
    const phase = scene.beat.phase
    if (phase < 1 && lastPhase >= 0 && phase < lastPhase - 0.5) beatBase += 1
    lastPhase = phase
    beats = phase >= 1 ? phase : beatBase + phase
  } else {
    lastPhase = -1
    beats = (now / 60000) * HOUSE_BPM
  }

  tick = {
    now,
    env: Math.round(env * 100) / 100,
    viseme: name,
    weight: recent ? speech.weight : 0,
    talking: speech.talking,
    input: fresh ? speech.input : null,
    beats,
  }
  listeners.forEach((l) => l())
}

function run(now: number) {
  if (now - last >= INTERVAL) {
    last = now
    sample(now)
  }
  frame = requestAnimationFrame(run)
}

function subscribe(changed: () => void) {
  listeners.add(changed)
  if (listeners.size === 1) frame = requestAnimationFrame(run)
  return () => {
    listeners.delete(changed)
    if (listeners.size === 0) cancelAnimationFrame(frame)
  }
}

const idle = () => () => {}

/** A live show's current pattern; its panel re-renders only when the pattern changes (patterns are cached frames). */
export function useLivePattern(show: Live | null, on: boolean): Frame | null {
  const pattern = show ? show.pattern : null
  const read = useCallback(() => (pattern ? pattern(tick) : null), [pattern])
  return useSyncExternalStore(on && pattern ? subscribe : idle, read)
}

/** The latest tick, for a one-off read (a wipe's target). */
export const currentTick = () => tick
