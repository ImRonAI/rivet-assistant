import type { Frame } from "@/components/ui/matrix"

import { anim, memo, once, type Program } from "./program"
import { edge, hash, loop, paint } from "./raster"

/**
 * The armor's light: the gauntlets (3×11 forearm plates, column 0 at the wrist) and the mouth (3×15 across the
 * lower visor), after docs/design/EMRYS_MATRIX.md §4–5.
 */
export const GAUNTLET = { rows: 3, cols: 11 }
export const MOUTH = { rows: 3, cols: 15 }

const WORK = new Set(["thinking", "searching", "working", "texting", "emailing", "calling", "video"])

/** A light running from wrist to elbow with a soft tail, echoing the chest; `side` offsets the right arm. */
function chase(period: number, side: number, peak: number): Program {
  return memo(`chase${period}:${side}:${peak}`, () =>
    anim(
      `chase${period}:${side}:${peak}`,
      loop(GAUNTLET.rows, GAUNTLET.cols, period, 8, (t) => {
        const head = (((t / period + side * 0.5) % 1) * (GAUNTLET.cols + 5)) - 2.5
        return (x, y) => {
          const ahead = x - head
          const v = ahead > 0 ? edge(ahead, 0.9) : Math.exp(ahead / 2.2)
          return (y === 1 ? peak : peak * 0.35) * v
        }
      }),
      8
    )
  )
}

/** The gauntlet for a state: an ambient chase, quicker while Emrys works, a slow breath while it waits. */
export function gauntletFor(state: string, side: 0 | 1): Program {
  if (state === "sleeping") return memo("gauntlet-off", () => anim("gauntlet-off", [paint(3, 11, () => 0)], 1))
  if (state === "waiting")
    return memo(`gauntlet-wait${side}`, () =>
      anim(
        `gauntlet-wait${side}`,
        loop(3, 11, 3, 6, (t) => (_, y) => (y === 1 ? 0.2 + 0.18 * (0.5 - 0.5 * Math.cos((2 * Math.PI * t) / 3)) : 0.06)),
        6
      )
    )
  return chase(WORK.has(state) ? 1.4 : 2.8, side, WORK.has(state) ? 0.5 : 0.4)
}

/** The boot show's gauntlets: dark, a sweep from wrist to elbow, then `then`. */
export function bootGauntlet(then: Program): Program {
  const frames = loop(3, 11, 2.2, 10, (t) => {
    const u = (t - 0.8) / 0.7
    return (x) => (u < 0 ? 0 : u > 1.4 ? 0 : 0.5 * edge(Math.abs(x - u * 12 + 1) - 0.5, 2.5) * (u > 1 ? 1.4 - u : 1))
  })
  return once("gauntlet:boot", frames, 10, then)
}

/** A beat VU along the forearm during music: it fills from the wrist on each beat and falls away. */
export function beatVu(beats: number): Frame {
  const cycle = memo("beat-vu", () => {
    const frames: Frame[] = []
    for (let beat = 0; beat < 4; beat++)
      for (let s = 0; s < 4; s++) {
        const reach = (0.55 + 0.45 * hash(beat, 3, 23)) * (1 - s / 4) ** 1.3 * GAUNTLET.cols
        frames.push(paint(3, 11, (x) => (x < reach - 1 ? 0.45 : x < reach ? 0.75 : 0.05)))
      }
    return frames
  })
  const i = Math.floor(beats * 4)
  return cycle[((i % cycle.length) + cycle.length) % cycle.length]
}

