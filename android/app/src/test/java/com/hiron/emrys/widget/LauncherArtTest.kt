package com.hiron.emrys.widget

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.hiron.emrys.MainActivity
import com.hiron.emrys.R
import com.hiron.emrys.core.Connection
import com.hiron.emrys.service.CallNote
import com.hiron.emrys.service.Notifications
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.ui.Quick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The launcher-facing art: the adaptive icon, the notification icon, the shortcuts, both home-screen widgets and
 * the notifications, drawn with Android's own renderer and saved to build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LauncherArtTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(System.getProperty("emrys.screenshots") ?: "build/screens").apply { mkdirs() }

    private fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()

    private fun save(bitmap: Bitmap, name: String) = FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun wallpaper(canvas: Canvas, w: Int, h: Int) {
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), intArrayOf(0xFF2C3E50.toInt(), 0xFF4B6584.toInt(), 0xFFA5917B.toInt()), null, Shader.TileMode.CLAMP) })
    }

    @Test
    fun theAdaptiveIconHasAllThreeLayers() {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertTrue(icon.background != null && icon.foreground != null && icon.monochrome != null)

        val size = px(108f)
        val sheet = Bitmap.createBitmap(size * 3 + px(48f), size + px(32f), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        wallpaper(canvas, sheet.width, sheet.height)
        // Circle, rounded square and squircle masks, as different launchers apply them.
        val masks = listOf<(Path, Float) -> Unit>(
            { p, s -> p.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW) },
            { p, s -> p.addRoundRect(0f, 0f, s, s, s * 0.22f, s * 0.22f, Path.Direction.CW) },
            { p, s -> p.addRoundRect(0f, 0f, s, s, s * 0.42f, s * 0.42f, Path.Direction.CW) },
        )
        masks.forEachIndexed { i, mask ->
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(layer)
            val clip = Path().also { mask(it, size.toFloat()) }
            c.clipPath(clip)
            // The visible icon is the middle 72 of the 108dp layers.
            val bleed = size / 4
            icon.background.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            icon.foreground.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            icon.background.draw(c)
            icon.foreground.draw(c)
            canvas.drawBitmap(layer, (px(16f) + i * (size + px(8f))).toFloat(), px(16f).toFloat(), null)
        }
        save(sheet, "icon-launcher")
    }

    /** The icon at the sizes it must read at: 48 px and 192 px, full colour and themed, with the 48 px one enlarged. */
    @Test
    fun theIconReadsAtLauncherSizes() {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val gap = 24
        val zoom = 4
        // A dark and a light Material You tint pair, as a launcher applies them to the themed (monochrome) layer.
        val themes = listOf(null, 0xFF2A2E45.toInt() to 0xFFD9DEFF.toInt(), 0xFFDDE1FF.toInt() to 0xFF2A3166.toInt())
        val rowH = 192 + gap * 2
        val sheet = Bitmap.createBitmap(gap + 48 + gap + 192 + gap + 48 * zoom + gap, rowH * themes.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        wallpaper(canvas, sheet.width, sheet.height)
        themes.forEachIndexed { row, theme ->
            val top = row * rowH + gap
            val small = adaptive(icon, 48, theme)
            canvas.drawBitmap(small, gap.toFloat(), (top + 72).toFloat(), null)
            canvas.drawBitmap(adaptive(icon, 192, theme), (gap + 48 + gap).toFloat(), top.toFloat(), null)
            canvas.drawBitmap(Bitmap.createScaledBitmap(small, 48 * zoom, 48 * zoom, false), (gap + 48 + gap + 192 + gap).toFloat(), top.toFloat(), null)
        }
        save(sheet, "icon-sizes")
    }

    /**
     * The Android 12+ splash as the platform lays it out: the icon's foreground at 240 dp on the window's noir,
     * inside the 160 dp circle an icon background allows.
     */
    @Test
    fun theSplashIsTheSigilOnNoir() {
        val w = px(411f)
        val h = px(891f)
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(context.getColor(R.color.emrys_bg))
        val icon = px(240f)
        val left = (w - icon) / 2
        val top = (h - icon) / 2
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(w / 2f, h / 2f, px(80f).toFloat(), Path.Direction.CW) })
        context.getDrawable(R.drawable.ic_launcher_foreground)!!.apply { setBounds(left, top, left + icon, top + icon) }.draw(canvas)
        canvas.restore()
        save(sheet, "splash")
    }

    /**
     * The status-bar icon at 24 dp on mdpi to xxhdpi and at the notification header's 18 dp on xxhdpi, light on dark
     * and dark on light, then the 24 px and 54 px ones enlarged pixel for pixel.
     */
    @Test
    fun theNotificationIconReadsInTheStatusBar() {
        val sizes = listOf(24, 36, 48, 54, 72)
        val gap = 24
        val zoom = 4
        val stripH = 72 + gap * 2
        val zoomH = 54 * zoom + gap * 2
        val sheet = Bitmap.createBitmap(maxOf(gap + sizes.sumOf { it + gap }, gap + (24 + 54) * zoom + gap * 2), stripH * 2 + zoomH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        fun icon(s: Int, fg: Int, bg: Int) = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(bg)
            context.getDrawable(R.drawable.ic_emrys)!!.mutate().apply { setTint(fg); setBounds(0, 0, s, s) }.draw(c)
        }
        listOf(0xFF000000.toInt() to 0xFFFFFFFF.toInt(), 0xFFF2F1F6.toInt() to 0xFF1B1B1F.toInt()).forEachIndexed { row, (bg, fg) ->
            canvas.drawRect(0f, (row * stripH).toFloat(), sheet.width.toFloat(), ((row + 1) * stripH).toFloat(), Paint().apply { color = bg })
            var x = gap
            sizes.forEach { s ->
                canvas.drawBitmap(icon(s, fg, bg), x.toFloat(), (row * stripH + gap + (72 - s) / 2).toFloat(), null)
                x += s + gap
            }
        }
        canvas.drawRect(0f, (stripH * 2).toFloat(), sheet.width.toFloat(), sheet.height.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        var x = gap
        listOf(24, 54).forEach { s ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(icon(s, 0xFFFFFFFF.toInt(), 0xFF000000.toInt()), s * zoom, s * zoom, false), x.toFloat(), (stripH * 2 + gap).toFloat(), null)
            x += s * zoom + gap
        }
        save(sheet, "icon-notification")
    }

    /** The long-press shortcuts and the marks the widgets and the app draw, at their sizes, on noir. */
    @Test
    fun theShortcutsAndMarksDrawOnNoir() {
        val shortcuts = listOf(R.drawable.shortcut_talk, R.drawable.shortcut_type, R.drawable.shortcut_catchup, R.drawable.shortcut_myday)
        val gap = px(16f)
        val s = px(48f)
        val sheet = Bitmap.createBitmap(gap + (s + gap) * 6, s + gap * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF0A0A0C.toInt())
        (shortcuts + R.drawable.emrys_head + R.drawable.emrys_sigil).forEachIndexed { i, id ->
            val d = context.getDrawable(id)!!.mutate()
            if (id == R.drawable.emrys_sigil) d.setTint(context.getColor(R.color.emrys_accent))
            val x = gap + i * (s + gap)
            d.setBounds(x, gap, x + s, gap + s)
            d.draw(canvas)
        }
        save(sheet, "icon-shortcuts")
    }

    /** Emrys's notifications each carry the sigil and the halo accent. */
    @Test
    fun notificationsCarryTheSigilAndTheHalo() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        Notifications.approval(context, textApproval("a1"), names = mapOf("+18015550100" to "Dana"))
        Notifications.callAnswering(context, "c-0123456789ab", CallNote("Ada · +1 801-555-0100", "Lunch Friday?"))
        Notifications.reminder(context, "r1", "Stretch")
        val posted = manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        assertEquals(3, posted.size)
        posted.forEach {
            assertEquals(context.getColor(R.color.emrys_accent), it.color)
            assertEquals(R.drawable.ic_emrys, it.smallIcon.resId)
        }
        val service = Notifications.service(context, "Connected and listening for tasks")
        assertEquals("Emrys", service.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    private fun adaptive(icon: AdaptiveIconDrawable, size: Int, theme: Pair<Int, Int>?): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) })
        val bleed = size / 4
        if (theme == null) {
            listOf(icon.background, icon.foreground).forEach { it.setBounds(-bleed, -bleed, size + bleed, size + bleed); it.draw(c) }
        } else {
            c.drawColor(theme.first)
            val mono = icon.monochrome!!.mutate()
            mono.setTint(theme.second)
            mono.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            mono.draw(c)
        }
        return out
    }

    @Test
    fun bothWidgetsInflateWithEveryControlWired() {
        val snap = WidgetSnapshot(Connection.Online, "Tomorrow you have a dentist cleaning at 9 AM. I texted Dana that Friday works.", 0)
        render(EmrysWidgets.views(context, snap, panel = false), 360f, 72f, "widget-search").let { root ->
            listOf(R.id.ask, R.id.mic, R.id.head).forEach { assertTrue(root.findViewById<View>(it).hasOnClickListeners()) }
        }
        render(EmrysWidgets.views(context, snap, panel = true), 360f, 200f, "widget-panel").let { root ->
            assertEquals(snap.lastReply, root.findViewById<TextView>(R.id.last).text.toString())
            assertEquals("Online", root.findViewById<TextView>(R.id.status_text).text.toString())
            listOf(R.id.ask, R.id.mic, R.id.last, R.id.key_catchup, R.id.key_myday, R.id.key_texts, R.id.key_calls)
                .forEach { assertTrue(root.findViewById<View>(it).hasOnClickListeners()) }
        }
        val waiting = render(EmrysWidgets.views(context, snap.copy(waiting = 2, lastReply = ""), panel = true), 360f, 200f, "widget-panel-waiting")
        assertEquals("2 waiting", waiting.findViewById<TextView>(R.id.status_text).text.toString())
        assertEquals("Emrys is waiting on your OK for 2 actions. Tap to review them.", waiting.findViewById<TextView>(R.id.last).text.toString())
    }

    @Test
    fun theSearchBarOpensTheSearchSheet() {
        val snap = WidgetSnapshot(Connection.Online, "", 0)
        for (panel in listOf(false, true)) {
            val root = render(EmrysWidgets.views(context, snap, panel), 360f, if (panel) 200f else 72f, if (panel) "widget-panel" else "widget-search")
            assertEquals(context.getString(R.string.widget_ask), root.findViewById<TextView>(R.id.ask).text.toString())
            root.findViewById<View>(R.id.ask).performClick()
            val started = shadowOf(context as android.app.Application).nextStartedActivity
            assertEquals(EmrysWidgets.ACTION_SEARCH, started.action)
            assertEquals(MainActivity::class.java.name, started.component?.className)
        }
        assertEquals("Search or ask Emrys", context.getString(R.string.widget_ask))
    }

    @Test
    fun theSmallestSizesStillFitAndSayTheStatusPlainly() {
        val snap = WidgetSnapshot(Connection.Connecting, "", 0)
        render(EmrysWidgets.views(context, snap, panel = false), 250f, 64f, "widget-search-narrow")
        val panel = render(EmrysWidgets.views(context, snap, panel = true), 250f, 200f, "widget-panel-narrow")
        assertEquals("Connecting…", panel.findViewById<TextView>(R.id.status_text).text.toString())
        assertEquals(context.getString(R.string.widget_waiting), panel.findViewById<TextView>(R.id.last).text.toString())
        val offline = render(EmrysWidgets.views(context, snap.copy(connection = Connection.Offline), panel = true), 250f, 200f, "widget-panel-offline")
        assertEquals("Offline", offline.findViewById<TextView>(R.id.status_text).text.toString())
    }

    @Test
    fun quickKeysOnlyResolveTheirOwnNames() {
        assertEquals(Quick.CatchUp, Quick.of("CatchUp"))
        assertEquals(Quick.MyDay, Quick.of("myday"))
        assertNull(Quick.of("Text Mom that I'm running late"))
        assertNull(Quick.of(null))
    }

    private fun render(views: android.widget.RemoteViews, wDp: Float, hDp: Float, name: String): View {
        val host = FrameLayout(context)
        val root = views.apply(context, host)
        host.addView(root, FrameLayout.LayoutParams(px(wDp), px(hDp)))
        val pad = px(16f)
        host.measure(View.MeasureSpec.makeMeasureSpec(px(wDp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(hDp), View.MeasureSpec.EXACTLY))
        host.layout(0, 0, px(wDp), px(hDp))
        val bitmap = Bitmap.createBitmap(px(wDp) + pad * 2, px(hDp) + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wallpaper(canvas, bitmap.width, bitmap.height)
        canvas.translate(pad.toFloat(), pad.toFloat())
        host.draw(canvas)
        save(bitmap, name)
        return root
    }
}
