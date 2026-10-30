import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { expect, test, type Page } from "@playwright/test"

import { BASE_URL } from "../playwright.config.ts"

/*
 * The stage layer on the real robot page, as the app's WebView loads it. The page answers one request outside its
 * assets the way ui/RobotView.kt does: the Orb's noise texture from the bundled copy, with the page's origin allowed.
 * Every other outside request is refused and recorded, so the page is proved to work offline.
 *
 * The Matrix tests need the robot page's armor and floor slots (docs/design/EMRYS_MATRIX.md §3); they skip on a page
 * that doesn't draw them.
 */
declare global {
  interface Window {
    emrys: {
      state(name: string): void
      mouth(word: string, seconds: number): void
      quiet(): void
      theme(name: string): void
      focus(mode: string): void
      voice(turn: string): void
      level(value: number): void
      speech(): { viseme: string; weight: number; talking: boolean; level: number | null }
      stageInfo(): { lowFx: boolean; short: boolean }
    }
    degrade(): void
  }
}

const NOISE = "https://storage.googleapis.com/eleven-public-cdn/images/perlin-noise.png"
const NOISE_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../app/src/main/assets/emrys/vendor/perlin-noise.png")
const PAGE = "/assets/emrys/index.html"

type Opened = { outside: string[]; errors: string[] }

async function openStage(page: Page, height = 640): Promise<Opened> {
  const opened: Opened = { outside: [], errors: [] }
  page.on("pageerror", (e) => opened.errors.push(e.message))
  page.on("console", (m) => {
    if (m.type() === "error") opened.errors.push(m.text())
  })
  await page.route("**/*", (route) => {
    const request = route.request()
    const url = request.url()
    if (url.startsWith(`${BASE_URL}/assets/`)) return route.continue()
    if (url === NOISE && request.method() === "GET")
      return route.fulfill({
        status: 200,
        contentType: "image/png",
        headers: { "Access-Control-Allow-Origin": BASE_URL },
        body: readFileSync(NOISE_FILE),
      })
    opened.outside.push(`${request.method()} ${url}`)
    return route.abort("blockedbyclient")
  })
  await page.setViewportSize({ width: 412, height })
  await page.goto(PAGE)
  await page.waitForFunction(() => typeof window.emrys.stageInfo === "function" && document.querySelector("[data-emrys=back]") !== null)
  await page.evaluate(() => window.emrys.theme("dark"))
  return opened
}

const halo = (page: Page) => page.locator("[data-emrys=halo]")
const caption = (page: Page) => page.locator("[data-emrys=caption]")

/** Whether the robot page draws the Matrix slots (it may add them a moment after load). */
const hasSlots = (page: Page) =>
  page.waitForFunction(() => document.querySelector("#slot-chest .emrys-slot-root") !== null, null, { timeout: 3000 }).then(
    () => true,
    () => false
  )

/** Brightness of each cell in a slot's Matrix, as drawn. */
const cells = (page: Page, slot: string) =>
  page.evaluate(
    (s) =>
      [...document.querySelectorAll(`[data-emrys="slot-${s}"] circle.matrix-pixel`)].map((c) =>
        c.classList.contains("opacity-20") ? 0 : Number(c.getAttribute("opacity"))
      ),
    slot
  )

test("the Orb mounts behind the robot's svg as the stage's first child and runs offline", async ({ page }) => {
  const opened = await openStage(page)
  await expect(halo(page).locator("canvas")).toHaveCount(1)
  const layout = await page.evaluate(() => {
    const stage = document.querySelector(".stage")
    const canvas = document.querySelector("[data-emrys=halo] canvas")
    const kids = stage ? [...stage.children] : []
    return {
      first: kids[0]?.getAttribute("data-emrys") ?? null,
      backBeforeSvg: kids.findIndex((k) => k.getAttribute("data-emrys") === "back") < kids.findIndex((k) => k.tagName === "svg"),
      width: canvas instanceof HTMLCanvasElement ? canvas.width : 0,
      halo: document.documentElement.getAttribute("data-halo"),
    }
  })
  expect(layout).toEqual({ first: "back", backBeforeSvg: true, width: expect.any(Number), halo: "orb" })
  expect(layout.width).toBeGreaterThan(100)
  // The texture loaded through the page's one allowed outside URL; nothing else left the page.
  await page.waitForTimeout(1500)
  expect(opened.outside).toEqual([])
  expect(opened.errors).toEqual([])
  await page.screenshot({ path: test.info().outputPath("idle.png") })
})

