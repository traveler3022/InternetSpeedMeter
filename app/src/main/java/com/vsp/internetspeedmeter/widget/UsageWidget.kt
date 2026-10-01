package com.vsp.internetspeedmeter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.vsp.internetspeedmeter.MainActivity
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.room.UsageDatabase
import com.vsp.internetspeedmeter.ui.Fmt
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.PackageStore
import com.vsp.internetspeedmeter.util.UsageSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: today's mobile and Wi-Fi usage and what is left of the
 * data package. Refreshed by the service once a minute and when the app opens.
 */
class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, UsageWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch {
                val rows = try {
                    UsageDatabase.getInstance(app).usageDao().getAllOnce()
                } catch (_: Exception) {
                    return@launch
                }
                manager.updateAppWidget(ids, views(app, UsageSummary.byDay(rows)))
            }
        }

        private fun views(context: Context, byDay: Map<Int, UsageSummary.Totals>): RemoteViews {
            val today = AppCalendar.today(context)
            val day = byDay[today] ?: UsageSummary.Totals()
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.w_mobile,
                context.getString(R.string.widget_mobile_fmt, Fmt.bytes(context, day.mobile)))
            views.setTextViewText(R.id.w_wifi,
                context.getString(R.string.widget_wifi_fmt, Fmt.bytes(context, day.wifi)))

            val pkg = PackageStore.load(context)
            val status = pkg?.status(byDay, today)
            if (status != null && !status.expired) {
                views.setTextViewText(R.id.w_package,
                    context.getString(R.string.widget_package_fmt, Fmt.bytes(context, status.remaining)))
                views.setViewVisibility(R.id.w_package, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.w_package, View.GONE)
            }

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}
