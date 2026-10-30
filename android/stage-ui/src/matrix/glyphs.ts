import type { Frame } from "@/components/ui/matrix"
import sigilSource from "../../../../docs/design/emrys-sigil.json"

import { anim, memo, once, still, type Program } from "./program"
import {
  band,
  dist,
  edge,
  hash,
  inCircle,
  loop,
  paint,
  scale,
  segment,
  stroke,
  turn,
  type Field,
} from "./raster"

/**
 * The light language on the chest emblem (13×13) and the mini band (7×7): the Emrys sigil and one glyph per state,
 * after docs/design/EMRYS_MATRIX.md §4. Geometry is in cell units so each glyph draws at both sizes.
 */

/** The seal: an E in a halo ring of dots, its middle bar ending in a half-lit spark (docs/design/emrys-sigil.json). */
export const SIGIL_13: Frame = sigilSource.frame
/** The same seal for the band's 7×7 grid. */
export const SIGIL_7: Frame = [
  [0, 0, 0.45, 0.45, 0.45, 0, 0],
  [0, 0.45, 1, 1, 1, 0.45, 0],
  [0.45, 0, 1, 0, 0, 0, 0.45],
  [0.45, 0, 1, 1, 0.6, 0, 0.45],
  [0.45, 0, 1, 0, 0, 0, 0.45],
  [0, 0.45, 1, 1, 1, 0.45, 0],
  [0, 0, 0.45, 0.45, 0.45, 0, 0],
]
export const sigil = (n: number) => (n === 13 ? SIGIL_13 : SIGIL_7)

/** Shapes scale from the 13-cell design to the grid in use. */
const at = (n: number) => (v: number) => (v * (n - 1)) / 12
const ringRadius = (n: number) => (n - 1) / 2 - 0.15

function onSigil(n: number, f: (v: number, x: number, y: number) => number): Field {
  const s = sigil(n)
  return (x, y) => (s[y][x] ? f(s[y][x], x, y) : 0)
}

/** idle: the sigil breathing (0.55 → 0.9 over 4 s) with a slow glint crossing it every 12 s. */
function idle(n: number): Program {
  return memo(`idle${n}`, () =>
    anim(
      `idle${n}`,
      loop(n, n, 12, 6, (t) => {
        const b = 0.725 - 0.175 * Math.cos((2 * Math.PI * t) / 4)
        const front = t > 9 ? -2 + ((t - 9) / 1.4) * (2 * n + 2) : -99
        return onSigil(n, (v, x, y) => v * b + 0.35 * band(x + y, front, 0.4, 1.6))
      }),
      6
    )
  )
}

/** Sparks converging from around the emblem into the sigil over `seconds`. */
function converge(n: number, seconds: number, fps: number): Frame[] {
  const s = sigil(n)
  const sparks: { tx: number; ty: number; sx: number; sy: number; v: number; delay: number }[] = []
  s.forEach((row, ty) =>
    row.forEach((v, tx) => {
      if (!v) return
      const a = hash(tx, ty, 7) * Math.PI * 2
      const r = (n / 2) * (0.7 + 0.6 * hash(tx, ty, 11))
      sparks.push({ tx, ty, sx: tx + Math.cos(a) * r, sy: ty + Math.sin(a) * r, v, delay: hash(tx, ty, 3) * 0.3 })
    })
  )
  return loop(n, n, seconds, fps, (t) => {
    const p = sparks.map((k) => {
      const u = Math.min(1, Math.max(0, (t / seconds - k.delay) / (1 - k.delay)))
      const e = 1 - (1 - u) ** 3
      return { x: k.sx + (k.tx - k.sx) * e, y: k.sy + (k.ty - k.sy) * e, v: k.v * (0.35 + 0.65 * u) }
    })
    return (x, y) => {
      let best = 0
      for (const k of p) best = Math.max(best, k.v * edge(dist(x, y, k.x, k.y) - 0.15, 0.9))
      return best
    }
  }).concat([s])
}

/** greeting: the sigil assembles from scattered sparks, then idles. */
function greeting(n: number): Program {
  return memo(`greeting${n}`, () => once(`greeting${n}`, converge(n, 1.2, 12), 12, idle(n)))
}

/** The boot show's chest: dark, then sparks converge into the sigil and hold. */
export function bootChest(n: number, then: Program): Program {
  const dark = Array.from({ length: 4 }, () => scale(sigil(n), 0))
  const hold = Array.from({ length: 6 }, () => sigil(n))
  return once(`boot${n}`, [...dark, ...converge(n, 1.2, 10), ...hold], 10, then)
}

