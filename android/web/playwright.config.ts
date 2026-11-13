import { defineConfig } from "@playwright/test"

// The built pages (android/app/src/main/assets/ren/web) served over HTTP from the app's `main` folder, so /assets/ and
// /res/ answer the way the app's WebViewAssetLoader answers them.
const PORT = Number(process.env["REN_WEB_PORT"] ?? 8773)
export const BASE_URL = `http://127.0.0.1:${PORT}`

export default defineConfig({
  testDir: "tests",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: BASE_URL,
    viewport: { width: 412, height: 520 },
    deviceScaleFactor: 2,
  },
  webServer: {
    command: `python3 -m http.server ${PORT} --bind 127.0.0.1 --directory ../app/src/main`,
    url: `${BASE_URL}/assets/ren/web/robot/index.html`,
    reuseExistingServer: true,
  },
})
