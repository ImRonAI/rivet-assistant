import { useCallback, useSyncExternalStore } from "react"

import type { Frame } from "@/components/ui/matrix"

import { readSpeech } from "@/emrys"

import { OPEN, visemeName } from "./armor"
import type { Live, Tick } from "./program"

/**
 * One clock for every live show, about 15 times a second on animation frames (so it stops while the page is hidden):
 * the speech envelope (the agent's audio level while it speaks, else how open the viseme on the mouth is, with a short
 * release so the dots fall softly between letters) and the viseme itself.
 */
const INTERVAL = 66

let tick: Tick = { env: 0, viseme: "sil", weight: 0, talking: false }
const listeners = new Set<() => void>()
let frame = 0
let last = 0

function sample() {
  const speech = readSpeech()
  const name = speech.weight > 0 ? visemeName(speech.viseme) : "sil"
  const target = speech.talking ? (speech.level ?? (OPEN[name] ?? 0) * speech.weight) : 0
  const env = target >= tick.env ? target : tick.env + (target - tick.env) * 0.4
  tick = { env: Math.round(env * 100) / 100, viseme: name, weight: speech.weight, talking: speech.talking }
  listeners.forEach((l) => l())
}

function run(now: number) {
  if (now - last >= INTERVAL) {
    last = now
    sample()
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