test("Emrys's state drives the Orb's agentState, its colours and the caption", async ({ page }) => {
  await openStage(page)
  const expectHalo = async (state: string, colors: string) => {
    await expect(halo(page)).toHaveAttribute("data-agent-state", state)
    await expect(halo(page)).toHaveAttribute("data-colors", colors)
  }
  await expectHalo("idle", "#CDD5FF #B7C2FF")

  await page.evaluate(() => window.emrys.state("listening"))
  await expectHalo("listening", "#F07B6C #F6B4AA")
  await expect(caption(page)).toHaveCount(0)

  await page.evaluate(() => window.emrys.state("working"))
  await expectHalo("thinking", "#8FDBE8 #C3EBF2")
  await expect(caption(page)).toHaveAttribute("data-caption", "Working")
  await expect(caption(page)).toContainText("Working")
  await page.screenshot({ path: test.info().outputPath("working.png") })

  await page.evaluate(() => window.emrys.state("waiting"))
  await expectHalo("idle", "#E8C07C #F1D9AE")
  await expect(caption(page)).toHaveCount(0)

  await page.evaluate(() => window.emrys.state("done"))
  await expectHalo("idle", "#97D4A6 #C6E7CD")

  await page.evaluate(() => window.emrys.state("calling"))
  await expect(caption(page)).toHaveAttribute("data-caption", "On a call")

  // Speech: TTS words through emrys.mouth until quiet, then the agent's own turn and its audio level.
  await page.evaluate(() => {
    window.emrys.state("idle")
    window.emrys.mouth("hello", 0.6)
  })
  await expectHalo("talking", "#CDD5FF #B7C2FF")
  await expect(page.locator("html")).toHaveAttribute("data-voice", "speaking")
  await page.evaluate(() => window.emrys.quiet())
  await expect(halo(page)).toHaveAttribute("data-agent-state", "idle")
  await page.evaluate(() => window.emrys.voice("speaking"))
  await expect(halo(page)).toHaveAttribute("data-agent-state", "talking")
  await expect(halo(page)).toHaveAttribute("data-volume", "auto")
  await page.evaluate(() => window.emrys.level(0.42))
  await expect(halo(page)).toHaveAttribute("data-volume", "manual")
  expect(await page.evaluate(() => window.emrys.speech())).toMatchObject({ level: 0.42, talking: true })
  await page.evaluate(() => window.emrys.voice("listening"))
  await expect(halo(page)).toHaveAttribute("data-agent-state", "listening")
  await expect(halo(page)).toHaveAttribute("data-volume", "auto")
  await page.evaluate(() => window.emrys.voice("none"))
  await expect(halo(page)).toHaveAttribute("data-agent-state", "idle")
  expect(await page.evaluate(() => window.emrys.speech().level)).toBeNull()
})

test("a squeezed stage (the mini band) drops the caption and the Orb and shows the band glyph", async ({ page }) => {
  await openStage(page, 110)
  await page.evaluate(() => {
    window.emrys.focus("card")
    window.emrys.state("thinking")
  })
  await expect(page.locator("[data-emrys=band]")).toHaveAttribute("data-glyph", "thinking")
  await expect(page.locator("[data-emrys=band] circle.matrix-pixel")).toHaveCount(49)
  await expect(caption(page)).toHaveCount(0)
  await expect(halo(page)).toHaveCount(0)
  await page.waitForTimeout(600)
  const lit = await page.evaluate(() => [...document.querySelectorAll("[data-emrys=band] circle")].filter((c) => !c.classList.contains("opacity-20")).length)
  expect(lit).toBeGreaterThan(0)
  await page.evaluate(() => window.emrys.state("waiting"))
  await expect(page.locator("[data-emrys=band]")).toHaveAttribute("data-glyph", "waiting")
  await page.screenshot({ path: test.info().outputPath("band.png") })
  if (await hasSlots(page)) {
    // Only the chest stays, still; the floor, gauntlets and mouth leave and the robot draws its own.
    await expect(page.locator("#slot-chest .emrys-slot-root")).toHaveAttribute("data-filled", "1")
    for (const s of ["floor", "mouth", "gauntlet-l", "gauntlet-r"]) await expect(page.locator(`#slot-${s} .emrys-slot-root`)).not.toHaveAttribute("data-filled", "1")
  }
})

