import { Bloom, EffectComposer } from "@react-three/postprocessing"

/** CatoWeb's bloom (CanvasProvider): only the robot's lit decals and speculars cross the threshold. */
export default function Glow() {
  return (
    <EffectComposer multisampling={4}>
      <Bloom intensity={0.4} luminanceThreshold={0.86} luminanceSmoothing={0.2} mipmapBlur />
    </EffectComposer>
  )
}