/** listening: sonar rings travelling out from the centre (a live mic). */
function listening(n: number): Program {
  return memo(`listening${n}`, () => {
    const R = ringRadius(n) + 1
    return anim(
      `listening${n}`,
      loop(n, n, 1.6, 10, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const d = dist(x, y, c.x, c.y)
        let v = d < 0.8 ? 0.6 : 0
        for (const k of [0, 0.5]) {
          const r = ((t / 1.6 + k) % 1) * R
          v = Math.max(v, band(d, r, 0.3, 0.9) * (0.95 - (0.55 * r) / R))
        }
        return v
      }),
      10
    )
  })
}

/** thinking / searching: a comet orbiting the halo ring with a fading tail, inner dots twinkling into a constellation. */
function thinking(n: number): Program {
  return memo(`thinking${n}`, () => {
    const R = ringRadius(n)
    return anim(
      `thinking${n}`,
      loop(n, n, 4.8, 12, (t) => {
        const head = (t / 2.4) % 1
        return (x, y, c) => {
          if (!inCircle(x, y, c)) return 0
          const d = dist(x, y, c.x, c.y)
          const behind = (head - turn(x, y, c) + 1) % 1
          const tail = behind < 0.32 ? (1 - behind / 0.32) ** 1.6 : 0
          const ring = band(d, R, 0.4, 0.9) * Math.max(0.12, 0.95 * tail)
          const star = d < R - 1.4 && hash(x, y, 5) < 0.2 ? 0.45 * Math.max(0, Math.sin(2 * Math.PI * (t / 1.6 + hash(x, y, 9)))) ** 3 : 0
          return Math.max(ring, star)
        }
      }),
      12
    )
  })
}

/** working: a weave, two sets of diagonal threads crossing like a loom. */
function working(n: number): Program {
  return memo(`working${n}`, () =>
    anim(
      `working${n}`,
      loop(n, n, 2, 8, (t) => (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const a = band((((x + y - 2 * t) % 4) + 4) % 4, 0, 0.3, 0.8) + band((((x + y - 2 * t) % 4) + 4) % 4, 4, 0.3, 0.8)
        const b = band((((x - y + 2 * t) % 4) + 4) % 4, 0, 0.3, 0.8) + band((((x - y + 2 * t) % 4) + 4) % 4, 4, 0.3, 0.8)
        return a && b ? 0.7 : 0.26 * Math.max(a, b)
      }),
      8
    )
  )
}

/** texting: three typing dots that bounce, then resolve into a speech bubble. */
function texting(n: number): Program {
  return memo(`texting${n}`, () => {
    const p = at(n)
    const dots = [p(3), p(6), p(9)]
    const bubble = stroke([
      [p(2), p(2.5)], [p(10), p(2.5)], [p(10.5), p(3)], [p(10.5), p(7.5)], [p(10), p(8)],
      [p(5.5), p(8)], [p(3), p(10.5)], [p(3.5), p(8)], [p(2), p(8)], [p(1.5), p(7.5)], [p(1.5), p(3)], [p(2), p(2.5)],
    ])
    const r = n === 13 ? 0.55 : 0.3
    return anim(
      `texting${n}`,
      loop(n, n, 4, 10, (t) => (x, y, c) => {
        if (t < 2.4) {
          let v = 0
          dots.forEach((dx, i) => {
            const lift = Math.max(0, Math.sin(2 * Math.PI * (t / 0.9 - i * 0.18))) * (n === 13 ? 1.4 : 0.8)
            v = Math.max(v, 0.9 * edge(dist(x, y, dx, c.y - lift) - r, 0.8))
          })
          return v
        }
        const fade = Math.min(1, (t - 2.4) / 0.3)
        const inside = dots.reduce((v, dx) => Math.max(v, 0.4 * edge(dist(x, y, dx, p(5.2)) - r, 0.8)), 0)
        return fade * Math.max(0.85 * edge(bubble(x, y) - 0.25, 0.8), inside)
      }),
      10
    )
  })
}