/* The mouth. Each viseme (the Oculus set the page's lip sync uses) is a dot-matrix shape on 3 rows: [row, from, to, v]. */
type Run = [number, number, number, number]
const SHAPES: Record<string, Run[]> = {
  sil: [[1, 4, 10, 0.35]],
  PP: [[1, 3, 11, 0.8]],
  FF: [[1, 4, 10, 0.25], [2, 4, 10, 0.75]],
  TH: [[1, 4, 10, 0.35], [1, 7, 7, 0.9]],
  DD: [[0, 5, 9, 0.45], [1, 4, 4, 0.4], [1, 10, 10, 0.4], [2, 5, 9, 0.6]],
  kk: [[0, 5, 9, 0.3], [1, 4, 4, 0.35], [1, 10, 10, 0.35], [2, 5, 9, 0.55]],
  nn: [[0, 5, 9, 0.35], [2, 5, 9, 0.5]],
  CH: [[0, 5, 9, 0.3], [1, 4, 4, 0.7], [1, 6, 6, 0.7], [1, 8, 8, 0.7], [1, 10, 10, 0.7], [2, 5, 9, 0.3]],
  SS: [[0, 4, 10, 0.25], [1, 3, 3, 0.6], [1, 5, 5, 0.6], [1, 7, 7, 0.6], [1, 9, 9, 0.6], [1, 11, 11, 0.6], [2, 4, 10, 0.25]],
  RR: [[0, 6, 8, 0.55], [1, 5, 5, 0.6], [1, 9, 9, 0.6], [2, 6, 8, 0.55]],
  aa: [[0, 3, 11, 0.85], [1, 2, 2, 0.85], [1, 12, 12, 0.85], [1, 3, 11, 0.1], [2, 3, 11, 0.85]],
  E: [[0, 2, 12, 0.7], [1, 1, 1, 0.5], [1, 13, 13, 0.5], [2, 2, 12, 0.7]],
  I: [[0, 3, 11, 0.6], [1, 2, 2, 0.45], [1, 12, 12, 0.45], [2, 3, 11, 0.6]],
  O: [[0, 5, 9, 0.85], [1, 4, 4, 0.85], [1, 10, 10, 0.85], [2, 5, 9, 0.85]],
  U: [[0, 6, 8, 0.8], [1, 5, 5, 0.8], [1, 9, 9, 0.8], [2, 6, 8, 0.8]],
  /* resting expressions */
  smile: [[0, 3, 3, 0.55], [0, 11, 11, 0.55], [1, 4, 4, 0.55], [1, 10, 10, 0.55], [2, 5, 9, 0.55]],
  frown: [[2, 3, 3, 0.45], [2, 11, 11, 0.45], [1, 4, 4, 0.45], [1, 10, 10, 0.45], [0, 5, 9, 0.45]],
  rest: [[1, 6, 8, 0.22]],
  blink: [[0, 2, 12, 0.7], [1, 1, 13, 0.7], [2, 2, 12, 0.7]],
}
const ALIAS: Record<string, string> = { ih: "I", oh: "O", ou: "U", e: "E", i: "I", o: "O", u: "U" }

/** How open each viseme is, for the speech envelope when the app sends no real level. */
export const OPEN: Record<string, number> = {
  sil: 0, PP: 0.25, FF: 0.35, TH: 0.4, DD: 0.5, kk: 0.5, nn: 0.45, CH: 0.55, SS: 0.55, RR: 0.6,
  aa: 1, E: 0.8, I: 0.7, O: 0.9, U: 0.7,
}

export const visemeName = (key: string) => (key in SHAPES ? key : (ALIAS[key] ?? "nn"))

/** The mouth for a viseme at a weight (0..1); the shape holds and the brightness follows the weight. */
export function mouthShape(key: string, weight = 1): Frame {
  const name = visemeName(key)
  const level = Math.round(Math.max(0, Math.min(1, weight)) * 4)
  return memo(`mouth:${name}:${level}`, () => {
    const k = name === "sil" || !(name in OPEN) ? 1 : 0.45 + 0.55 * (level / 4)
    const frame = paint(MOUTH.rows, MOUTH.cols, () => 0)
    for (const [row, from, to, v] of SHAPES[name]) for (let x = from; x <= to; x++) frame[row][x] = Math.max(frame[row][x], v * k)
    return frame
  })
}

/** The mouth at rest for a state: calm, a smile when done, a frown when failed, barely there asleep. */
export function restingMouth(state: string): Frame {
  return mouthShape(state === "done" ? "smile" : state === "failed" ? "frown" : state === "sleeping" ? "rest" : "sil")
}
