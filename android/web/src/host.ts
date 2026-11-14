/*
 * What Electron supplies to ren's pages on the desktop, supplied by the app on the phone.
 *
 * ren desktop reaches its backend on loopback. The phone reaches the same backend on the workspace VM, behind a bearer
 * token the app holds: the page asks for it through the RenHost web message listener (browser/Browser.kt, which
 * answers only the app's bundled pages) and adds it to its requests to that backend. The same listener carries the
 * favorite links, which the app keeps for its home page and Discover alike.
 */
import { API_BASE_URL } from "@/lib/api-base"
import type { Favorite } from "@/stores/favoritesStore"

/** What the app answers: `{"type":"token","token":…}` or `{"type":"favorites","favorites":[…]}`. */
type Answer = { type: "token"; token: string } | { type: "favorites"; favorites: Favorite[] }

const waiting = new Map<Answer["type"], (answer: Answer) => void>()

function isFavorite(value: unknown): value is Favorite {
  return (
    typeof value === "object" &&
    value !== null &&
    "id" in value &&
    typeof value.id === "string" &&
    "title" in value &&
    typeof value.title === "string" &&
    "url" in value &&
    typeof value.url === "string"
  )
}

function answerOf(data: string): Answer | null {
  const message: unknown = JSON.parse(data)
  if (typeof message !== "object" || message === null || !("type" in message)) return null
  if (message.type === "token" && "token" in message && typeof message.token === "string") return { type: "token", token: message.token }
  if (message.type === "favorites" && "favorites" in message && Array.isArray(message.favorites)) {
    const favorites: unknown[] = message.favorites
    return { type: "favorites", favorites: favorites.filter(isFavorite) }
  }
  return null
}

const host = window.RenHost
if (host !== undefined) {
  host.onmessage = (event) => {
    const answer = answerOf(event.data)
    if (answer === null) return
    waiting.get(answer.type)?.(answer)
    waiting.delete(answer.type)
  }
}

/** Asks the app for one thing; null outside the app, which has nothing to give. */
function ask(type: Answer["type"]): Promise<Answer | null> {
  if (host === undefined) return Promise.resolve(null)
  return new Promise((resolve) => {
    waiting.set(type, resolve)
    host.postMessage(JSON.stringify({ type }))
  })
}

/** The favorite links the app keeps; null outside the app. */
export async function favorites(): Promise<Favorite[] | null> {
  const answer = await ask("favorites")
  return answer?.type === "favorites" ? answer.favorites : null
}

/** Gives the app the favorite links as the page left them. */
export function saveFavorites(list: Favorite[]) {
  host?.postMessage(JSON.stringify({ type: "favorites", favorites: list }))
}

/** The app's bearer token for the backend, asked for once; null outside the app, where there is none to add. */
const token = ask("token").then((answer) => (answer?.type === "token" ? answer.token : null))

const send = window.fetch.bind(window)

window.fetch = async (input, init) => {
  const request = new Request(input, init)
  if (new URL(request.url).origin !== API_BASE_URL) return send(request)
  const bearer = await token
  if (bearer !== null) request.headers.set("Authorization", `Bearer ${bearer}`)
  return send(request)
}
