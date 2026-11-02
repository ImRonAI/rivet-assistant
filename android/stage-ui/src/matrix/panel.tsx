import { memo as reactMemo, useCallback, useEffect, useRef, useState } from "react"

import { Matrix, type Frame } from "@/components/ui/matrix"

import { isLive, type Program, type Show } from "./program"
import { wipe } from "./raster"
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
  brightness?: number
}

/**
 * One ElevenLabs Matrix playing a show. A program plays through the component's own frame loop (frames built once);
 * a one-shot hands over to what follows it; a live show's pattern comes from the ticker. `playing` is the program on
 * screen, or null while the live show itself is.
 */
export const Panel = reactMemo(function Panel({ rows, cols, size, gap, show, wipes, paused, label, brightness }: Props) {
  const shown = useRef<Frame | null>(null)
  const current = useRef(show)
  current.current = show
  const [playing, setPlaying] = useState<Program | null>(isLive(show) ? null : show)
  const [playingFor, setPlayingFor] = useState(show.key)
  const livePattern = useLivePattern(isLive(show) ? show : null, !paused)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (playingFor === show.key) return
    setPlayingFor(show.key)
    if (timer.current) clearTimeout(timer.current)
    const from = shown.current
    if (!wipes || !from || paused) {
      setPlaying(isLive(show) ? null : show)
      return
    }
    const to = isLive(show) ? show.pattern(currentTick()) : show.frames[0]
    setPlaying({
      key: `wipe>${show.key}`,
      frames: wipe(from, to, WIPE_STEPS),
      fps: (WIPE_STEPS * 1000) / WIPE_MS,
      loop: false,
      then: isLive(show) ? undefined : show,
    })
  }, [show, playingFor, wipes, paused])

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  // A one-shot hands over after its last frame has had its time, outside the component's own state update.
  const handOver = useCallback((program: Program) => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setPlaying((now) => {
        if (now !== program) return now
        if (program.then) return program.then
        return isLive(current.current) ? null : now
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

  let pattern: Frame | undefined
  let frames: Frame[] | undefined
  if (!playing) pattern = (isLive(show) ? livePattern : shown.current) ?? undefined
  else if (playing.frames.length === 1) pattern = playing.frames[0]
  else frames = playing.frames

  useEffect(() => {
    if (pattern) shown.current = pattern
    else if (frames) shown.current = frames[0]
    if (playing && !playing.loop && playing.frames.length === 1) handOver(playing)
    // Track what a new program opens with; later frames are tracked by onFrame.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pattern, playing])

  return (
    <Matrix
      rows={rows}
      cols={cols}
      size={size}
      gap={gap}
      pattern={pattern}
      frames={frames}
      fps={playing?.fps ?? 12}
      loop={playing?.loop ?? true}
      autoplay={!paused}
      onFrame={onFrame}
      ariaLabel={label}
      brightness={brightness}
    />
  )
})
