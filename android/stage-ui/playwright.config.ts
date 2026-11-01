import { defineConfig } from "@playwright/test"

// The real robot page (android/app/src/main/assets/emrys/index.html, with the built stage layer) served over HTTP at
// the path the app's WebView uses. PLAYWRIGHT_CHROMIUM_EXECUTABLE points at a local Chromium when Playwright's own
// browser isn't installed.
const PORT = Number(process.env["STAGE_PORT"] ?? 8772)
export const BASE_URL = `http://127.0.0.1:${PORT}`
const chromium = process.env["PLAYWRIGHT_CHROMIUM_EXECUTABLE"]

export default defineConfig({
  testDir: "tests",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: BASE_URL,
    viewport: { width: 412, height: 640 },
    deviceScaleFactor: 2,
    colorScheme: "dark",
    launchOptions: chromium ? { executablePath: chromium } : {},
  },
  webServer: {
    command: `python3 -m http.server ${PORT} --bind 127.0.0.1 --directory ../app/src/main`,
    url: `${BASE_URL}/assets/emrys/index.html`,
    reuseExistingServer: true,
  },
})
