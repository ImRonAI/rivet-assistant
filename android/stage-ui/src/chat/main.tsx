import { StrictMode } from "react"
import { createRoot } from "react-dom/client"

import "katex/dist/katex.min.css"
import "./chat.css"
import { ChatApp } from "./app"
import { tell } from "./model"

/* The chat page (assets/emrys/chat/index.html in the app). The app sends the chat once the page says it's ready. */
const root = document.getElementById("root")
if (root) {
  createRoot(root).render(
    <StrictMode>
      <ChatApp />
    </StrictMode>
  )
  tell({ type: "ready" })
}
