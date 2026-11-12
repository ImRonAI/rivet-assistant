import path from "node:path"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// Ren's pages for the app's WebViews. Each is its own page under assets/ren/, loaded through WebViewAssetLoader, with
// its chunks beside it (the robot's bloom pass loads only on wide screens).
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src"),
  base: "./",
  plugins: [react()],
  publicDir: false,
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/ren/web"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
    rollupOptions: {
      input: { robot: path.resolve(__dirname, "src/robot/index.html") },
    },
  },
})
