import type { Frame } from "@/components/ui/matrix"

/**
 * What a Matrix plays: frames at an fps, looping or once. A one-shot hands over to `then` when its last frame has
 * shown. `key` is its identity: a panel plays its transition only when the key changes. Frames are built once and
 * cached (the component restarts an animation whenever the frames array changes).
 */
export type Program = {
  key: string
  frames: Frame[]
  fps: number
  loop: boolean
  then?: Program
}

/** Real time the live shows read, sampled by the ticker (see ticker.ts). */
export type Tick = {
  now: number
  /** The speech envelope, 0..1: the real output level when the app sends one, else the visemes' openness. */
  env: number
  /** The current viseme and its weight; weight 0 between letters. */
  viseme: string
  weight: number
  talking: boolean
  /** The real input level (the user's voice activity), null when the app sends none. */
  input: number | null
  /** Beats since the music started, from the page's beat when it gives one. */
  beats: number
}

/** A show driven by real time: speech, levels or the music's beat. `pattern` is called on every tick. */
export type Live = {
  key: string
  live: true
  pattern: (tick: Tick) => Frame
}

export type Show = Program | Live

export const isLive = (show: Show): show is Live => "live" in show

export const still = (key: string, frame: Frame): Program => ({ key, frames: [frame], fps: 1, loop: true })
export const anim = (key: string, frames: Frame[], fps: number): Program => ({ key, frames, fps, loop: true })
export const once = (key: string, frames: Frame[], fps: number, then: Program): Program => ({
  key,
  frames,
  fps,
  loop: false,
  then,
})

const cache = new Map<string, unknown>()
/** Builds a value once per key. */
export function memo<T>(key: string, build: () => T): T {
  if (!cache.has(key)) cache.set(key, build())
  return cache.get(key) as T
}
