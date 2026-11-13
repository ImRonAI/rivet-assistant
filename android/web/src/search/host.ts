/*
 * What Electron supplies to ren's search page on the desktop, supplied by the app on the phone.
 *
 * ren desktop reaches its backend on loopback. The phone reaches the same backend on the workspace VM, behind a bearer
 * token the app holds: the page asks for it through the RenHost web message listener (browser/Browser.kt, which
 * answers only the app's bundled pages) and adds it to its requests to that backend.
 */
import { API_BASE_URL } from "@/lib/api-base"

/** The app's bearer token for the backend, asked for once; null outside the app, where there is none to add. */
const token = new Promise<string | null>((resolve) => {
  const host = window.RenHost
  if (host === undefined) {
    resolve(null)
    return
  }
  host.onmessage = (event) => resolve(event.data)
  host.postMessage("token")
})

const send = window.fetch.bind(window)

window.fetch = async (input, init) => {
  const request = new Request(input, init)
  if (new URL(request.url).origin !== API_BASE_URL) return send(request)
  const bearer = await token
  if (bearer !== null) request.headers.set("Authorization", `Bearer ${bearer}`)
  return send(request)
}
