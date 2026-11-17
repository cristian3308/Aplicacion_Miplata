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
import com.example.domain.AvailableToSpendResult
import com.example.domain.CopFormatter

data class WidgetDisplayData(
    val title: String,
    val mainAmountText: String,
    val periodSubtitleText: String,
    val availablePerDay: Long,
    val availableForPeriod: Long,
    val periodLabel: String,
    val hideBalances: Boolean
)

/**
 * Widget nativo de pantalla de inicio para MiPlata:
 * Muestra el número grande `availablePerDay` con el título "Hoy puedes gastar",
 * y debajo "Te quedan $X para <período>", respetando "ocultar saldos".
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

        fun buildWidgetDisplayData(
            available: AvailableToSpendResult,
            hideBalances: Boolean
        ): WidgetDisplayData {
            return formatWidgetDisplayData(
                availablePerDay = available.availablePerDay,
                availableForPeriod = available.availableForPeriod,
                periodLabel = available.periodLabel,
                hideBalances = hideBalances
            )
        }

        fun formatWidgetDisplayData(
            availablePerDay: Long,
            availableForPeriod: Long,
            periodLabel: String,
            hideBalances: Boolean
        ): WidgetDisplayData {
            val mainAmountText = CopFormatter.format(availablePerDay, hideBalance = hideBalances)
            val remainingText = CopFormatter.format(availableForPeriod, hideBalance = hideBalances)
            val safePeriod = periodLabel.ifBlank { "Este mes" }
            return WidgetDisplayData(
                title = "Hoy puedes gastar",
                mainAmountText = mainAmountText,
                periodSubtitleText = "Te quedan $remainingText para $safePeriod",
                availablePerDay = availablePerDay,
                availableForPeriod = availableForPeriod,
                periodLabel = safePeriod,
                hideBalances = hideBalances
            )
        }

        /**
         * Guarda el último disponible por día y del período calculados y refresca todos los widgets activos.
         */
        fun pushWidgetUpdate(
            context: Context,
            availablePerDay: Long,
            availableForPeriod: Long,
            periodLabel: String,
            hideBalances: Boolean
        ) {
            val display = formatWidgetDisplayData(
                availablePerDay = availablePerDay,
                availableForPeriod = availableForPeriod,
                periodLabel = periodLabel,
                hideBalances = hideBalances
            )
            context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("available_per_day", display.availablePerDay)
                .putLong("available_for_period", display.availableForPeriod)
                .putString("period_label", display.periodLabel)
                .putString("period_subtitle", display.periodSubtitleText)
                .putBoolean("hide_balances", display.hideBalances)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MiPlataWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    updateAppWidget(context, manager, id)
                }
            }
        }

        fun readCachedWidgetDisplayData(context: Context): WidgetDisplayData {
            val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            val availablePerDay = prefs.getLong("available_per_day", 0L)
            val availableForPeriod = prefs.getLong("available_for_period", 0L)
            val periodLabel = prefs.getString("period_label", "Este mes") ?: "Este mes"
            val hideBalances = prefs.getBoolean("hide_balances", false)
            return formatWidgetDisplayData(
                availablePerDay = availablePerDay,
                availableForPeriod = availableForPeriod,
                periodLabel = periodLabel,
                hideBalances = hideBalances
            )
        }

        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val display = readCachedWidgetDisplayData(context)

            val views = RemoteViews(context.packageName, R.layout.widget_mi_plata)

            views.setTextViewText(R.id.widget_subtitle, display.title)
            views.setTextViewText(R.id.widget_amount, display.mainAmountText)
            views.setTextColor(
                R.id.widget_amount,
                if (display.availablePerDay < 0L) Color.parseColor("#FCA5A5") else Color.WHITE
            )
            views.setTextViewText(R.id.widget_period_info, display.periodSubtitleText)

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
