/** The app's web message listener for its bundled pages (WebViewCompat.addWebMessageListener). */
interface Window {
  RenHost?: {
    postMessage(message: string): void
    onmessage: ((event: MessageEvent<string>) => void) | null
  }
}
