import path from "node:path"
import { fileURLToPath } from "node:url"
import type { Config } from "tailwindcss"
import ren from "./src/ren/tailwind.config"

const here = path.dirname(fileURLToPath(import.meta.url))

// ren desktop's own Tailwind config (Ink & Volt), scanning the phone's search page, its agent panel chat and ren's files they mount.
export default {
  ...ren,
  content: [path.join(here, "src/search/**/*.{html,tsx}"), path.join(here, "src/panel/**/*.{html,tsx}"), path.join(here, "src/ren/**/*.{ts,tsx}")],
} satisfies Config
