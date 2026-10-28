package com.hiron.rivet.widget

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
import com.hiron.rivet.R
import com.hiron.rivet.core.Connection
import com.hiron.rivet.ui.Quick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The launcher-facing art: the adaptive icon and both home-screen widgets, drawn with Android's own renderer
 * and saved to build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LauncherArtTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(System.getProperty("rivet.screenshots") ?: "build/screens").apply { mkdirs() }

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

    @Test
    fun bothWidgetsInflateWithEveryControlWired() {
        val snap = WidgetSnapshot(Connection.Online, "Tomorrow you have a dentist cleaning at 9 AM. I texted Dana that Friday works.", 0)
        render(RivetWidgets.views(context, snap, panel = false), 360f, 72f, "widget-search").let { root ->
            listOf(R.id.ask, R.id.mic, R.id.head).forEach { assertTrue(root.findViewById<View>(it).hasOnClickListeners()) }
        }
        render(RivetWidgets.views(context, snap, panel = true), 360f, 200f, "widget-panel").let { root ->
            assertEquals(snap.lastReply, root.findViewById<TextView>(R.id.last).text.toString())
            assertEquals("ONLINE", root.findViewById<TextView>(R.id.status_text).text.toString())
            listOf(R.id.ask, R.id.mic, R.id.last, R.id.key_catchup, R.id.key_myday, R.id.key_texts, R.id.key_calls)
                .forEach { assertTrue(root.findViewById<View>(it).hasOnClickListeners()) }
        }
        val waiting = render(RivetWidgets.views(context, snap.copy(waiting = 2, lastReply = ""), panel = true), 360f, 200f, "widget-panel-waiting")
        assertEquals("2 WAITING", waiting.findViewById<TextView>(R.id.status_text).text.toString())
        assertEquals("Rivet is waiting on your OK for 2 actions. Tap to review them.", waiting.findViewById<TextView>(R.id.last).text.toString())
    }

    @Test
    fun theSmallestSizesStillFitAndSayTheStatusPlainly() {
        val snap = WidgetSnapshot(Connection.Connecting, "", 0)
        render(RivetWidgets.views(context, snap, panel = false), 250f, 64f, "widget-search-narrow")
        val panel = render(RivetWidgets.views(context, snap, panel = true), 250f, 200f, "widget-panel-narrow")
        assertEquals("CONNECTING…", panel.findViewById<TextView>(R.id.status_text).text.toString())
        assertEquals(context.getString(R.string.widget_waiting), panel.findViewById<TextView>(R.id.last).text.toString())
        val offline = render(RivetWidgets.views(context, snap.copy(connection = Connection.Offline), panel = true), 250f, 200f, "widget-panel-offline")
        assertEquals("OFFLINE", offline.findViewById<TextView>(R.id.status_text).text.toString())
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
