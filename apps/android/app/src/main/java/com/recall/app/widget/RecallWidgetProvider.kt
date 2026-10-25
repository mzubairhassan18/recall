package com.recall.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.recall.app.MainActivity
import com.recall.app.R
import com.recall.app.Session
import com.recall.app.config.AppConfig
import com.recall.app.data.toHit
import com.recall.core.Snippets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RecallWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val views = build(context)
                for (id in appWidgetIds) manager.updateAppWidget(id, views)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recall)
        val config = AppConfig(context)
        val repository = Session.repository()

        val body: String
        val footer: String
        when {
            !config.isConfigured -> {
                body = "Set up Recall to start remembering."
                footer = "Tap to set up"
            }
            repository == null -> {
                body = context.getString(R.string.widget_locked)
                footer = "Tap to unlock"
            }
            else -> {
                val recent = runCatching { repository.recent(3) }.getOrDefault(emptyList())
                body = if (recent.isEmpty()) {
                    context.getString(R.string.widget_empty)
                } else {
                    recent.joinToString("\n") { entity ->
                        "• " + Snippets.displayTitle(entity.toHit()).take(80)
                    }
                }
                val count = runCatching { repository.count() }.getOrDefault(0)
                footer = "$count saved · tap to open"
            }
        }

        views.setTextViewText(R.id.widget_body, body)
        views.setTextViewText(R.id.widget_footer, footer)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        return views
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, RecallWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val intent = Intent(context, RecallWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
