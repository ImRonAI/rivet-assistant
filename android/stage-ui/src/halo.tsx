import { Component, Suspense, useEffect, useRef, useState, type ReactNode } from "react"

import { Orb, type AgentState } from "@/components/ui/orb"
import { readSpeech, WORKING, type StageInfo } from "@/emrys"

/** The Orb's two ramp colours per mood (docs/design/EMRYS_DESIGN.md §3): aurora at rest and while Emrys speaks. */
const AURORA: [string, string] = ["#CDD5FF", "#B7C2FF"]
const MOOD_COLORS: Record<string, [string, string]> = {
  cy: ["#8FDBE8", "#C3EBF2"],
  amb: ["#E8C07C", "#F1D9AE"],
  ok: ["#97D4A6", "#C6E7CD"],
  rec: ["#F07B6C", "#F6B4AA"],
}

export function haloColors(info: StageInfo): [string, string] {
  if (info.talking || !info.mood) return AURORA
  return MOOD_COLORS[info.mood] ?? AURORA
}

/** The Orb's agentState for what Emrys is doing: speech wins, then a live mic, then any kind of work. */
export function haloState(info: StageInfo): AgentState {
  if (info.talking) return "talking"
  if (info.state === "listening" || info.turn === "listening") return "listening"
  if (info.state in WORKING) return "thinking"
  return null
}

let webgl2: boolean | null = null
/** three.js (r163 and later) renders only on WebGL 2. */
export function hasWebGL2(): boolean {
  if (webgl2 === null) {
    try {
      const gl = document.createElement("canvas").getContext("webgl2")
      webgl2 = !!gl
      gl?.getExtension("WEBGL_lose_context")?.loseContext()
    } catch {
      webgl2 = false
    }
  }
  return webgl2
}

/** Keeps a failed Orb (no WebGL context, the texture refused) from taking the stage down; the CSS glow stays. */
class Fallback extends Component<{ children: ReactNode; onFail: () => void }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  componentDidCatch() {
    this.props.onFail()
  }
  render() {
    return this.state.failed ? null : this.props.children
  }
}

const SEED = 4096
/** Feathers the Orb's disc into a glow. */
const FEATHER = "radial-gradient(closest-side, #000 18%, transparent 82%)"

/**
 * The hero's backlight: an ElevenLabs Orb centred on Emrys's chest and head, large, soft and faint so the robot and
 * its Matrix read in front of it. Real voice levels drive it when the app sends them (a live ElevenLabs voice
 * session); otherwise the Orb animates itself from agentState, as the component documents.
 */
export function Halo({ info }: { info: StageInfo }) {
  const colors = haloColors(info)
  // The Orb reads its colours from this ref every frame and blends to them.
  const colorsRef = useRef<[string, string]>(colors)
  const [low, high] = colors
  useEffect(() => {
    colorsRef.current = [low, high]
  }, [low, high])
  const [failed, setFailed] = useState(false)
  const [real, setReal] = useState(false)

  useEffect(() => {
    // A voice session's levels count from the first one the app sends until the session ends.
    const timer = setInterval(() => {
      const speech = readSpeech()
      setReal(speech.output !== null || speech.input !== null)
    }, 250)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    if (failed) return
    document.documentElement.setAttribute("data-halo", "orb")
    return () => document.documentElement.removeAttribute("data-halo")
  }, [failed])

  if (failed) return null
  const a = info.anchor
  const height = a ? Math.max(80, a.feetY - a.y) : info.height * 0.7
  const size = Math.round(Math.min(Math.max(height * 1.15, 180), Math.max(info.width, info.height) * 1.2))
  const cx = a ? a.x : info.width / 2
  const cy = a ? a.y + height * 0.32 : info.height * 0.42
  return (
    <div
      data-emrys="halo"
      data-agent-state={haloState(info) ?? "idle"}
      data-volume={real ? "manual" : "auto"}
      data-colors={colors.join(" ")}
      aria-hidden="true"
      className="absolute opacity-30 mix-blend-screen transition-[left,top,width,height] duration-700 ease-out"
      style={{ left: cx - size / 2, top: cy - size / 2, width: size, height: size, maskImage: FEATHER, WebkitMaskImage: FEATHER }}
    >
      <Fallback onFail={() => setFailed(true)}>
        <Suspense fallback={null}>
          <Orb
            colorsRef={colorsRef}
            seed={SEED}
            agentState={haloState(info)}
            volumeMode={real ? "manual" : "auto"}
            getInputVolume={() => readSpeech().input ?? 0}
            getOutputVolume={() => readSpeech().output ?? 0}
          />
        </Suspense>
      </Fallback>
    </div>
  )
}