/** emailing: an envelope drawn on dot by dot, then its flap closing. */
function emailing(n: number): Program {
  return memo(`emailing${n}`, () => {
    const p = at(n)
    const box: [number, number][] = [[p(1.2), p(3)], [p(10.8), p(3)], [p(10.8), p(10)], [p(1.2), p(10)], [p(1.2), p(3)]]
    return anim(
      `emailing${n}`,
      loop(n, n, 3.4, 10, (t) => {
        const outline = stroke(box, Math.min(1, t / 1.4))
        const apex = t < 1.4 ? p(0.4) : p(0.4) + (p(7) - p(0.4)) * Math.min(1, (t - 1.4) / 0.8)
        const flap = t < 1.4 ? null : stroke([[p(1.2), p(3)], [p(6), apex], [p(10.8), p(3)]])
        return (x, y) => Math.max(0.85 * edge(outline(x, y) - 0.25, 0.8), flap ? 0.7 * edge(flap(x, y) - 0.25, 0.8) : 0)
      }),
      10
    )
  })
}

/** calling: a handset with signal arcs pulsing outward. */
function calling(n: number): Program {
  return memo(`calling${n}`, () => {
    const p = at(n)
    const handset = stroke([[p(3.2), p(2.6)], [p(2.4), p(5)], [p(3.4), p(7.6)], [p(5.4), p(9.6)], [p(8), p(10.6)], [p(10), p(10)]])
    const ends: [number, number][] = [[p(3.4), p(2.4)], [p(10.2), p(9.8)]]
    const ox = p(4), oy = p(8.6)
    return anim(
      `calling${n}`,
      loop(n, n, 1.8, 10, (t) => (x, y) => {
        let v = 0.85 * edge(handset(x, y) - 0.45, 0.8)
        for (const [ex, ey] of ends) v = Math.max(v, 0.85 * edge(dist(x, y, ex, ey) - (n === 13 ? 0.9 : 0.5), 0.8))
        const a = Math.atan2(x - ox, oy - y) / (2 * Math.PI)
        if (a > 0.02 && a < 0.23) {
          const d = dist(x, y, ox, oy)
          ;[p(4.6), p(6.8)].forEach((r, k) => {
            const pulse = Math.max(0, Math.sin(2 * Math.PI * (t / 1.8 - k * 0.22)))
            v = Math.max(v, band(d, r, 0.3, 0.8) * (0.25 + 0.6 * pulse))
          })
        }
        return v
      }),
      10
    )
  })
}

/** video: an aperture iris opening and closing. */
function video(n: number): Program {
  return memo(`video${n}`, () => {
    const R = ringRadius(n)
    return anim(
      `video${n}`,
      loop(n, n, 3, 10, (t) => {
        const open = R * (0.25 + 0.55 * (0.5 - 0.5 * Math.cos((2 * Math.PI * t) / 3)))
        return (x, y, c) => {
          if (!inCircle(x, y, c)) return 0
          const d = dist(x, y, c.x, c.y)
          if (d < open - 0.3) return d < 0.6 ? 0.3 : 0
          const blade = ((turn(x, y, c) + d * 0.05) * 6) % 1 < 0.5 ? 0.62 : 0.32
          return Math.max(band(d, R, 0.35, 0.8) * 0.75, d <= R ? blade : 0)
        }
      }),
      10
    )
  })
}

/** waiting: a keyhole that breathes (waiting on the user's OK). */
function waiting(n: number): Program {
  return memo(`waiting${n}`, () => {
    const p = at(n)
    const R = ringRadius(n)
    return anim(
      `waiting${n}`,
      loop(n, n, 2.4, 10, (t) => {
        const b = 0.55 + 0.4 * (0.5 - 0.5 * Math.cos((2 * Math.PI * t) / 2.4))
        return (x, y, c) => {
          const head = edge(dist(x, y, c.x, p(4.6)) - p(2), 0.8)
          const w = p(0.7) + ((y - p(5.5)) / p(4.5)) * p(0.8)
          const stem = y > p(5.5) && y < p(10.2) ? edge(Math.abs(x - c.x) - w, 0.8) : 0
          return Math.max(b * Math.max(head, stem), 0.22 * band(dist(x, y, c.x, c.y), R, 0.35, 0.8))
        }
      }),
      10
    )
  })
}

/** done: a check drawn stroke by stroke, held, then back to the sigil. */
function done(n: number): Program {
  return memo(`done${n}`, () => {
    const p = at(n)
    const check: [number, number][] = [[p(2.4), p(6.6)], [p(5), p(9.2)], [p(10.6), p(3.4)]]
    const frames = loop(n, n, 2, 12, (t) => {
      const s = stroke(check, Math.min(1, t / 0.8))
      return (x, y) => 0.95 * edge(s(x, y) - 0.35, 0.8)
    })
    return once(`done${n}`, frames, 12, idle(n))
  })
}

