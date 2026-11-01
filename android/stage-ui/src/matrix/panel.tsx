import { memo, useCallback, useEffect, useRef, useState } from "react"

import { Matrix, type Frame } from "@/components/ui/matrix"

import type { Program, Show } from "./program"
import { nth, wipe } from "./raster"
import { currentTick, useLivePattern } from "./ticker"

/** The transition between two glyphs: a 180 ms column wipe. */
const WIPE_MS = 180
const WIPE_STEPS = 5

type Props = {
  rows: number
  cols: number
  size: number
  gap: number
  show: Show
  /** Wipe into a new show (glyph panels); the floor's shows open with their own ring instead. */
  wipes: boolean
  /** Hold still (page hidden, slot facing away). */
  paused: boolean
  label: string
  /** The Matrix's brightness multiplier. */
  brightness: number
}

/**
 * One ElevenLabs Matrix playing a show. A program plays through the component's own frame loop (frames built once);
 * a one-shot hands over to what follows it; a live show's pattern comes from the ticker. `playing` is the program on
 * screen, or null while the live show itself is.
 */
export const Panel = memo(function Panel({ rows, cols, size, gap, show, wipes, paused, label, brightness }: Props) {
  const shown = useRef<Frame | null>(null)
  const current = useRef(show)
  const [playing, setPlaying] = useState<Program | null>(show.kind === "program" ? show : null)
  const [playingFor, setPlayingFor] = useState(show.key)
  const livePattern = useLivePattern(show.kind === "live" ? show : null, !paused)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    current.current = show
    if (playingFor === show.key) return
    setPlayingFor(show.key)
    if (timer.current) clearTimeout(timer.current)
    const from = shown.current
    if (!wipes || !from || paused) {
      setPlaying(show.kind === "program" ? show : null)
      return
    }
    const to = show.kind === "live" ? show.pattern(currentTick()) : nth(show.frames, 0)
    const into: Program = { kind: "program", key: `wipe>${show.key}`, frames: wipe(from, to, WIPE_STEPS), fps: (WIPE_STEPS * 1000) / WIPE_MS, loop: false }
    setPlaying(show.kind === "program" ? { ...into, then: show } : into)
  }, [show, playingFor, wipes, paused])

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    []
  )

  // A one-shot hands over after its last frame has had its time, outside the component's own state update.
  const handOver = useCallback((program: Program) => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setPlaying((now) => {
        if (now !== program) return now
        if (program.then) return program.then
        return current.current.kind === "live" ? null : now
      })
    }, 1000 / program.fps)
  }, [])

  const onFrame = useCallback(
    (index: number) => {
      if (!playing) return
      shown.current = playing.frames[index] ?? shown.current
      if (!playing.loop && index === playing.frames.length - 1) handOver(playing)
    },
    [playing, handOver]
  )

  // A still (one frame) or a live pattern goes in as `pattern`; an animation as `frames`.
  const still = !playing ? livePattern : playing.frames.length === 1 ? nth(playing.frames, 0) : null
  const animated = playing && playing.frames.length > 1 ? playing.frames : null

  // Track what a new program opens with; later frames are tracked by onFrame. A one-frame one-shot hands over at once.
  useEffect(() => {
    if (still) shown.current = still
    else if (animated) shown.current = nth(animated, 0)
    if (playing && !playing.loop && playing.frames.length === 1) handOver(playing)
  }, [still, animated, playing, handOver])

  return (
    <Matrix
      rows={rows}
      cols={cols}
      size={size}
      gap={gap}
      {...(still ? { pattern: still } : animated ? { frames: animated } : {})}
      fps={playing?.fps ?? 12}
      loop={playing?.loop ?? true}
      autoplay={!paused}
      onFrame={onFrame}
      ariaLabel={label}
      brightness={brightness}
    />
  )
})
