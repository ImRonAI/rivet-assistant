package com.hiron.ren.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.hiron.ren.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ren's app operator: reads the screen as a numbered list of elements and taps, types or scrolls on them.
 *
 * Every action names both the element number from the last [read] and its visible label; the phone refuses
 * the action if the label no longer matches, so the brain's approval (which keys on the label, see
 * device_tools.py) always describes what is really tapped. Password fields are never read or typed into.
 * While Ren operates, a Stop button floats at the edge of every app; pressing it refuses all actions until the
 * user next speaks or types to Ren ([resume]).
 */
class ScreenOperator : AccessibilityService() {

    private var overlay: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { removeOverlay() }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun showOverlay() = main.post {
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, OVERLAY_MS)
        if (overlay != null) return@post
        val view = TextView(this).apply {
            text = getString(R.string.operator_stop)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(36, 22, 36, 22)
            background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.parseColor("#E5484D")) }
            setOnClickListener {
                stopped = true
                removeOverlay()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // The right edge, off the toolbars and tab bars where apps put their buttons.
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        // The service can be unbound between the post and now (turned off, or paused by a UI automation session);
        // its window token is gone then, and adding the view would take the whole app down.
        if (instance !== this) return@post
        val added = runCatching { getSystemService(WindowManager::class.java)?.addView(view, params) }
        if (added.isSuccess) overlay = view
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    companion object {
        private const val MAX_NODES = 150
        private const val OVERLAY_MS = 120_000L
        private const val READ_WAIT_MS = 4_000L
        private const val QUIET_MS = 400L
        private const val RETRY_MS = 350L

        // The running service, cleared in onDestroy; the system owns its lifetime.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ScreenOperator? = null

        @Volatile
        private var stopped = false

        @Volatile
        private var lastEventAt = 0L

        private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

        val connected: Boolean get() = instance != null

        /** The user spoke or typed to Ren again, which is them asking: lifts a Stop. */
        fun resume() {
            stopped = false
        }

        /**
         * Reads the screen once it has settled. An app that was just opened is still drawing, and Chrome (web apps
         * included) fills a page's elements in only after the first read, so a screen with at most one element is
         * read again until it fills in or [READ_WAIT_MS] passes.
         */
        suspend fun read(): ToolResult = withContext(Dispatchers.Main) {
            val service = instance ?: return@withContext notConnected()
            stopped()?.let { return@withContext it }
            service.showOverlay()
            val deadline = SystemClock.uptimeMillis() + READ_WAIT_MS
            var screen = snapshot(service, deadline)
            while ((screen == null || screen.nodes.size <= 1) && SystemClock.uptimeMillis() < deadline) {
                delay(RETRY_MS)
                screen = snapshot(service, deadline) ?: screen
            }
            if (screen == null) return@withContext ToolResult.error("No app window is readable right now.")
            lastNodes = screen.nodes
            ToolResult.ok(
                JSONObject()
                    .put("app", screen.app)
                    .put("elements", screen.rows)
                    .put("truncated", screen.nodes.size >= MAX_NODES)
                    .put("note", "Act with screen_act using an element's number and its label exactly as shown."),
            )
        }

        private class Screen(val app: String, val nodes: List<AccessibilityNodeInfo>, val rows: JSONArray)

        /** The app in front, read after [QUIET_MS] without window changes (or at [deadline]). */
        private suspend fun snapshot(service: ScreenOperator, deadline: Long): Screen? {
            while (SystemClock.uptimeMillis() - lastEventAt < QUIET_MS && SystemClock.uptimeMillis() < deadline) delay(50)
            val root = service.rootInActiveWindow
                ?: service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
                ?: return null
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val rows = JSONArray()
            collect(root, nodes, rows)
            return Screen(root.packageName?.toString().orEmpty(), nodes, rows)
        }

        suspend fun act(action: String, node: Int?, label: String?, text: String?): ToolResult = withContext(Dispatchers.Main) {
            val service = instance ?: return@withContext notConnected()
            stopped()?.let { return@withContext it }
            service.showOverlay()
            val result = when (action) {
                "back" -> global(service, AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> global(service, AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> global(service, AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> global(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "tap", "type", "scroll_down", "scroll_up" -> {
                    // Without an element, a scroll swipes the whole screen.
                    val target = if (node == null && action.startsWith("scroll_")) null else target(node, label)
                    when (target) {
                        null -> swipe(service, null, action == "scroll_down")
                        is ToolResult -> target
                        else -> onNode(service, action, target as AccessibilityNodeInfo, text)
                    }
                }
                else -> ToolResult.error("Unknown action \"$action\". Use tap, type, scroll_down, scroll_up, back, home, recents or notifications.")
            }
            // Let the app react before the model reads the screen again.
            delay(600)
            result
        }

        /**
         * The whole label as read, so the label the approval was judged on is the one tapped. An element read with no
         * label (a list, a map pin, a photo tile) is named by its number alone.
         */
        internal fun sameLabel(shown: String, label: String?): Boolean = shown.equals(label.orEmpty().trim(), ignoreCase = true)

        private fun target(node: Int?, label: String?): Any {
            val i = node ?: return ToolResult.error("Say which element to act on (its number from screen_read).")
            val n = lastNodes.getOrNull(i) ?: return ToolResult.error("Element $i is not on the last screen read. Read the screen again.")
            if (!n.refresh()) return ToolResult.error("Element $i is gone. The screen changed; read it again.")
            val shown = labelOf(n)
            if (!sameLabel(shown, label)) {
                return ToolResult.error("Element $i now reads \"$shown\", not \"${label.orEmpty()}\". The screen changed; read it again.")
            }
            return n
        }

        private fun onNode(service: ScreenOperator, action: String, n: AccessibilityNodeInfo, text: String?): ToolResult = when (action) {
            "tap" -> {
                val clickable = generateSequence(n) { it.parent }.firstOrNull { it.isClickable }
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    ToolResult.ok(JSONObject().put("tapped", labelOf(n)))
                } else {
                    tapAt(service, n)
                }
            }
            "type" -> when {
                n.isPassword -> ToolResult.error("Ren never types into password fields. Ask the user to enter it.")
                !n.isEditable -> ToolResult.error("Element \"${labelOf(n)}\" is not a text field.")
                text == null -> ToolResult.error("Say what text to type.")
                else -> {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                    if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        ToolResult.ok(JSONObject().put("typed", text.length))
                    } else {
                        ToolResult.error("The field didn't accept text.")
                    }
                }
            }
            else -> {
                val forward = action == "scroll_down"
                val scrollable = generateSequence(n) { it.parent }.firstOrNull { it.isScrollable }
                val op = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                // Web pages and custom views often scroll without saying so: swipe over the element instead.
                if (scrollable?.performAction(op) == true) ToolResult.ok(JSONObject().put("scrolled", if (forward) "down" else "up"))
                else swipe(service, n, forward)
            }
        }

        /** Swipes over [n], or the middle of the screen without one, so the content moves by most of a page. */
        private fun swipe(service: ScreenOperator, n: AccessibilityNodeInfo?, forward: Boolean): ToolResult {
            val metrics = service.resources.displayMetrics
            val r = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            n?.getBoundsInScreen(r)
            if (r.height() < metrics.heightPixels / 4) r.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            val x = r.exactCenterX()
            val low = r.top + r.height() * 0.75f
            val high = r.top + r.height() * 0.25f
            val path = Path().apply {
                moveTo(x, if (forward) low else high)
                lineTo(x, if (forward) high else low)
            }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
            return if (service.dispatchGesture(gesture, null, null)) ToolResult.ok(JSONObject().put("scrolled", if (forward) "down" else "up"))
            else ToolResult.error("Android refused the swipe.")
        }

        private fun tapAt(service: ScreenOperator, n: AccessibilityNodeInfo): ToolResult {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty) return ToolResult.error("That element has no position on screen.")
            val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
            return if (service.dispatchGesture(gesture, null, null)) ToolResult.ok(JSONObject().put("tapped", labelOf(n)))
            else ToolResult.error("Android refused the tap.")
        }

        private fun global(service: ScreenOperator, action: Int): ToolResult =
            if (service.performGlobalAction(action)) ToolResult.ok(JSONObject().put("done", true))
            else ToolResult.error("Android refused that action.")

        private fun collect(node: AccessibilityNodeInfo, nodes: MutableList<AccessibilityNodeInfo>, rows: JSONArray) {
            if (nodes.size >= MAX_NODES || !node.isVisibleToUser) return
            val label = labelOf(node)
            val interesting = label.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable
            if (interesting) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                rows.put(
                    JSONObject()
                        .put("n", nodes.size)
                        .put("label", label)
                        .put("kind", node.className?.toString()?.substringAfterLast('.').orEmpty())
                        .apply {
                            if (node.isClickable) put("tap", true)
                            if (node.isEditable) put("edit", true)
                            if (node.isScrollable) put("scroll", true)
                            if (AccessibilityNodeInfoCompat.wrap(node).checked == AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE) put("checked", true)
                            if (node.isPassword) put("password", true)
                        }
                        .put("at", "${r.centerX()},${r.centerY()}"),
                )
                nodes += node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, nodes, rows) }
        }

        private fun labelOf(n: AccessibilityNodeInfo): String {
            if (n.isPassword) return "(password field)"
            val text = n.text?.toString().orEmpty().ifBlank { n.contentDescription?.toString().orEmpty() }
                .ifBlank { n.hintText?.toString().orEmpty() }
            return text.replace(Regex("\\s+"), " ").trim().take(120)
        }

        private fun stopped(): ToolResult? =
            if (stopped) ToolResult.error("The user pressed Stop. Don't operate apps again until they ask you to.") else null

        private fun notConnected() = ToolResult.error(
            "Ren can't operate apps yet. Ask the user to open Ren › Settings › What Ren can use › App operator and turn on " +
                "Ren's accessibility service.",
        )
    }
}
