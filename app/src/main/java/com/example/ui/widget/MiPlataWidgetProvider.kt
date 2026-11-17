package com.example.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.domain.CopFormatter

/**
 * Widget nativo de pantalla de inicio para MiPlata:
 * Muestra "Hoy puedes gastar $X" en COP y tiene un botón directo "+ Registrar"
 * que abre la hoja de Registro Rápido en máximo 3 toques.
 */
class MiPlataWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val EXTRA_OPEN_QUICK_REGISTER = "EXTRA_OPEN_QUICK_REGISTER"
        private const val WIDGET_PREFS = "mi_plata_widget_cache"

        /**
         * Guarda el último disponible por día calculado y refresca todos los widgets activos.
         */
        fun pushWidgetUpdate(
            context: Context,
            availablePerDay: Long,
            periodSubtitle: String,
            hideBalances: Boolean
        ) {
            context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("available_per_day", availablePerDay)
                .putString("period_subtitle", periodSubtitle)
                .putBoolean("hide_balances", hideBalances)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MiPlataWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    updateAppWidget(context, manager, id)
                }
            }
        }

        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            val availableToday = prefs.getLong("available_per_day", 0L)
            val subtitle = prefs.getString("period_subtitle", "MiPlata • Finanzas en COP") ?: "MiPlata"
            val hideBalances = prefs.getBoolean("hide_balances", false)

            val views = RemoteViews(context.packageName, R.layout.widget_mi_plata)
            val formattedAmount = CopFormatter.format(availableToday, hideBalance = hideBalances)

            views.setTextViewText(R.id.widget_amount, formattedAmount)
            views.setTextColor(
                R.id.widget_amount,
                if (availableToday < 0L) Color.parseColor("#FCA5A5") else Color.WHITE
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                if (availableToday < 0L) "TE PASASTE DEL DISPONIBLE" else "HOY PUEDES GASTAR"
            )
            views.setTextViewText(R.id.widget_period_info, subtitle)

            // Intent para abrir la app normalmente al tocar la tarjeta
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                100,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPending)

            // Intent para abrir directamente la hoja de Registro Rápido al tocar "+ Registrar"
            val quickAddIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_QUICK_REGISTER, true)
            }
            val quickAddPending = PendingIntent.getActivity(
                context,
                101,
                quickAddIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_quick_add_btn, quickAddPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
