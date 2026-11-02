import { useEffect, useState } from "react"
import { createPortal } from "react-dom"

import { Matrix } from "@/components/ui/matrix"
import { Caption } from "@/caption"
import { Halo, hasWebGL2 } from "@/halo"
import { isMusic } from "@/matrix/floor"
import { Panel } from "@/matrix/panel"
import { emblemShow, floorShow, gauntletShow, mouthShow, type Moment } from "@/matrix/show"
import { SlotPortal, SLOTS, slotBox, useSlotRoots, useSlotStates, type SlotName } from "@/matrix/slots"
import { readScene, useDarkClass, usePageVisible, useReducedMotion, useStageInfo, WORKING, type StageInfo } from "@/emrys"

/** How long the boot show runs on a freshly loaded page (docs/design/EMRYS_MATRIX.md §5). */
const BOOT_MS = 2200
const CALM: Record<string, string> = {
  idle: "Ready",
  greeting: "Saying hello",
  listening: "Listening",
  waiting: "Waiting on your OK",
  done: "Done",
  failed: "Something went wrong",
  sleeping: "Asleep",
}

const words = (info: StageInfo) => (info.talking ? "Speaking" : (WORKING[info.state] ?? CALM[info.state] ?? "Ready"))

/**
 * Every Matrix takes its lit colour from the first Matrix's gradient on the page (they share the ids), and a gradient
 * inside a hidden slot may not paint. This one stays first in the document and always rendered, invisibly, so the
 * shared gradient is always there and always in the stage's mood colour.
 */
function SharedGradient() {
  return (
    <div aria-hidden="true" className="emrys-matrix pointer-events-none absolute top-0 left-0 size-px overflow-hidden opacity-0">
      <Matrix rows={1} cols={1} pattern={[[0]]} ariaLabel="" />
    </div>
  )
}

/** The band's 7×7 glyph, beside Emrys on a squeezed stage. */
function BandGlyph({ info, moment, paused }: { info: StageInfo; moment: Moment; paused: boolean }) {
  const glyph = Math.round(Math.min(64, Math.max(28, info.height * 0.5)))
  const gap = glyph >= 42 ? 2 : 1
  const size = (glyph + gap) / 7 - gap
  const x = (info.anchor?.x ?? info.width / 2) + Math.max(54, info.height * 0.62)
  const left = Math.min(info.width - glyph - 12, x)
  return (
    <div
      data-emrys="band"
      data-glyph={moment.talking ? "speaking" : moment.state}
      className="emrys-matrix absolute"
      style={{ left, top: (info.height - glyph) / 2, width: glyph, height: glyph }}
    >
      <Panel rows={7} cols={7} size={size} gap={gap} show={emblemShow(moment, 7)} wipes paused={paused} label={`Emrys: ${words(info)}`} />
    </div>
  )
}

/**
 * The ElevenLabs UI layer on the robot stage: the Orb as a backlight behind Emrys (in `back`, the stage's first child,
 * under its svg), the caption and the band glyph over it (in `front`, the stage's last child), and the Matrix in the
 * robot page's armor and floor slots.
 */
export function StageLayer({ stage, front }: { stage: HTMLElement; front: HTMLElement }) {
  const info = useStageInfo()
  const reduced = useReducedMotion()
  const visible = usePageVisible()
  const roots = useSlotRoots(stage)
  const slotStates = useSlotStates()
  useDarkClass()
  const [booting, setBooting] = useState(() => !reduced && !info.short && !document.hidden)

  useEffect(() => {
    if (!booting) return
    const timer = setTimeout(() => setBooting(false), BOOT_MS)
    return () => clearTimeout(timer)
  }, [booting])

  const scene = readScene()
  const move = scene?.move ?? info.move
  const moment: Moment = {
    state: info.state,
    talking: info.talking,
    move,
    music: isMusic(move),
    reduced,
    lowFx: info.lowFx,
    short: info.short,
    booting: booting && !reduced && !info.short,
  }
  const halo = hasWebGL2() && !info.lowFx && !reduced && visible && !info.short

  const slot = (name: SlotName, show: ReturnType<typeof floorShow>, wipes: boolean, label: string) => {
    const root = roots[name]
    if (!root || !show) return null
    const box = slotBox(name, root)
    const { rows, cols, brightness } = SLOTS[name]
    const paused = !visible || slotStates[name]?.visible === false
    return (
      <SlotPortal key={name} name={name} root={root}>
        <Panel rows={rows} cols={cols} size={box.size} gap={box.gap} show={show} wipes={wipes} paused={paused} label={label} brightness={brightness} />
      </SlotPortal>
    )
  }

  return (
    <>
      <SharedGradient />
      {halo && <Halo info={info} />}
      {slot("floor", floorShow(moment), false, "Emrys's light floor")}
      {slot("chest", emblemShow(moment, 13), true, `Emrys's emblem: ${words(info)}`)}
      {slot("mouth", mouthShow(moment), false, "Emrys's voice")}
      {slot("gauntlet-l", gauntletShow(moment, 0), true, "Emrys's left gauntlet")}
      {slot("gauntlet-r", gauntletShow(moment, 1), true, "Emrys's right gauntlet")}
      {createPortal(
        <>
          <Caption info={info} reduced={reduced} />
          {info.short && <BandGlyph info={info} moment={moment} paused={!visible} />}
        </>,
        front
      )}
    </>
  )
}
