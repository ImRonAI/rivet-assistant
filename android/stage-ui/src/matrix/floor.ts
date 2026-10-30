import type { Frame } from "@/components/ui/matrix"

import { anim, memo, once, still, type Program } from "./program"
import { band, dist, edge, hash, inCircle, loop, paint, turn } from "./raster"

/**
 * The light floor under Emrys's feet (17×17, circular): ripples, radar, celebrations and a dance floor
 * (docs/design/EMRYS_MATRIX.md §4–5). It stays at or under 0.5, so its dots never take the glow filter. The design
 * names 25×25 (625 dots, about 900 on the page with the armor); every animated dot repaints through its 300 ms fade, and
 * with 4× CPU throttling the floor was the largest single cost, so it is 17×17 (289 dots, about 570 on the page).
 */
export const FLOOR = 17
const R = (FLOOR - 1) / 2
const CAP = 0.5

/** At rest: one faint ring leaves the feet every 8 s. */
function rest(dim = 1): Program {
  return memo(`floor-rest${dim}`, () =>
    anim(
      `floor-rest${dim}`,
      loop(FLOOR, FLOOR, 8, 4, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const r = (t / 3) * (R + 1)
        return t < 3 ? dim * 0.24 * band(dist(x, y, c.x, c.y), r, 0.4, 1) * (1 - r / (R + 2)) : 0
      }),
      4
    )
  )
}

/** One ring outward (a state change, greeting, the boot show) or inward (failed), then `then`. */
function ring(key: string, inward: boolean, seconds: number, then: Program, delay = 0): Program {
  const fps = 10
  const frames = loop(FLOOR, FLOOR, seconds + delay, fps, (t) => {
    const u = Math.max(0, (t - delay) / seconds)
    const r = (inward ? 1 - u : u) * (R + 1)
    return (x, y, c) => {
      if (t < delay || !inCircle(x, y, c)) return 0
      return CAP * band(dist(x, y, c.x, c.y), r, 0.5, 1.2) * (inward ? 0.85 : 1 - 0.55 * u)
    }
  })
  return once(key, frames, fps, then)
}

/** searching: a radar beam sweeping the floor, with faint range rings. */
const radar = () =>
  memo("floor-radar", () =>
    anim(
      "floor-radar",
      loop(FLOOR, FLOOR, 2.4, 10, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const d = dist(x, y, c.x, c.y)
        const behind = (t / 2.4 - turn(x, y, c) + 1) % 1
        const beam = behind < 0.14 ? CAP * (1 - behind / 0.14) ** 1.4 * Math.min(1, 0.35 + d / R) : 0
        return Math.max(beam, 0.12 * Math.max(band(d, R / 3, 0.3, 0.8), band(d, (2 * R) / 3, 0.3, 0.8)))
      }),
      10
    )
  )

/** listening: slow sonar rings. */
const sonar = () =>
  memo("floor-sonar", () =>
    anim(
      "floor-sonar",
      loop(FLOOR, FLOOR, 2.4, 8, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const d = dist(x, y, c.x, c.y)
        let v = 0
        for (const k of [0, 0.5]) {
          const r = ((t / 2.4 + k) % 1) * (R + 1)
          v = Math.max(v, 0.3 * band(d, r, 0.4, 1) * (1 - r / (R + 2)))
        }
        return v
      }),
      8
    )
  )

/** waiting: one slow honey ring held under Emrys. */
const held = () =>
  memo("floor-held", () =>
    anim(
      "floor-held",
      loop(FLOOR, FLOOR, 3, 6, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        return (0.3 + 0.12 * (0.5 - 0.5 * Math.cos((2 * Math.PI * t) / 3))) * band(dist(x, y, c.x, c.y), R * 0.62, 0.5, 1)
      }),
      6
    )
  )

