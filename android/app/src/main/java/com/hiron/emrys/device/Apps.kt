package com.hiron.emrys.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import com.hiron.emrys.service.Notifications
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Launching apps, handing drafts to the mail app, and the phone's own status. */
class Apps(private val context: Context, private val isVisible: () -> Boolean) {

    private data class App(val label: String, val pkg: String)

    fun list(query: String?): ToolResult {
        val needle = query?.lowercase()
        val apps = launcherApps().filter { needle == null || it.label.lowercase().contains(needle) || it.pkg.contains(needle) }
        return ToolResult.ok(
            JSONObject().put("apps", JSONArray(apps.take(200).map { JSONObject().put("name", it.label).put("package", it.pkg) })),
        )
    }

    fun open(app: String): ToolResult {
        val all = launcherApps()
        val needle = app.trim().lowercase()
        val match = all.firstOrNull { it.pkg == app.trim() }
            ?: all.firstOrNull { it.label.lowercase() == needle }
            ?: all.firstOrNull { it.label.lowercase().startsWith(needle) }
            ?: all.firstOrNull { it.label.lowercase().contains(needle) }
            ?: return ToolResult.error("No installed app matches \"$app\". Use apps_list to see what's installed.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ToolResult.error("${match.label} can't be launched.")
        return launch(intent, "Open ${match.label}", "Emrys is ready to open ${match.label}.")
            .let { how -> ToolResult.ok(JSONObject().put("app", match.label).put("package", match.pkg).put("opened", how)) }
    }

    fun emailDraft(to: String, subject: String, body: String): ToolResult {
        val uri = Uri.parse("mailto:" + Uri.encode(to.trim()).replace("%40", "@").replace("%2C", ","))
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, to.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return ToolResult.error("No email app is installed.")
        val how = launch(intent, "Email draft: ${subject.ifBlank { "(no subject)" }}", "To ${to.ifBlank { "(no recipient)" }}. Tap to review and send.")
        return ToolResult.ok(
            JSONObject().put("draft", true).put("delivered", how)
                .put("note", "The draft is in the user's mail app; it is sent only when they press Send there."),
        )
    }

    fun status(): ToolResult {
        val battery = context.getSystemService(BatteryManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return ToolResult.ok(
            JSONObject()
                .put("time", Times.format(System.currentTimeMillis()))
                .put("timezone", ZoneId.systemDefault().id)
                .put("battery_percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: JSONObject.NULL)
                .put("charging", battery?.isCharging ?: JSONObject.NULL)
                .put("network", network)
                .put("notification_access", EmrysNotificationListener.connected)
                .put("app_operator", ScreenOperator.connected),
        )
    }

    /**
     * Starts [intent] directly when Android allows it (Emrys on screen, or its accessibility service bound),
     * otherwise posts a notification the user taps. Returns "opened" or "notification".
     */
    private fun launch(intent: Intent, title: String, text: String): String {
        if (isVisible() || ScreenOperator.connected) {
            try {
                context.startActivity(intent)
                return "opened"
            } catch (e: ActivityNotFoundException) {
                throw SecurityException("no app can handle this")
            } catch (e: RuntimeException) {
                // Background launch refused; fall back to a notification.
            }
        }
        Notifications.action(context, title, text, intent)
        return "notification"
    }

    private fun launcherApps(): List<App> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
