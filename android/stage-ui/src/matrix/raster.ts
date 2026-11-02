import type { Frame } from "@/components/ui/matrix"

/** A brightness field in cell units: (x, y) is a cell centre, (0, 0) the top-left cell, (c, c) the centre. */
export type Field = (x: number, y: number, c: { x: number; y: number }) => number

const clamp = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v)
/** Rounds to 1/100, so repeated frames are equal and the 300 ms cell transitions don't chase noise. */
const q = (v: number) => Math.round(clamp(v) * 100) / 100

export function paint(rows: number, cols: number, field: Field): Frame {
  const c = { x: (cols - 1) / 2, y: (rows - 1) / 2 }
  const frame: Frame = []
  for (let y = 0; y < rows; y++) {
    const row: number[] = []
    for (let x = 0; x < cols; x++) row.push(q(field(x, y, c)))
    frame.push(row)
  }
  return frame
}

export const blank = (rows: number, cols: number): Frame => paint(rows, cols, () => 0)

export function map(frame: Frame, f: (v: number, x: number, y: number) => number): Frame {
  return frame.map((row, y) => row.map((v, x) => q(f(v, x, y))))
}

export const scale = (frame: Frame, k: number): Frame => map(frame, (v) => v * k)

export function lighten(a: Frame, b: Frame): Frame {
  return a.map((row, y) => row.map((v, x) => q(Math.max(v, b[y]?.[x] ?? 0))))
}

/** 1 inside, falling to 0 over `soft` cells past the edge (d is a signed or unsigned distance). */
export const edge = (d: number, soft = 1) => clamp(1 - d / soft)
/** A band of half-width `w` around distance `d === r`, soft over one cell. */
export const band = (d: number, r: number, w = 0.5, soft = 1) => edge(Math.abs(d - r) - w, soft)

export const dist = (x: number, y: number, cx: number, cy: number) => Math.hypot(x - cx, y - cy)

export function segment(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const vx = bx - ax, vy = by - ay
  const t = clamp(((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy || 1))
  return Math.hypot(px - (ax + vx * t), py - (ay + vy * t))
}

/** Distance to a polyline, drawn only up to `progress` (0..1) of its length, for strokes that write themselves on. */
export function stroke(points: [number, number][], progress = 1) {
  const segments = points.slice(1).map((b, i) => {
    const a = points[i] ?? b
    return { a, b, length: Math.hypot(b[0] - a[0], b[1] - a[1]) }
  })
  const total = segments.reduce((sum, s) => sum + s.length, 0)
  return (x: number, y: number) => {
    let left = total * clamp(progress)
    let best = Infinity
    for (const { a, b, length } of segments) {
      if (left <= 0) break
      const k = Math.min(1, left / length)
      best = Math.min(best, segment(x, y, a[0], a[1], a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k))
      left -= length
    }
    return best
  }
}

/** The angle of (x, y) around the centre, 0 at the top, clockwise, in turns (0..1). */
export const turn = (x: number, y: number, c: { x: number; y: number }) =>
  (Math.atan2(x - c.x, c.y - y) / (2 * Math.PI) + 1) % 1

/** Angular distance in turns (0..0.5). */
export const arcGap = (a: number, b: number) => {
  const d = Math.abs(a - b) % 1
  return d > 0.5 ? 1 - d : d
}

/** Frames of a looping animation: `field(t)` sampled at `fps` over `seconds`. */
export function loop(rows: number, cols: number, seconds: number, fps: number, at: (t: number) => Field): Frame[] {
  const n = Math.max(1, Math.round(seconds * fps))
  return Array.from({ length: n }, (_, i) => paint(rows, cols, at((i / n) * seconds)))
}

/** Cells outside the inscribed circle stay dark (circular emblems and the floor). */
export const inCircle = (x: number, y: number, c: { x: number; y: number }) => dist(x, y, c.x, c.y) <= c.x + 0.35

/** A deterministic 0..1 hash for a cell and a step, so shows look the same on every run. */
export function hash(a: number, b: number, s = 0): number {
  let h = (a * 374761393 + b * 668265263 + s * 2147483647) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

/** The transition between two glyphs: a column wipe, left to right, in `steps` frames. */
export function wipe(from: Frame, to: Frame, steps: number): Frame[] {
  const cols = to[0]?.length ?? 0
  return Array.from({ length: steps }, (_, i) => {
    const front = ((i + 1) / steps) * (cols + 1) - 1
    return to.map((row, y) =>
      row.map((v, x) => {
        const old = from[y]?.[x] ?? 0
        if (x < front - 0.5) return v
        if (x < front + 0.5) return Math.max(v, old, 0.4) // the wipe's leading column, below the glow
        return old
      })
    )
  })
}

/** The frame at index `i` of a list that has frames, held to its ends. */
export function nth(frames: readonly Frame[], i: number): Frame {
  const frame = frames[Math.max(0, Math.min(frames.length - 1, i))]
  if (!frame) throw new Error("an empty frame list")
  return frame
}
