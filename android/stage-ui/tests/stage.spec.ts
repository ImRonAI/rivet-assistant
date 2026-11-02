import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { expect, test, type Page } from "@playwright/test"

/*
 * The stage layer on the real robot page, as the app's WebView loads it. The page answers one request outside its
 * assets the way ui/RobotView.kt does: the Orb's noise texture from the bundled copy, with the page's origin allowed.
 * Every other outside request is refused and recorded, so the page is proved to work offline.
 *
 * The Matrix tests need the robot page's armor and floor slots (docs/design/EMRYS_MATRIX.md §3); they skip on a page
 * that doesn't draw them yet. EMRYS_SLOT_HARNESS may name a development script that draws them for a local run.
 */
declare global {
  interface Window {
    emrys?: { stageInfo?: () => { hud: boolean; lowFx: boolean } }
  }
}

const NOISE = "https://storage.googleapis.com/eleven-public-cdn/images/perlin-noise.png"
const NOISE_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../app/src/main/assets/emrys/vendor/perlin-noise.png")
const PAGE = "/assets/emrys/index.html"

type Opened = { outside: string[]; errors: string[] }

async function openStage(page: Page, { height = 640 }: { height?: number } = {}): Promise<Opened> {
  const opened: Opened = { outside: [], errors: [] }
  const origin = new URL(test.info().project.use.baseURL!).origin
  page.on("pageerror", (e) => opened.errors.push(e.message))
  page.on("console", (m) => {
    if (m.type() === "error") opened.errors.push(m.text())
  })
  await page.route("**/*", (route) => {
    const request = route.request()
    const url = request.url()
    if (url.startsWith(`${origin}/assets/`)) return route.continue()
    if (url === NOISE && request.method() === "GET")
      return route.fulfill({
        status: 200,
        contentType: "image/png",
        headers: { "Access-Control-Allow-Origin": origin },
        body: readFileSync(NOISE_FILE),
      })
    opened.outside.push(`${request.method()} ${url}`)
    return route.abort("blockedbyclient")
  })
  if (process.env.EMRYS_SLOT_HARNESS) await page.addInitScript({ path: process.env.EMRYS_SLOT_HARNESS })
  await page.setViewportSize({ width: 412, height })
  await page.goto(PAGE)
  await page.waitForFunction(() => !!window.emrys?.stageInfo && !!document.querySelector("[data-emrys=back]"))
  await page.evaluate(() => (window.emrys as unknown as { theme: (t: string) => void }).theme("dark"))
  return opened
}

const call = (page: Page, method: string, ...args: unknown[]) =>
  page.evaluate(([m, a]) => (window.emrys as unknown as Record<string, (...x: unknown[]) => unknown>)[m as string](...(a as unknown[])), [method, args] as const)

const halo = (page: Page) => page.locator("[data-emrys=halo]")
const caption = (page: Page) => page.locator("[data-emrys=caption]")
/** Whether the robot page draws the Matrix slots (it may add them a moment after load). */
const hasSlots = (page: Page) =>
  page.waitForFunction(() => !!document.querySelector("#slot-chest .emrys-slot-root"), null, { timeout: 3000 }).then(
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
    const stage = document.querySelector(".stage")!
    const kids = [...stage.children]
    const canvas = document.querySelector("[data-emrys=halo] canvas") as HTMLCanvasElement
    return {
      first: (kids[0] as HTMLElement).dataset.emrys,
      backBeforeSvg: kids.findIndex((k) => (k as HTMLElement).dataset.emrys === "back") < kids.findIndex((k) => k.tagName === "svg"),
      canvas: [canvas.width, canvas.height],
      haloAttr: document.documentElement.getAttribute("data-halo"),
    }
  })
  expect(layout.first).toBe("back")
  expect(layout.backBeforeSvg).toBe(true)
  expect(layout.canvas[0]).toBeGreaterThan(100)
  expect(layout.haloAttr).toBe("orb")
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

  await call(page, "state", "listening")
  await expectHalo("listening", "#F07B6C #F6B4AA")
  await expect(caption(page)).toHaveCount(0)

  await call(page, "state", "working")
  await expectHalo("thinking", "#8FDBE8 #C3EBF2")
  await expect(caption(page)).toHaveAttribute("data-caption", "Working", { timeout: 5000 })
  await expect(caption(page)).toContainText("Working")
  await page.screenshot({ path: test.info().outputPath("working.png") })

  await call(page, "state", "waiting")
  await expectHalo("idle", "#E8C07C #F1D9AE")
  await expect(caption(page)).toHaveCount(0)

  await call(page, "state", "done")
  await expectHalo("idle", "#97D4A6 #C6E7CD")

  await call(page, "state", "calling")
  await expect(caption(page)).toHaveAttribute("data-caption", "On a call", { timeout: 5000 })

  // Speech: TTS words through emrys.mouth, then the agent's own turn and real levels.
  await call(page, "state", "idle")
  await call(page, "mouth", "hello", 0.6)
  await expectHalo("talking", "#CDD5FF #B7C2FF")
  await expect(page.locator("html")).toHaveAttribute("data-voice", "speaking")
  await call(page, "quiet")
  await expect(halo(page)).toHaveAttribute("data-agent-state", "idle")
  await call(page, "voice", "speaking")
  await expect(halo(page)).toHaveAttribute("data-agent-state", "talking")
  await expect(halo(page)).toHaveAttribute("data-volume", "auto")
  await call(page, "levels", 0.42, 0.9)
  await expect(halo(page)).toHaveAttribute("data-volume", "manual")
  expect(await call(page, "speech")).toMatchObject({ output: 0.42, input: 0.9, turn: "speaking", talking: true })
  await call(page, "voice", "listening")
  await expect(halo(page)).toHaveAttribute("data-agent-state", "listening")
  await call(page, "voice", null)
  await expect(halo(page)).toHaveAttribute("data-volume", "auto")
  await expect(halo(page)).toHaveAttribute("data-agent-state", "idle")
})

