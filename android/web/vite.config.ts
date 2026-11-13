import path from "node:path"
import react from "@vitejs/plugin-react"
import autoprefixer from "autoprefixer"
import tailwindcss from "tailwindcss"
import { defineConfig } from "vite"

// Ren's pages for the app's WebViews. Each is its own page under assets/ren/, loaded through WebViewAssetLoader, with
// its chunks beside it (the robot's bloom pass loads only on wide screens). The search page and the agent panel's chat
// are ren desktop's own pages and components (src/ren, which its files import as "@"), styled by its Tailwind config.
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src"),
  envDir: __dirname,
  base: "./",
  plugins: [react()],
  publicDir: false,
  resolve: { alias: { "@": path.resolve(__dirname, "src/ren") } },
  css: { postcss: { plugins: [tailwindcss({ config: path.resolve(__dirname, "tailwind.config.ts") }), autoprefixer()] } },
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/ren/web"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
    rollupOptions: {
      input: {
        robot: path.resolve(__dirname, "src/robot/index.html"),
        search: path.resolve(__dirname, "src/search/index.html"),
        panel: path.resolve(__dirname, "src/panel/index.html"),
      },
    },
  },
})
