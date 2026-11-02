import { StrictMode } from "react"
import { createRoot } from "react-dom/client"

import "./index.css"
import { StageLayer } from "./stage"

/*
 * Mounts the stage layer into the robot page (assets/emrys/index.html loads this after bridge.js). The back layer is
 * the stage's first child, so the Orb sits under the robot's svg; the front layer is its last, for the caption and the
 * band glyph. A page without a stage is left alone.
 */
const stage = document.querySelector<HTMLElement>(".stage")
if (stage && window.emrys) {
  const back = document.createElement("div")
  back.className = "emrys-layer"
  back.dataset.emrys = "back"
  stage.prepend(back)
  const front = document.createElement("div")
  front.className = "emrys-layer"
  front.dataset.emrys = "front"
  stage.append(front)
  createRoot(back).render(
    <StrictMode>
      <StageLayer stage={stage} front={front} />
    </StrictMode>
  )
}