/** failed: a soft × that fades, then a dimmed sigil. */
function failed(n: number): Program {
  return memo(`failed${n}`, () => {
    const p = at(n)
    const frames = loop(n, n, 2.4, 10, (t) => {
      const k = t < 0.6 ? t / 0.6 : t < 1.6 ? 1 : Math.max(0, 1 - (t - 1.6) / 0.8)
      return (x, y) =>
        0.7 * k * edge(Math.min(segment(x, y, p(3.5), p(3.5), p(9.5), p(9.5)), segment(x, y, p(9.5), p(3.5), p(3.5), p(9.5))) - 0.3, 0.9)
    })
    const dim = anim(
      `failed-dim${n}`,
      loop(n, n, 6, 4, (t) => onSigil(n, (v) => v * (0.35 + 0.05 * Math.sin((2 * Math.PI * t) / 6)))),
      4
    )
    return once(`failed${n}`, frames, 10, dim)
  })
}

/** sleeping: the sigil at 25 % with a single z drifting up every few seconds, at a low frame rate. */
function sleeping(n: number): Program {
  return memo(`sleeping${n}`, () => {
    const p = at(n)
    const s = n === 13 ? 1.1 : 0.8
    return anim(
      `sleeping${n}`,
      loop(n, n, 4, 4, (t) => {
        const u = Math.min(1, t / 3)
        const zx = p(8) + p(1.5) * u, zy = p(8) - p(5.5) * u
        const fade = t < 3 ? 0.5 * Math.sin(Math.PI * u) : 0
        const z = stroke([[zx - s, zy - s], [zx + s, zy - s], [zx - s, zy + s], [zx + s, zy + s]])
        return (x, y) => Math.max((sigil(n)[y][x] || 0) * 0.25, fade * edge(z(x, y) - 0.2, 0.8))
      }),
      4
    )
  })
}

const PROGRAMS: Record<string, (n: number) => Program> = {
  idle,
  greeting,
  listening,
  thinking,
  searching: thinking,
  working,
  texting,
  emailing,
  calling,
  video,
  waiting,
  done,
  failed,
  sleeping,
}

/** The glyph program for a state on an n×n emblem (13 on the chest, 7 on the band). */
export const glyph = (state: string, n: number): Program => (PROGRAMS[state] ?? idle)(n)

/** Each state's resting glyph, a still: for reduced motion and lowFx. */
export function restingGlyph(state: string, n: number): Program {
  return memo(`rest:${state}${n}`, () => {
    const program = glyph(state, n)
    const pick: Record<string, number> = { texting: -1, emailing: -1, done: -1, calling: 0, video: 0.5, failed: 0.4 }
    let frame: Frame
    if (state === "idle" || state === "greeting" || !(state in PROGRAMS)) frame = scale(sigil(n), 0.8)
    else if (state === "sleeping") frame = scale(sigil(n), 0.25)
    else {
      const f = program.frames
      const where = pick[state]
      frame = where === undefined ? f[0] : where < 0 ? f[f.length - 1] : f[Math.floor(where * (f.length - 1))]
    }
    return still(`rest:${state}${n}`, frame)
  })
}

/** The chest's radial VU while Emrys speaks: the speech envelope, 0..1, as a disc growing from the centre. */
export function radialVu(n: number, env: number): Frame {
  const levels = memo(`vu${n}`, () =>
    Array.from({ length: 9 }, (_, l) =>
      paint(n, n, (x, y, c) => {
        if (!inCircle(x, y, c)) return 0
        const d = dist(x, y, c.x, c.y)
        const reach = (l / 8) * (ringRadius(n) + 0.6)
        const lit = d <= reach + 0.3 ? 0.92 - (0.42 * d) / (ringRadius(n) + 0.6) : 0
        return Math.max(lit, 0.2 * band(d, ringRadius(n), 0.35, 0.8))
      })
    )
  )
  return levels[Math.max(0, Math.min(8, Math.round(env * 8)))]
}

/** The chest on a music move: the sigil pulsing on each downbeat. */
export function downbeat(n: number, beats: number): Frame {
  const pulses = memo(`downbeat${n}`, () =>
    Array.from({ length: 4 }, (_, i) => scale(sigil(n), 0.5 + 0.45 * (1 - i / 4) ** 2))
  )
  const frac = beats - Math.floor(beats)
  return pulses[Math.min(3, Math.floor(frac * 4))]
}

