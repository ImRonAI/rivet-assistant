/*
 * ren://search on the phone: ren desktop's search results page, mounted as it is. The tab's address carries the query
 * and the level, as it does on the desktop.
 */
import "./host"
import "./search.css"
import "@/index.css"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { getSearchLevelFromRonUrl, getSearchQueryFromRonUrl } from "@/components/chrome/UrlBar"
import { DEFAULT_SEARCH_LEVEL } from "@/lib/search-api"
import { SearchResultsPage } from "@/pages/SearchResultsPage"

/** This page's address the way ren desktop writes a search. */
const address = `ron://search${window.location.search}`
const query = getSearchQueryFromRonUrl(address) ?? ""
const level = getSearchLevelFromRonUrl(address) ?? DEFAULT_SEARCH_LEVEL

/** ren's Dark is the `dark` class on the document; the phone's theme decides it. */
const dark = window.matchMedia("(prefers-color-scheme: dark)")
const applyTheme = () => document.documentElement.classList.toggle("dark", dark.matches)
applyTheme()
dark.addEventListener("change", applyTheme)

document.title = query

const root = document.getElementById("root")
if (root === null) throw new Error("The search page has no root element.")

createRoot(root).render(
  <StrictMode>
    <div className="h-full overflow-y-auto">
      <SearchResultsPage query={query} level={level} onBack={() => window.location.assign("ren://home")} />
    </div>
  </StrictMode>,
)
