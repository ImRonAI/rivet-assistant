import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// The robot page loads the layer as one classic script and one stylesheet from its own assets, next to the noise
// texture the Orb reads, so the build writes a single IIFE and a single CSS file there and leaves the folder's other
// files alone. Library mode doesn't replace process.env.NODE_ENV, which React reads, so it is defined here.
// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  define: {
    "process.env.NODE_ENV": JSON.stringify("production"),
  },
  publicDir: false,
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/emrys/vendor"),
    emptyOutDir: false,
    target: "es2022",
    assetsInlineLimit: 0,
    sourcemap: false,
    lib: {
      entry: path.resolve(__dirname, "src/main.tsx"),
      name: "EmrysStage",
      formats: ["iife"],
      fileName: () => "stage-ui.js",
      cssFileName: "stage-ui",
    },
  },
})
