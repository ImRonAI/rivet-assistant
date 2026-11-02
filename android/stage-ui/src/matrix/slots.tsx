import { useEffect, useLayoutEffect, useState, type ReactNode } from "react"
import { createPortal } from "react-dom"

import { readSlots, type SlotState } from "@/emrys"

export type SlotName = "chest" | "mouth" | "gauntlet-l" | "gauntlet-r" | "floor"
type Slot = { id: string; rows: number; cols: number; width: number; height: number; gap: number; round: boolean; brightness: number }

/**
 * The robot page's slots (docs/design/EMRYS_MATRIX.md §3), each a `.emrys-slot-root` with a fixed local box.
 * `brightness` is the Matrix's own multiplier: the floor's quiet patterns are lifted past the glow threshold so
 * the light floor reads as the stage's second visual after Emrys.
 */
export const SLOTS: Readonly<Record<SlotName, Slot>> = {
  chest: { id: "slot-chest", rows: 13, cols: 13, width: 130, height: 130, gap: 2, round: true, brightness: 1.3 },
  mouth: { id: "slot-mouth", rows: 3, cols: 15, width: 150, height: 30, gap: 0, round: false, brightness: 1.25 },
  "gauntlet-l": { id: "slot-gauntlet-l", rows: 3, cols: 11, width: 110, height: 30, gap: 0, round: false, brightness: 1.25 },
  "gauntlet-r": { id: "slot-gauntlet-r", rows: 3, cols: 11, width: 110, height: 30, gap: 0, round: false, brightness: 1.25 },
  floor: { id: "slot-floor", rows: 17, cols: 17, width: 420, height: 420, gap: 4, round: true, brightness: 2.4 },
}

type Roots = Record<SlotName, HTMLElement | null>

function find(stage: HTMLElement): Roots {
  const root = (name: SlotName) => {
    const found = stage.querySelector(`#${SLOTS[name].id} .emrys-slot-root`)
    return found instanceof HTMLElement ? found : null
  }
  return { chest: root("chest"), mouth: root("mouth"), "gauntlet-l": root("gauntlet-l"), "gauntlet-r": root("gauntlet-r"), floor: root("floor") }
}

const same = (a: Roots, b: Roots) =>
  a.chest === b.chest && a.mouth === b.mouth && a["gauntlet-l"] === b["gauntlet-l"] && a["gauntlet-r"] === b["gauntlet-r"] && a.floor === b.floor

/** The slot roots the robot page has drawn, found again whenever the stage's children change (they may come later). */
export function useSlotRoots(stage: HTMLElement): Roots {
  const [roots, setRoots] = useState<Roots>(() => find(stage))
  useEffect(() => {
    let pending = 0
    const look = () => {
      pending = 0
      const next = find(stage)
      setRoots((now) => (same(now, next) ? now : next))
    }
    const observer = new MutationObserver(() => {
      if (!pending) pending = requestAnimationFrame(look)
    })
    observer.observe(stage, { childList: true, subtree: true })
    pending = requestAnimationFrame(look)
    return () => {
      observer.disconnect()
      cancelAnimationFrame(pending)
    }
  }, [stage])
  return roots
}

/** The page's view of each slot (visible, facing), polled; empty while the page doesn't report it. */
export function useSlotStates(): Partial<Record<string, SlotState>> {
  const [states, setStates] = useState(readSlots)
  useEffect(() => {
    const timer = setInterval(() => {
      const next = readSlots()
      setStates((now) => (JSON.stringify(now) === JSON.stringify(next) ? now : next))
    }, 500)
    return () => clearInterval(timer)
  }, [])
  return states
}

/** The cell size that makes a grid fill its box exactly: cols × (size + gap) − gap = width. */
export function cellSize(cols: number, width: number, gap: number): number {
  return (width + gap) / cols - gap
}

/**
 * Mounts a Matrix into a slot root and marks the root `data-filled="1"` while it is there, so the robot skips its own
 * drawing of that part; unmounting clears the mark and the robot draws its native look again.
 */
export function SlotPortal({ name, root, children }: { name: SlotName; root: HTMLElement; children: ReactNode }) {
  useLayoutEffect(() => {
    root.setAttribute("data-filled", "1")
    return () => root.removeAttribute("data-filled")
  }, [root])
  const slot = SLOTS[name]
  const width = root.clientWidth || slot.width
  const height = root.clientHeight || slot.height
  return createPortal(
    <div
      data-emrys={`slot-${name}`}
      {...(name === "floor" ? { "aria-hidden": true } : {})}
      className="emrys-matrix pointer-events-none"
      style={slot.round ? { width, height, clipPath: "circle(50%)" } : { width, height }}
    >
      {children}
    </div>,
    root
  )
}

/** A slot's box in CSS px, as the page laid it out (the contract's size until it has). */
export function slotBox(name: SlotName, root: HTMLElement) {
  const slot = SLOTS[name]
  const width = root.clientWidth || slot.width
  const height = root.clientHeight || slot.height
  return { width, height, size: cellSize(slot.cols, width, slot.gap), gap: slot.gap }
}
