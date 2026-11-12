import { Canvas, useFrame } from "@react-three/fiber"
import { Suspense, lazy, useEffect, useRef, useState, useSyncExternalStore } from "react"
import { createRoot } from "react-dom/client"
import * as THREE from "three"

import { useQuality } from "../cato/quality"
import RenBody, { type RenBodyHandle } from "./RenBody"
import { onTheme, ready, stage, type Focus } from "./bridge"
import "./robot.css"

const Glow = lazy(() => import("./glow"))

/** The canvas background per theme: ren's paper. */
const PAPER = { light: "#ffffff", dark: "#0c0c0c" } as const

/** The camera's field of view, as on catolabs.com. */
const FOV = 32
/** Ren from the floor to the tip of the antenna, and the half width it needs with its arms out, in its own units. */
const HEIGHT = 2.05
const HALF_WIDTH = 0.85

/**
 * How each focus frames Ren: `fill` is the share of the stage's height its body takes, and `centre` where the middle
 * of its body sits, from the top of the stage (0) to the bottom (1).
 */
const FRAMING: Readonly<Record<Focus, { fill: number; centre: number }>> = {
  /** A full-height stage. */
  stage: { fill: 0.8, centre: 0.52 },
  /** A short card or band: closer, on the upper body. */
  card: { fill: 1.25, centre: 0.72 },
  /** The upper part of the stage, with a greeting under its feet. */
  greet: { fill: 0.58, centre: 0.36 },
}

/** How far a drag turns the camera round Ren, and how fast it comes back. */
const MAX_YAW = 0.9
const pointer = { yaw: 0, dragging: false, tapped: false }

const _look = new THREE.Vector3()

function Scene({ animate }: { animate: boolean }) {
  const body = useRef<RenBodyHandle>(null)
  const S = useRef({ seq: -1, pose: "", letter: "", told: false, frames: 0, yaw: 0, distance: 0, lookY: 0 })

  useFrame((state, dt) => {
    const core = body.current?.core
    if (!core) return
    const s = S.current

    /* what the app is doing */
    if (s.seq !== stage.moveSeq) {
      s.seq = stage.moveSeq
      if (core.mode() !== stage.move || !core.isLoop(stage.move)) {
        core.play(stage.move)
        s.pose = stage.move
      }
    }
    if (pointer.tapped) {
      pointer.tapped = false
      if (core.mode() === "idle") core.play("wave")
    }

    /* speech: the app's word timings letter by letter, else its visemes, else the agent's audio level */
    const word = stage.word
    let letter = ""
    if (word) {
      const i = Math.floor((performance.now() - word.start) / word.step)
      if (i >= word.letters.length) stage.word = null
      else letter = core.letters[word.letters.charAt(i)] ?? "nn"
    }
    if (letter !== s.letter) {
      if (s.letter) core.viseme(s.letter, 0)
      if (letter) core.viseme(letter, 0.85)
      s.letter = letter
    }
    if (letter) core.viseme(letter, 0.85)
    stage.visemes.forEach((value, key) => core.viseme(key, value))
    stage.visemes.clear()
    if (!letter && stage.turn === "speaking" && stage.level !== null) core.viseme("aa", stage.level)

    /* the camera: fitted to the stage for the focus, turned by a drag */
    const cam = state.camera as THREE.PerspectiveCamera
    const f = FRAMING[stage.focus]
    const tan = Math.tan(THREE.MathUtils.degToRad(FOV / 2))
    const aspect = state.size.width / state.size.height
    const distance = Math.max(HEIGHT / (2 * tan * f.fill), HALF_WIDTH / (tan * aspect))
    const lookY = HEIGHT / 2 + (f.centre - 0.5) * 2 * tan * distance
    const first = s.frames === 0
    const ease = (from: number, to: number, rate: number) => (first || !animate ? to : THREE.MathUtils.damp(from, to, rate, dt))
    if (!pointer.dragging) pointer.yaw = ease(pointer.yaw, 0, 3)
    s.yaw = ease(s.yaw, pointer.yaw, 14)
    s.distance = ease(s.distance, distance, 6)
    s.lookY = ease(s.lookY, lookY, 6)
    _look.set(0, s.lookY, 0)
    cam.position.set(Math.sin(s.yaw) * s.distance, s.lookY + 0.35, Math.cos(s.yaw) * s.distance)
    cam.lookAt(_look)
    cam.updateMatrixWorld()

    /* the first frames settle the rest pose; then the app can replay its state */
    if (!s.told && ++s.frames > 2) {
      s.told = true
      ready()
    }
  }, -1)

  return <RenBody ref={body} paused={!animate} />
}

function Stage() {
  const q = useQuality()
  const theme = useSyncExternalStore(onTheme, () => stage.theme)
  const [host, setHost] = useState<HTMLDivElement | null>(null)

  /* drag to look round Ren, tap to say hi; the engine's eyes follow the finger */
  useEffect(() => {
    if (!host) return
    let startX = 0
    let startYaw = 0
    let moved = false
    const down = (e: PointerEvent) => {
      host.setPointerCapture(e.pointerId)
      pointer.dragging = true
      moved = false
      startX = e.clientX
      startYaw = pointer.yaw
    }
    const move = (e: PointerEvent) => {
      if (!pointer.dragging) return
      const dx = e.clientX - startX
      if (Math.abs(dx) > 6) moved = true
      pointer.yaw = Math.max(-MAX_YAW, Math.min(MAX_YAW, startYaw - (dx / host.clientWidth) * 2.4))
    }
    const up = () => {
      if (pointer.dragging && !moved) pointer.tapped = true
      pointer.dragging = false
    }
    host.addEventListener("pointerdown", down)
    host.addEventListener("pointermove", move)
    host.addEventListener("pointerup", up)
    host.addEventListener("pointercancel", up)
    return () => {
      host.removeEventListener("pointerdown", down)
      host.removeEventListener("pointermove", move)
      host.removeEventListener("pointerup", up)
      host.removeEventListener("pointercancel", up)
    }
  }, [host])

  return (
    <div className="gl-stage" ref={setHost}>
      {/* the canvas as catolabs.com sets it up (CanvasProvider): flat keeps NoToneMapping with or without the bloom */}
      <Canvas
        flat
        dpr={q.dpr}
        camera={{ position: [0, 1, 6], fov: FOV, near: 0.1, far: 60 }}
        gl={{ antialias: true, alpha: false, powerPreference: "high-performance", stencil: false, toneMapping: THREE.NoToneMapping }}
      >
        <color attach="background" args={[PAPER[theme]]} />
        <Scene animate={q.animate} />
        {q.bloom && (
          <Suspense fallback={null}>
            <Glow />
          </Suspense>
        )}
      </Canvas>
    </div>
  )
}

const root = document.getElementById("root")
if (root) createRoot(root).render(<Stage />)
