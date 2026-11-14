/*
 * ren://discover on the phone: ren desktop's Discover, its home page (HomePage on the Discover tab), mounted as it is,
 * with the interests interview behind Personalize (`#onboarding`, as ren desktop's App.tsx routes it).
 *
 * On the desktop Discover lives among ren's tabs. Here it is one tab of the phone's browser, so ren's tab store holds
 * just this page, and what Discover asks of it becomes the browser's: a story it opens loads in this tab (the article
 * itself, where the desktop shows its reader), and a favorite it opens in a new tab opens in a new browser tab.
 */
import { favorites, saveFavorites } from "../host"
import "../page.css"
import "@/index.css"
import { Compass, Search } from "lucide-react"
import { StrictMode, useEffect, useState } from "react"
import { createRoot } from "react-dom/client"
import { HomePage } from "@/pages/HomePage"
import { OnboardingPage } from "@/pages/OnboardingPage"
import { useFavoritesStore } from "@/stores/favoritesStore"
import { useNavigationStore } from "@/stores/navigationStore"
import { useTabStore } from "@/stores/tabStore"
import { cn } from "@/utils/cn"

/** ren desktop's reader address for a story (`readStoryUrl` in src/lib/articles.ts). */
const READ = "ron://read?"

const dark = window.matchMedia("(prefers-color-scheme: dark)")
const applyTheme = () => document.documentElement.classList.toggle("dark", dark.matches)
applyTheme()
dark.addEventListener("change", applyTheme)

useNavigationStore.getState().setActiveTab("discover")
useTabStore.getState().setTabs([], null)
useTabStore.getState().createTab("ron://home", true)
useTabStore.subscribe((state, previous) => {
  for (const tab of state.tabs) {
    const before = previous.tabs.find((it) => it.id === tab.id)
    if (before === undefined) {
      if (/^https?:\/\//.test(tab.url)) window.open(tab.url)
      useTabStore.getState().closeTab(tab.id)
    } else if (tab.url !== before.url && tab.url.startsWith(READ)) {
      const article = new URLSearchParams(tab.url.slice(READ.length)).get("url")
      if (article !== null) window.location.assign(article)
    }
  }
})

// The favorites are the app's, the same list its home page shows under the search box.
void favorites().then((list) => {
  if (list === null) return
  useFavoritesStore.setState({ favorites: list })
  useFavoritesStore.subscribe((state, previous) => {
    if (state.favorites !== previous.favorites) saveFavorites(state.favorites)
  })
})

/** Where the phone's home page and Discover sit side by side, as ren desktop's sidebar lists its destinations. */
function Environments() {
  const item = "inline-flex h-9 items-center gap-2 rounded-lg px-3.5 text-[13px] font-semibold transition-colors duration-150 ease-out"
  return (
    <nav aria-label="Destinations" className="flex justify-center px-4 pt-4">
      <div className="inline-flex gap-1 rounded-xl bg-surface-100 p-1 dark:bg-surface-850">
        <a href="ren://home" className={cn(item, "text-ink-secondary dark:text-ink-inverse-secondary")}>
          <Search className="h-4 w-4" />
          Search
        </a>
        <span aria-current="page" className={cn(item, "bg-surface-0 text-ink shadow-sm dark:bg-surface-900 dark:text-ink-inverse")}>
          <Compass className="h-4 w-4" />
          Discover
        </span>
      </div>
    </nav>
  )
}

function Discover() {
  const [interview, setInterview] = useState(window.location.hash === "#onboarding")
  useEffect(() => {
    const update = () => setInterview(window.location.hash === "#onboarding")
    window.addEventListener("hashchange", update)
    return () => window.removeEventListener("hashchange", update)
  }, [])
  if (interview) return <OnboardingPage />
  return (
    <div className="flex h-full flex-col bg-surface-0 dark:bg-surface-900">
      <Environments />
      <div className="min-h-0 flex-1">
        <HomePage />
      </div>
    </div>
  )
}

document.title = "Discover"

const root = document.getElementById("root")
if (root === null) throw new Error("The Discover page has no root element.")

createRoot(root).render(
  <StrictMode>
    <Discover />
  </StrictMode>,
)
