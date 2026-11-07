import path from "path"
import { defineConfig } from "vite"

// The Telnyx page is its own app: the app's WebView loads assets/emrys/telnyx/index.html through WebViewAssetLoader.
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src/telnyx"),
  base: "./",
  publicDir: false,
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/emrys/telnyx"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
  },
})
