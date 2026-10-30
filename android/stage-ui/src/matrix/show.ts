import type { Frame } from "@/components/ui/matrix"

import { bootGauntlet, gauntletFor, mouthShape, restingMouth } from "./armor"
import { bootFloor, floorBreath, floorFor } from "./floor"
import { bootChest, glyph, radialVu, restingGlyph } from "./glyphs"
import { cache, once, still, type Live, type Program, type Show, type Tick } from "./program"
import { nth } from "./raster"

/**
 * Which show each slot plays (docs/design/EMRYS_MATRIX.md §4–6): the boot show first, then speech, then the state's
 * own glyph. Reduced motion keeps every slot on its resting glyph; lowFx keeps the chest still and drops the floor and
 * gauntlets; the squeezed stage has only the band glyph (and a still chest).
 */
export type Moment = {
  state: string
  talking: boolean
  reduced: boolean
  lowFx: boolean
  short: boolean
  booting: boolean
}

const programs = cache<Program>()
const lives = cache<Live>()

const live = (key: string, pattern: (tick: Tick) => Frame): Live =>
  lives(key, () => ({ kind: "live", key, pattern }))

export function emblemShow(m: Moment, n: 13 | 7): Show {
  if (m.reduced || m.lowFx || (m.short && n === 13)) return restingGlyph(m.state, n)
  if (m.booting && n === 13) return programs("boot-chest", () => bootChest(13, glyph("idle", 13)))
  if (m.talking) return live(`vu${n}`, (tick) => radialVu(n, tick.env))
  return glyph(m.state, n)
}

/** null: no floor (reduced motion, lowFx, the squeezed stage). */
export function floorShow(m: Moment): Show | null {
  if (m.reduced || m.lowFx || m.short) return null
  if (m.booting) return programs("boot-floor", () => bootFloor(floorFor("idle")))
  if (m.talking) return live("floor-breath", (tick) => floorBreath(tick.env))
  return floorFor(m.state)
}

/** null: no gauntlets (lowFx, the squeezed stage); reduced motion keeps a faint still line. */
export function gauntletShow(m: Moment, side: 0 | 1): Show | null {
  if (m.lowFx || m.short) return null
  if (m.reduced) return programs("gauntlet-still", () => still("gauntlet-still", nth(gauntletFor("idle", 0).frames, 8)))
  if (m.booting) return programs(`boot-gauntlet${side}`, () => bootGauntlet(gauntletFor("idle", side)))
  return gauntletFor(m.state, side)
}

/** null: no mouth on the squeezed stage. Speech shows the visemes; otherwise the state's resting mouth. */
export function mouthShow(m: Moment): Show | null {
  if (m.short) return null
  const resting = programs(`mouth-rest:${m.state}`, () => still(`mouth-rest:${m.state}`, restingMouth(m.state)))
  if (m.reduced) return resting
  if (m.booting)
    return programs("boot-mouth", () =>
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
