import { beatVu, bootGauntlet, gauntletFor, mouthShape, restingMouth } from "./armor"
import { bootFloor, danceFloor, floorBreath, floorFor } from "./floor"
import { bootChest, downbeat, glyph, radialVu, restingGlyph } from "./glyphs"
import { memo, once, still, type Live, type Show, type Tick } from "./program"
import { map } from "./raster"

/**
 * Which show each slot plays (docs/design/EMRYS_MATRIX.md §4–6): the boot show first, then speech, then the music's
 * dance floor, then the state's own glyph. Reduced motion keeps every slot on its resting glyph; lowFx keeps the
 * chest still and drops the floor and gauntlets; the squeezed stage has only the band glyph (and a still chest).
 */
export type Moment = {
  state: string
  talking: boolean
  move: string | null
  music: boolean
  reduced: boolean
  lowFx: boolean
  short: boolean
  booting: boolean
}

const live = (key: string, pattern: (tick: Tick) => ReturnType<Live["pattern"]>): Live =>
  memo(`live:${key}`, () => ({ key, live: true as const, pattern }))

/** The listening glyph, its rings brightening with the user's voice when the app sends a real input level. */
function listeningLive(n: number): Live {
  const loop = glyph("listening", n)
  return live(`listening-live${n}`, (tick) => {
    const i = Math.floor((tick.now / 1000) * loop.fps) % loop.frames.length
    if (tick.input === null) return loop.frames[i]
    const k = Math.round((0.45 + 0.75 * tick.input) * 10) / 10
    return memo(`listening-lit${n}:${i}:${k}`, () => map(loop.frames[i], (v) => v * k))
  })
}

export function emblemShow(m: Moment, n: 13 | 7): Show {
  if (m.reduced || m.lowFx || (m.short && n === 13)) return restingGlyph(m.state, n)
  if (m.booting && n === 13) return memo("boot-chest", () => bootChest(13, glyph("idle", 13)))
  if (m.talking) return live(`vu${n}`, (tick) => radialVu(n, tick.env))
  if (m.music) return live(`downbeat${n}`, (tick) => downbeat(n, tick.beats))
  if (m.state === "listening") return listeningLive(n)
  return glyph(m.state, n)
}

/** null: no floor (reduced motion, lowFx, the squeezed stage). */
export function floorShow(m: Moment): Show | null {
  if (m.reduced || m.lowFx || m.short) return null
  if (m.booting) return memo("boot-floor", () => bootFloor(floorFor("idle")))
  if (m.talking) return live("floor-breath", (tick) => floorBreath(tick.env))
  if (m.music && m.move) {
    const move = m.move
    return live(`dance:${move}`, (tick) => danceFloor(move, tick.beats))
  }
  return floorFor(m.state)
}

/** null: no gauntlets (lowFx, the squeezed stage); reduced motion keeps a faint still line. */
export function gauntletShow(m: Moment, side: 0 | 1): Show | null {
  if (m.lowFx || m.short) return null
  if (m.reduced) return memo("gauntlet-still", () => still("gauntlet-still", gauntletFor("idle", 0).frames[8]))
  if (m.booting) return memo(`boot-gauntlet${side}`, () => bootGauntlet(gauntletFor("idle", side)))
  if (m.music) return live("beat-vu", (tick) => beatVu(tick.beats))
  return gauntletFor(m.state, side)
}

/** null: no mouth on the squeezed stage. Speech shows the visemes; otherwise the state's resting mouth. */
export function mouthShow(m: Moment): Show | null {
  if (m.short) return null
  const resting = memo(`mouth-rest:${m.state}`, () => still(`mouth-rest:${m.state}`, restingMouth(m.state)))
  if (m.reduced) return resting
  if (m.booting)
    return memo("boot-mouth", () =>
      once(
        "boot-mouth",
        [...Array.from({ length: 18 }, () => mouthShape("sil")), mouthShape("blink"), mouthShape("blink"), mouthShape("sil")],
        10,
        still("mouth-rest:idle", restingMouth("idle"))
      )
    )
  if (m.talking) return live("mouth-talk", (tick) => mouthShape(tick.weight > 0 ? tick.viseme : "sil", tick.weight))
  return resting
}

