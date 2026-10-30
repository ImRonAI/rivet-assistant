import type { Frame } from "@/components/ui/matrix"

/**
 * What a Matrix plays: frames at an fps, looping or once. A one-shot hands over to `then` when its last frame has
 * shown. `key` is its identity: a panel plays its transition only when the key changes. Frames are built once and
 * cached (the component restarts an animation whenever the frames array changes).
 */
export type Program = {
  kind: "program"
  key: string
  frames: Frame[]
  fps: number
  loop: boolean
  then?: Program
}

/** What the live shows read from the ticker (ticker.ts): Emrys's speech as it happens. */
export type Tick = {
  /** The speech envelope, 0..1: the agent's audio level while it speaks, else how open the viseme is. */
  env: number
  /** The viseme on the mouth and its weight; weight 0 between letters. */
  viseme: string
  weight: number
  talking: boolean
}

/** A show driven by speech as it happens; `pattern` gives its frame for each tick. */
export type Live = {
  kind: "live"
  key: string
  pattern: (tick: Tick) => Frame
}

export type Show = Program | Live

export const still = (key: string, frame: Frame): Program => ({ kind: "program", key, frames: [frame], fps: 1, loop: true })
export const anim = (key: string, frames: Frame[], fps: number): Program => ({ kind: "program", key, frames, fps, loop: true })
export const once = (key: string, frames: Frame[], fps: number, then: Program): Program => ({
  kind: "program",
  key,
  frames,
  fps,
  loop: false,
  then,
})

/** A store that builds each value once per key. */
export function cache<T>(): (key: string, build: () => T) => T {
  const values = new Map<string, T>()
  return (key, build) => {
    const known = values.get(key)
    if (known !== undefined) return known
    const value = build()
    values.set(key, value)
    return value
  }
}
