package com.hiron.ren.mcp

import android.util.Base64
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.SearchLevel
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Ren's browser as MCP tools: its tabs, navigation, and reading and acting on the page in a tab. A page is read the
 * way ren desktop's browser tools read one: its title and address, up to [TEXT_LIMIT] characters of text and up to
 * [ELEMENT_LIMIT] interactive elements, each with a `ref` that `browser_click` and `browser_type` take. Refs belong to
 * the page as it was last read; a navigation makes them stale.
 */
internal class BrowserTools(private val browser: Browser, private val level: () -> SearchLevel) {
    val tools: List<Tool> = listOf(
        tool("browser_tabs", "The browser's open tabs: id, address, title, and which is active.", readOnly = true),
        tool("browser_open", "Open a new tab on an address or a search and make it active. Returns the tab.", false, listOf("url"),
            "url" to str("An address (example.com, https://…) or words to search for")),
        tool("browser_navigate", "Take a tab to an address or a search.", false, listOf("url"),
            "url" to str("An address (example.com, https://…) or words to search for"), "tab" to str(TAB)),
        tool("browser_back", "Go back in a tab.", false, emptyList(), "tab" to str(TAB)),
        tool("browser_forward", "Go forward in a tab.", false, emptyList(), "tab" to str(TAB)),
        tool("browser_reload", "Reload a tab's page.", false, emptyList(), "tab" to str(TAB)),
        tool("browser_select", "Make a tab the active one.", false, listOf("tab"), "tab" to str("The tab's id from browser_tabs")),
        tool("browser_close", "Close a tab.", false, listOf("tab"), "tab" to str("The tab's id from browser_tabs"), destructive = true),
        tool("browser_read", "Read a tab's page: title, address, text, and its links, buttons and fields with a ref for each.", true, emptyList(),
            "tab" to str(TAB), "offset" to int("Skip this many elements, to page through a long page (default 0)")),
        tool("browser_click", "Click an element of the page last read.", false, listOf("ref"), "ref" to int(REF), "tab" to str(TAB), openWorld = true),
        tool("browser_type", "Type into a field of the page last read.", false, listOf("ref", "text"),
            "ref" to int(REF), "text" to str("The text to put in the field"), "submit" to bool("Submit the field's form after typing"), "tab" to str(TAB), openWorld = true),
        tool("browser_scroll", "Scroll a tab's page by one screen.", false, listOf("direction"), "direction" to enum("down", "up"), "tab" to str(TAB)),
        tool("browser_screenshot", "A picture of the active tab's page as it is on screen.", readOnly = true),
    )

    /** Runs the browser tool [name]; every call touches the WebViews on the main thread. */
    suspend fun call(name: String, input: JSONObject): CallToolResult = withContext(Dispatchers.Main) {
        runCatching { run(name, input) }.getOrElse { CallToolResult.error(it.message ?: "The browser couldn't do that.") }
    }

    private suspend fun run(name: String, input: JSONObject): CallToolResult = when (name) {
        "browser_tabs" -> ok(tabs())
        "browser_open" -> {
            val id = browser.open()
            browser.load(id, Addresses.resolve(input.getString("url"), level()))
            settled(id)
        }
        "browser_navigate" -> tab(input).let { id ->
            browser.select(id)
            browser.load(id, Addresses.resolve(input.getString("url"), level()))
            settled(id)
        }
        "browser_back" -> tab(input).let { browser.select(it); browser.back(); settled(it) }
        "browser_forward" -> tab(input).let { browser.select(it); browser.forward(); settled(it) }
        "browser_reload" -> tab(input).let { browser.select(it); browser.reload(); settled(it) }
        "browser_select" -> tab(input).let { browser.select(it); ok(tabs()) }
        "browser_close" -> tab(input).let { browser.close(it); ok(tabs()) }
        "browser_read" -> read(tab(input), input.optInt("offset", 0))
        "browser_click" -> click(tab(input), input.getInt("ref"))
        "browser_type" -> act(tab(input), "type(${input.getInt("ref")}, ${JSONObject.quote(input.getString("text"))}, ${input.optBoolean("submit", false)})")
        "browser_scroll" -> act(tab(input), "scroll(${if (input.getString("direction") == "up") -1 else 1})", navigates = false)
        "browser_screenshot" -> screenshot()
        else -> CallToolResult.error("Unknown browser tool $name.")
    }

