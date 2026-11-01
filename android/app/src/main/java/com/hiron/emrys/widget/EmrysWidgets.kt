package com.hiron.emrys.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.hiron.emrys.MainActivity
import com.hiron.emrys.R
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.emrys
import com.hiron.emrys.ui.Quick
import com.hiron.emrys.ui.Rich
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The 4x1 bar: ask, or tap the voice key to talk. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = EmrysWidgets.render(context, manager, ids, panel = false)
}

/** The 4x2 panel: status, Emrys's latest reply, the ask bar and quick keys. */
class PanelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = EmrysWidgets.render(context, manager, ids, panel = true)
}

/** What the widgets show. */
data class WidgetSnapshot(val lastReply: String, val waiting: Int)

object EmrysWidgets {
    const val ACTION_LISTEN = "com.hiron.emrys.action.LISTEN"
    const val ACTION_TYPE = "com.hiron.emrys.action.TYPE"
    const val ACTION_QUICK = "com.hiron.emrys.action.QUICK"

    /** Keeps every placed widget in step with the runtime. */
    fun follow(context: Context, runtime: EmrysRuntime) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runtime.state.map(::snapshotOf).distinctUntilChanged().collect { refresh(app) }
        }
    }

    fun snapshotOf(state: EmrysState) = WidgetSnapshot(
        lastReply = state.messages.lastOrNull { !it.fromUser && !it.system && it.text.isNotBlank() }?.text?.let(Rich::plain).orEmpty(),
        waiting = state.approvals.size,
    )

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        render(context, manager, manager.getAppWidgetIds(ComponentName(context, SearchWidget::class.java)), panel = false)
        render(context, manager, manager.getAppWidgetIds(ComponentName(context, PanelWidget::class.java)), panel = true)
    }

    fun render(context: Context, manager: AppWidgetManager, ids: IntArray, panel: Boolean) {
        if (ids.isEmpty()) return
        val views = views(context, snapshotOf(context.emrys.state.value), panel)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    fun views(context: Context, snap: WidgetSnapshot, panel: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, if (panel) R.layout.widget_panel else R.layout.widget_search)
        val (colour, label) = when {
            snap.waiting > 0 -> R.color.emrys_amber to "${snap.waiting} waiting"
            else -> R.color.emrys_ok to "Ready"
        }
        val dot = context.getColor(colour)
        views.setInt(R.id.status, "setColorFilter", dot)
        // Widgets can't take typing, so the bar opens the composer in the app.
        views.setOnClickPendingIntent(R.id.ask, open(context, ACTION_TYPE, 1))
        views.setOnClickPendingIntent(R.id.head, open(context, null, 0))
        views.setOnClickPendingIntent(R.id.mic, open(context, ACTION_LISTEN, 2))
        if (panel) {
            views.setTextViewText(R.id.status_text, label)
            views.setTextViewText(
                R.id.last,
                when {
                    snap.waiting > 0 -> context.resources.getQuantityString(R.plurals.widget_needs_ok, snap.waiting, snap.waiting)
                    snap.lastReply.isNotBlank() -> snap.lastReply
                    else -> context.getString(R.string.widget_waiting)
                },
            )
            views.setOnClickPendingIntent(R.id.last, open(context, null, 0))
            mapOf(
                R.id.key_catchup to Quick.CatchUp, R.id.key_myday to Quick.MyDay,
                R.id.key_texts to Quick.Texts, R.id.key_calls to Quick.Calls,
            ).forEach { (id, quick) ->
                views.setOnClickPendingIntent(id, open(context, ACTION_QUICK, 10 + quick.ordinal, Uri.parse("emrys://quick/${quick.name}")))
            }
        }
        return views
    }

    private fun open(context: Context, action: String?, code: Int, data: Uri? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(action).setData(data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
