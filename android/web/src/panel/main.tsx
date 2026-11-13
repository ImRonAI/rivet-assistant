/*
 * The agent panel's chat (assets/ren/web/panel/ in the app): the conversation on ren desktop's own agent panel
 * components. The app sends the chat once the page says it's ready.
 */
import "../page.css"
import "@/index.css"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { PanelChat } from "./app"
import { tell } from "./model"

/** ren's Dark is the `dark` class on the document; the phone's theme decides it. */
const dark = window.matchMedia("(prefers-color-scheme: dark)")
const applyTheme = () => document.documentElement.classList.toggle("dark", dark.matches)
applyTheme()
dark.addEventListener("change", applyTheme)

const root = document.getElementById("root")
if (root === null) throw new Error("The panel page has no root element.")

createRoot(root).render(
  <StrictMode>
    <PanelChat />
  </StrictMode>,
)
tell({ type: "ready" })