/** done: a burst of sparks outward across the floor, then rest. */
function burst(then: Program): Program {
  const rays = Array.from({ length: 22 }, (_, i) => ({ a: (i + hash(i, 1)) / 22, speed: 0.75 + 0.5 * hash(i, 2) }))
  const fps = 10
  const frames = loop(FLOOR, FLOOR, 1.4, fps, (t) => {
    const sparks = rays.map(({ a, speed }) => {
      const r = 1.5 + Math.min(1, (t / 1.4) * speed) * (R - 1)
      const ang = a * 2 * Math.PI
      return { x: R + Math.sin(ang) * r, y: R - Math.cos(ang) * r, v: CAP * (1 - (t / 1.4) * 0.7) }
    })
    return (x, y, c) => {
      if (!inCircle(x, y, c)) return 0
      let v = 0
      for (const s of sparks) v = Math.max(v, s.v * edge(dist(x, y, s.x, s.y) - 0.3, 1))
      return v
    }
  })
  return once("floor-burst", frames, fps, then)
}

/** What the floor plays for a state; each state change sends one ring first. */
export function floorFor(state: string): Program {
  return memo(`floor:${state}`, () => {
    if (state === "sleeping") return still("floor:sleeping", paint(FLOOR, FLOOR, () => 0))
    if (state === "done") return ring("floor:done", false, 0.9, burst(rest()))
    if (state === "failed") return ring("floor:failed", true, 1.2, rest(0.6))
    const next =
      state === "searching" ? radar() : state === "listening" ? sonar() : state === "waiting" ? held() : rest()
    return ring(`floor:${state}`, false, 1.1, next)
  })
}

/** The boot show's floor: it ignites outward from the feet in one ripple, then rests. */
export const bootFloor = (then: Program) => ring("floor:boot", false, 1.0, then)

/** While Emrys speaks the floor breathes with the speech envelope, low. */
export function floorBreath(env: number): Frame {
  const levels = memo("floor-breath", () =>
    Array.from({ length: 9 }, (_, l) =>
      paint(FLOOR, FLOOR, (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        return (l / 8) * 0.34 * (1 - dist(x, y, c.x, c.y) / (R + 1)) ** 0.7
      })
    )
  )
  return levels[Math.max(0, Math.min(8, Math.round(env * 8)))]
}

const MUSIC: Record<string, "checker" | "waves" | "eq" | "fire"> = {
  pop: "checker",
  dance: "checker",
  reggae: "waves",
  dj: "eq",
  rock: "eq",
  robot: "eq",
  folk: "fire",
  country: "fire",
}

/** The music moves that turn the floor into a dance floor. */
export const isMusic = (move: string | null | undefined) => !!move && move in MUSIC

const STEPS = 2 // frames per beat; the cells' own 300 ms fades fill in between

/** The dance floor for a music move at `beats` (beats since it started): locked to the beat. */
export function danceFloor(move: string, beats: number): Frame {
  const style = MUSIC[move] ?? "checker"
  const cycle = memo(`dance:${style}`, () => {
    const frames: Frame[] = []
    for (let beat = 0; beat < 8; beat++)
      for (let s = 0; s < STEPS; s++) {
        const frac = s / STEPS
        const hit = (1 - frac) ** 1.6
        frames.push(
          paint(FLOOR, FLOOR, (x, y, c) => {
            if (!inCircle(x, y, c)) return 0
            if (style === "checker") {
              const on = (Math.floor(x / 2) + Math.floor(y / 2) + beat) % 2 === 0
              return on ? 0.12 + 0.38 * hit : 0.06
            }
            if (style === "waves") {
              const w = 0.5 + 0.5 * Math.sin(2 * Math.PI * (x / 6 - (beat + frac) / 2) + y / 4)
              return 0.08 + 0.38 * w * w * (0.7 + 0.3 * hit)
            }
            if (style === "eq") {
              const column = Math.floor(x / 2)
              const height = (0.25 + 0.75 * hash(column, beat, 17)) * hit * FLOOR
              const fromBottom = FLOOR - 1 - y
              return fromBottom < height ? (fromBottom > height - 2 ? CAP : 0.32) : 0.04
            }
            const d = dist(x, y, c.x, c.y)
            const flicker = 0.55 + 0.45 * hash(x, y, beat * STEPS + s)
            return d < 5 ? CAP * (1 - d / 5.5) * flicker : 0
          })
        )
      }
    return frames
  })
  const i = Math.floor(beats * STEPS)
  return cycle[((i % cycle.length) + cycle.length) % cycle.length]
}
