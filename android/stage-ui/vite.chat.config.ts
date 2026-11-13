import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// The chat page is its own app: the app's WebView loads assets/ren/chat/index.html through WebViewAssetLoader, and
// the page loads its chunks (the Streamdown plugins' code highlighting, math and diagrams load on demand) beside it.
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src/chat"),
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  publicDir: false,
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/ren/chat"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
  },
})