test.describe("reduced motion", () => {
  test.use({ contextOptions: { reducedMotion: "reduce" } })
  test("keeps the CSS mood glow instead of the Orb, and the Matrix still", async ({ page }) => {
    await openStage(page)
    await page.evaluate(() => window.emrys.state("working"))
    await expect(caption(page)).toHaveAttribute("data-caption", "Working")
    await expect(halo(page)).toHaveCount(0)
    await expect(page.locator("html")).not.toHaveAttribute("data-halo", "orb")
    // The mood glow fades in over 0.8 s.
    const glow = () =>
      page.evaluate(() => {
        const stage = document.querySelector(".stage")
        return stage ? Number(getComputedStyle(stage, "::before").opacity) : 0
      })
    await expect.poll(glow).toBeGreaterThan(0.1)
    if (await hasSlots(page)) {
      await expect(page.locator("#slot-floor .emrys-slot-root")).not.toHaveAttribute("data-filled", "1")
      await page.waitForTimeout(400)
      const before = await cells(page, "chest")
      await page.waitForTimeout(1200)
      expect(await cells(page, "chest")).toEqual(before)
    }
  })
})

test("lowFx drops the Orb, the floor and the gauntlets and keeps the chest still", async ({ page }) => {
  await openStage(page)
  await expect(halo(page).locator("canvas")).toHaveCount(1)
  const slots = await hasSlots(page)
  if (slots) await expect(page.locator("#slot-floor .emrys-slot-root")).toHaveAttribute("data-filled", "1")
  await page.evaluate(() => window.degrade())
  await page.waitForFunction(() => window.emrys.stageInfo().lowFx)
  await expect(halo(page)).toHaveCount(0)
  await expect(page.locator("html")).not.toHaveAttribute("data-halo", "orb")
  if (slots) {
    for (const s of ["floor", "gauntlet-l", "gauntlet-r"]) await expect(page.locator(`#slot-${s} .emrys-slot-root`)).not.toHaveAttribute("data-filled", "1")
    await expect(page.locator("#slot-chest .emrys-slot-root")).toHaveAttribute("data-filled", "1")
    await page.waitForTimeout(600)
    const before = await cells(page, "chest")
    await page.waitForTimeout(1200)
    expect(await cells(page, "chest")).toEqual(before)
  }
})

test.describe("the Matrix in the robot's slots", () => {
  test.beforeEach(async ({ page }) => {
    await openStage(page)
    test.skip(!(await hasSlots(page)), "the robot page draws no Matrix slots")
  })

  test("fills every slot with its grid and marks it filled", async ({ page }) => {
    const grids: [string, number][] = [["chest", 169], ["mouth", 45], ["gauntlet-l", 33], ["gauntlet-r", 33], ["floor", 289]]
    for (const [slot, n] of grids) {
      await expect(page.locator(`#slot-${slot} .emrys-slot-root`)).toHaveAttribute("data-filled", "1")
      await expect(page.locator(`[data-emrys="slot-${slot}"] circle.matrix-pixel`)).toHaveCount(n)
    }
    // §6 budget: about 800 dots on the page.
    expect(await page.locator("circle.matrix-pixel").count()).toBeLessThanOrEqual(800)
  })

  test("shows a glyph per state on the chest", async ({ page }) => {
    await page.waitForTimeout(2600) // the boot show
    const seen = new Map<string, string>()
    const states: [string, string][] = [["idle", "Ready"], ["thinking", "Thinking"], ["texting", "Writing a text"], ["waiting", "Waiting on your OK"], ["done", "Done"], ["failed", "Something went wrong"]]
    for (const [state, words] of states) {
      await page.evaluate((s) => window.emrys.state(s), state)
      await expect(page.locator('[data-emrys="slot-chest"] [role=img]')).toHaveAttribute("aria-label", `Emrys's emblem: ${words}`)
      await page.waitForTimeout(900)
      const lit = await cells(page, "chest")
      expect(lit.some((v) => v > 0.05)).toBe(true)
      seen.set(state, lit.map((v) => (v > 0.05 ? 1 : 0)).join(""))
    }
    expect(new Set(seen.values()).size).toBe(seen.size)
  })

  test("the mouth moves with emrys.mouth and rests after quiet", async ({ page }) => {
    await page.waitForTimeout(2600)
    await page.evaluate(() => window.emrys.state("idle"))
    await page.waitForTimeout(600)
    const resting = (await cells(page, "mouth")).join()
    const shapes = new Set<string>()
    await page.evaluate(() => window.emrys.mouth("wonderful", 1.2))
    for (let i = 0; i < 12; i++) {
      await page.waitForTimeout(90)
      shapes.add((await cells(page, "mouth")).join())
    }
    shapes.delete(resting)
    expect(shapes.size).toBeGreaterThanOrEqual(2)
    await page.evaluate(() => window.emrys.quiet())
    await page.waitForTimeout(900)
    expect((await cells(page, "mouth")).join()).toBe(resting)
  })
})