test("the caption steps aside while a glass card is on the stage", async ({ page }) => {
  await openStage(page)
  await call(page, "state", "thinking")
  await page.waitForFunction(() => window.emrys!.stageInfo!().hud, null, { timeout: 8000 })
  await page.waitForTimeout(400)
  await expect(caption(page)).toHaveCount(0)
})

test("a squeezed stage (the mini band) drops the caption and the Orb and shows the band glyph", async ({ page }) => {
  await openStage(page, { height: 110 })
  await call(page, "focus", "card")
  await call(page, "state", "thinking")
  await expect(page.locator("[data-emrys=band]")).toHaveAttribute("data-glyph", "thinking")
  await expect(page.locator("[data-emrys=band] circle.matrix-pixel")).toHaveCount(49)
  await page.waitForTimeout(2500)
  await expect(caption(page)).toHaveCount(0)
  await expect(halo(page)).toHaveCount(0)
  const lit = await page.evaluate(() => [...document.querySelectorAll("[data-emrys=band] circle")].filter((c) => !c.classList.contains("opacity-20")).length)
  expect(lit).toBeGreaterThan(0)
  await call(page, "state", "waiting")
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
    await call(page, "state", "working")
    await expect(caption(page)).toHaveAttribute("data-caption", "Working", { timeout: 5000 })
    await expect(halo(page)).toHaveCount(0)
    await expect(page.locator("html")).not.toHaveAttribute("data-halo", "orb")
    const glow = await page.evaluate(() => getComputedStyle(document.querySelector(".stage")!, "::before").opacity)
    expect(Number(glow)).toBeGreaterThan(0)
    if (await hasSlots(page)) {
      await expect(page.locator("#slot-floor .emrys-slot-root")).not.toHaveAttribute("data-filled", "1")
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
  await page.evaluate(() => (window as unknown as { degrade: () => void }).degrade())
  await page.waitForFunction(() => window.emrys!.stageInfo!().lowFx)
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
    test.skip(!(await hasSlots(page)), "the robot page draws no Matrix slots yet")
  })

  test("fills every slot with its grid and marks it filled", async ({ page }) => {
    const grids: Record<string, number> = { chest: 169, mouth: 45, "gauntlet-l": 33, "gauntlet-r": 33, floor: 289 }
    for (const [slot, n] of Object.entries(grids)) {
      await expect(page.locator(`#slot-${slot} .emrys-slot-root`)).toHaveAttribute("data-filled", "1")
      await expect(page.locator(`[data-emrys="slot-${slot}"] circle.matrix-pixel`)).toHaveCount(n)
    }
    // §6 budget: about 800 dots on the page, about 150 above 0.5 (the glow filter).
    expect(await page.locator("circle.matrix-pixel").count()).toBeLessThanOrEqual(800)
    for (const state of ["idle", "thinking", "working", "done", "listening"]) {
      await call(page, "state", state)
      await page.waitForTimeout(700)
      expect(await page.locator(".matrix-pixel-active").count()).toBeLessThanOrEqual(150)
    }
  })

  test("shows a glyph per state on the chest", async ({ page }) => {
    await page.waitForTimeout(2600) // the boot show
    const seen = new Map<string, string>()
    for (const [state, words] of [["idle", "Ready"], ["thinking", "Thinking"], ["texting", "Writing a text"], ["waiting", "Waiting on your OK"], ["done", "Done"], ["failed", "Something went wrong"]]) {
      await call(page, "state", state)
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
    await call(page, "state", "idle")
    await page.waitForTimeout(600)
    const resting = (await cells(page, "mouth")).join()
    const shapes = new Set<string>()
    await call(page, "mouth", "wonderful", 1.2)
    for (let i = 0; i < 12; i++) {
      await page.waitForTimeout(90)
      shapes.add((await cells(page, "mouth")).join())
    }
    shapes.delete(resting)
    expect(shapes.size).toBeGreaterThanOrEqual(2)
    await call(page, "quiet")
    await page.waitForTimeout(900)
    expect((await cells(page, "mouth")).join()).toBe(resting)
  })
})
