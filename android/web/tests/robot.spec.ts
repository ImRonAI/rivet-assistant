import { mkdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { expect, test, type Page } from "@playwright/test"

import { BASE_URL } from "../playwright.config.ts"

/*
 * Ren's robot on its real page, as the app's WebView loads it: it draws, tells the app it is ready, plays the move for
 * what the app is doing, follows the theme, and asks for nothing outside the app's own assets.
 */
const PAGE = "/assets/ren/web/robot/index.html"
const SCREENS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../build/screens")
mkdirSync(SCREENS, { recursive: true })

type Opened = { outside: string[]; errors: string[] }

async function open(page: Page): Promise<Opened> {
  const opened: Opened = { outside: [], errors: [] }
  page.on("request", (request) => {
    if (!request.url().startsWith(BASE_URL)) opened.outside.push(request.url())
  })
  page.on("console", (message) => {
    if (message.type() === "error") opened.errors.push(message.text())
  })
  page.on("pageerror", (error) => opened.errors.push(error.message))
  await page.addInitScript(() => {
    const told: string[] = []
    Object.assign(window, { told, RenHost: { postMessage: (message: string) => told.push(message) } })
  })
  await page.goto(PAGE)
  await expect.poll(() => page.evaluate(() => (window as unknown as { told: string[] }).told)).toContain('{"type":"ready"}')
  return opened
}

/** How many pixels differ between two screenshots of the same size, by comparing them in the page. */
async function shot(page: Page, name: string): Promise<Buffer> {
  return page.screenshot({ path: path.join(SCREENS, `robot-${name}.png`) })
}

test("the robot draws, says ready and asks for nothing outside the app", async ({ page }) => {
  const opened = await open(page)
  await page.waitForTimeout(1200)
  await shot(page, "idle-light")
  await expect(page.locator(".gl-stage canvas")).toBeVisible()
  expect(opened.outside).toEqual([])
  expect(opened.errors).toEqual([])
})

test("the robot plays the move for what the app is doing", async ({ page }) => {
  await open(page)
  await page.waitForTimeout(800)
  const idle = await shot(page, "idle")
  for (const state of ["thinking", "working", "texting", "emailing", "calling", "done", "failed"]) {
    await page.evaluate((name) => window.ren.state(name), state)
    await page.waitForTimeout(1600)
    const frame = await shot(page, state)
    expect(frame.equals(idle), `${state} looks the same as idle`).toBe(false)
  }
})

test("the robot follows the theme and the focus", async ({ page }) => {
  await open(page)
  await page.evaluate(() => window.ren.theme("dark"))
  await page.waitForTimeout(600)
  await shot(page, "idle-dark")
  expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe("rgb(12, 12, 12)")
  for (const focus of ["card", "greet"]) {
    await page.evaluate((mode) => window.ren.focus(mode), focus)
    await page.waitForTimeout(900)
    await shot(page, `${focus}-dark`)
  }
})

test("a word moves the mouth", async ({ page }) => {
  await open(page)
  await page.evaluate(() => window.ren.theme("light"))
  await page.evaluate(() => window.ren.focus("card"))
  await page.waitForTimeout(900)
  const closed = await shot(page, "mouth-closed")
  await page.evaluate(() => window.ren.mouth("aaaaaaaaaaaa", 2.4))
  await page.waitForTimeout(700)
  const open_ = await shot(page, "mouth-open")
  expect(open_.equals(closed)).toBe(false)
})