    /** The tab a call names, or the active one. */
    private fun tab(input: JSONObject): String {
        val state = browser.state.value
        val named = input.optString("tab").takeIf(String::isNotEmpty) ?: return checkNotNull(state.active) { "No tab is open." }
        return state.tabs.firstOrNull { it.id == named }?.id ?: error("No tab $named. Call browser_tabs for the open tabs.")
    }

    private fun tabs(): JSONObject {
        val state = browser.state.value
        val list = JSONArray()
        state.tabs.forEach { list.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.label).put("active", it.id == state.active)) }
        return JSONObject().put("tabs", list)
    }

    /**
     * The tab once it has settled: a click or a submit gets [START_WAIT_MS] to begin a navigation, and a page that is
     * loading gets [LOAD_WAIT_MS] to finish. Says where the tab ended up.
     */
    private suspend fun settled(id: String): CallToolResult {
        fun tab() = browser.state.value.tabs.firstOrNull { it.id == id }
        val before = tab()?.url
        var waited = 0L
        while (waited < START_WAIT_MS && tab()?.let { !it.loading && it.url == before } == true) {
            delay(STEP_MS)
            waited += STEP_MS
        }
        waited = 0L
        while (waited < LOAD_WAIT_MS && tab()?.loading == true) {
            delay(STEP_MS)
            waited += STEP_MS
        }
        val tab = tab() ?: return CallToolResult.error("The tab closed.")
        return ok(JSONObject().put("tab", tab.id).put("url", tab.url).put("title", tab.label).put("loading", tab.loading))
    }

    private suspend fun read(id: String, offset: Int): CallToolResult {
        val page = browser.evaluate(id, "$PAGE_SCRIPT; ren.read(${offset.coerceAtLeast(0)}, $TEXT_LIMIT, $ELEMENT_LIMIT)")
            ?: return ok(JSONObject().put("tab", id).put("url", Addresses.HOME).put("title", "Home").put("text", "Ren's home page: a search box. Use browser_navigate to go somewhere."))
        return ok(JSONObject(unquote(page)).put("tab", id))
    }

    /** Runs a page action; one that can start a navigation ([navigates]) waits for the tab to settle. */
    private suspend fun act(id: String, action: String, navigates: Boolean = true): CallToolResult {
        val answer = browser.evaluate(id, "$PAGE_SCRIPT; ren.$action") ?: return CallToolResult.error("The tab is on Ren's home page; there is nothing to act on.")
        val result = JSONObject(unquote(answer))
        return when {
            !result.optBoolean("ok") -> CallToolResult.error(result.optString("error", "The page didn't take that."))
            navigates -> settled(id)
            else -> ok(JSONObject().put("tab", id))
        }
    }

    /**
     * Clicks an element the way a finger does, with a tap at its place on screen, so the page sees a real gesture (a
     * link followed this way goes into the tab's history). A tab that isn't on screen can't be touched, so its page
     * runs the element's own `click()`.
     */
    private suspend fun click(id: String, ref: Int): CallToolResult {
        val answer = browser.evaluate(id, "$PAGE_SCRIPT; ren.point($ref)") ?: return CallToolResult.error("The tab is on Ren's home page; there is nothing to act on.")
        val point = JSONObject(unquote(answer))
        if (!point.optBoolean("ok")) return CallToolResult.error(point.optString("error", "The page didn't take that."))
        // The page scrolled the element into view; the tap follows the frame that shows it there.
        awaitFrame()
        return if (browser.tap(id, point.getDouble("x").toFloat(), point.getDouble("y").toFloat())) settled(id) else act(id, "click($ref)")
    }

    private fun screenshot(): CallToolResult {
        val id = browser.state.value.active ?: return CallToolResult.error("No tab is open.")
        val png = browser.capture(id) ?: return CallToolResult.error("The active tab has no page on screen. Open Ren's browser on a page first.")
        return CallToolResult(content = listOf(ImageContent(Base64.encodeToString(png, Base64.NO_WRAP), "image/png"), TextContent(browser.state.value.current?.url.orEmpty())))
    }

    private fun ok(data: JSONObject): CallToolResult = CallToolResult.success(data.toString())

    /** evaluateJavascript hands a string result back JSON-quoted. */
    private fun unquote(value: String): String = JSONTokener(value).nextValue() as? String ?: value

    private fun tool(
        name: String, description: String, readOnly: Boolean, required: List<String> = emptyList(), vararg properties: Pair<String, JsonObject>,
        destructive: Boolean = false, openWorld: Boolean = false,
    ): Tool = Tool(
        name = name,
        description = description,
        inputSchema = ToolSchema(properties = buildJsonObject { properties.forEach { (key, schema) -> put(key, schema) } }, required = required.ifEmpty { null }),
        annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, openWorldHint = openWorld),
    )

    private fun str(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String): JsonObject = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun bool(description: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", description) }
    private fun enum(vararg values: String): JsonObject = buildJsonObject { put("type", "string"); putJsonArray("enum") { values.forEach { add(it) } } }

    companion object {
        const val TEXT_LIMIT = 8000
        const val ELEMENT_LIMIT = 60
        private const val TAB = "The tab's id from browser_tabs; the active tab when left out"
        private const val REF = "The element's ref from browser_read"
        private const val STEP_MS = 100L
        private const val START_WAIT_MS = 1_200L
        private const val LOAD_WAIT_MS = 12_000L

        /**
         * What the tools run in the page: `ren.read` lists the visible interactive elements and keeps them as the
         * page's refs; `ren.point` says where a ref is on screen (as fractions of the viewport), and `ren.click`,
         * `ren.type` and `ren.scroll` act on a ref and say whether they could.
         */
        internal val PAGE_SCRIPT = """
            window.ren = window.ren || {
              refs: [],
              seen(e) { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; },
              label(e) { return (e.innerText || e.value || e.placeholder || e.getAttribute('aria-label') || e.title || '').trim().replace(/\s+/g, ' ').slice(0, 80); },
              read(offset, textLimit, limit) {
                const all = [...document.querySelectorAll('a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[contenteditable=true]')].filter(this.seen);
                this.refs = all.slice(offset, offset + limit);
                const text = (document.body ? document.body.innerText : '');
                return JSON.stringify({
                  url: location.href, title: document.title, text: text.slice(0, textLimit), truncated: text.length > textLimit,
                  elements: this.refs.map((e, i) => ({ ref: i + 1, tag: e.tagName.toLowerCase(), type: e.getAttribute('type'), label: this.label(e), href: e.href || null })),
                  nextOffset: all.length > offset + limit ? offset + limit : null,
                });
              },
              target(ref) { const e = this.refs[ref - 1]; return e && e.isConnected && this.seen(e) ? e : null; },
              point(ref) {
                const e = this.target(ref);
                if (!e) return JSON.stringify({ ok: false, error: 'No element ' + ref + ' on the page as it is now. Read the page again.' });
                e.scrollIntoView({ block: 'center' });
                const r = e.getClientRects()[0], v = window.visualViewport;
                return JSON.stringify({ ok: true, x: (r.left + r.width / 2 - v.offsetLeft) / v.width, y: (r.top + r.height / 2 - v.offsetTop) / v.height });
              },
              click(ref) {
                const e = this.target(ref);
                if (!e) return JSON.stringify({ ok: false, error: 'No element ' + ref + ' on the page as it is now. Read the page again.' });
                e.scrollIntoView({ block: 'center' }); e.click();
                return JSON.stringify({ ok: true });
              },
              type(ref, text, submit) {
                const e = this.target(ref);
                if (!e) return JSON.stringify({ ok: false, error: 'No element ' + ref + ' on the page as it is now. Read the page again.' });
                e.scrollIntoView({ block: 'center' }); e.focus();
                if (e.isContentEditable) e.textContent = text;
                else {
                  const proto = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : e.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                  Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, text);
                }
                e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true }));
                if (submit) { if (e.form) e.form.requestSubmit(); else e.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true })); }
                return JSON.stringify({ ok: true });
              },
              scroll(direction) { window.scrollBy({ top: direction * window.innerHeight * 0.85, behavior: 'instant' }); return JSON.stringify({ ok: true }); },
            }
        """.trimIndent()
    }
}
