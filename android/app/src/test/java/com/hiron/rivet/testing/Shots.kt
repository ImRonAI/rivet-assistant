package com.hiron.rivet.testing

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.hiron.rivet.ui.STAGE_TAG
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a screenshot of [activity] to build/screens/[name].png, with every window in z-order (the activity, then
 * sheets and dialogs on top of it).
 */
fun shot(rule: ComposeTestRule, activity: Activity, name: String) {
    rule.waitForIdle()
    val dir = File(System.getProperty("rivet.screenshots") ?: "build/screens").apply { mkdirs() }
    val decor = activity.window.decorView
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    rule.runOnUiThread {
        val canvas = Canvas(bitmap)
        for (view in windowRoots()) {
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            canvas.save()
            canvas.translate(at[0].toFloat(), at[1].toFloat())
            view.draw(canvas)
            canvas.restore()
        }
    }
    FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    // The robot WebView draws nothing under Robolectric; record where its stage is so a review can place it.
    rule.onAllNodesWithTag(STAGE_TAG).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.let { b ->
        File(dir, "$name.stage.json").writeText("""{"left":${b.left},"top":${b.top},"right":${b.right},"bottom":${b.bottom}}""")
    }
}

/** The root view of every window on screen, in z-order. */
internal fun windowRoots(): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
    return views.filterIsInstance<View>().filter { it.isShown && it.width > 0 }
}
