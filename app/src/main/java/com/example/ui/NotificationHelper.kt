package com.example.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.domain.CopFormatter

/**
 * Gestor de notificaciones locales para MiPlata:
 * - Recordatorio diario a la hora configurada: "Hoy puedes gastar $X. ¿Ya registraste tus gastos?".
 * - Recordatorio un día antes de cada pago pendiente y el mismo día por la mañana: "Mañana vence <nota>: $X".
 * - Alerta automática al llegar al 80% o 100% de un presupuesto.
 * - Respeta siempre el ajuste "ocultar saldos" (sin montos) y no envía nada si no hay permiso de notificaciones.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "mi_plata_alerts_channel"
    private const val CHANNEL_NAME = "Alertas y Recordatorios de MiPlata"

    fun ensureChannelCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisos de presupuestos, pagos pendientes y recordatorio diario."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun formatDailyReminderMessage(
        availableTodayCop: Long,
        hideBalances: Boolean
    ): String {
        return if (hideBalances) {
            "¿Ya registraste tus gastos?"
        } else {
            "Hoy puedes gastar ${CopFormatter.format(availableTodayCop)}. ¿Ya registraste tus gastos?"
        }
    }

    fun formatUpcomingObligationMessage(
        obligationName: String,
        daysUntilDue: Int,
        amountCop: Long,
        hideBalances: Boolean
    ): String {
        val cleanName = obligationName.trim().ifBlank { "Pago programado" }
        val prefix = when (daysUntilDue) {
            1 -> "Mañana vence $cleanName"
            0 -> "Hoy vence $cleanName"
            else -> "En $daysUntilDue días vence $cleanName"
        }
        return if (hideBalances) {
            prefix
        } else {
            "$prefix: ${CopFormatter.format(amountCop)}"
        }
    }

    fun sendBudgetAlertNotification(
        context: Context,
        categoryName: String,
        percentage: Int,
        spentCop: Long,
        limitCop: Long,
        hideBalances: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannelCreated(context)

        val title = if (percentage >= 100) {
            "Presupuesto agotado en $categoryName ($percentage%)"
        } else {
            "Aviso de presupuesto en $categoryName ($percentage%)"
        }
        val body = if (hideBalances) {
            "Alcanzaste el $percentage% del límite mensual en $categoryName."
        } else {
            "Llevas ${CopFormatter.format(spentCop)} de ${CopFormatter.format(limitCop)} este mes."
        }

        postNotification(context, ("budget_$categoryName").hashCode(), title, body)
    }

    fun sendUpcomingObligationAlert(
        context: Context,
        obligationName: String,
        daysUntilDue: Int,
        amountCop: Long,
        hideBalances: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannelCreated(context)

        val message = formatUpcomingObligationMessage(
            obligationName = obligationName,
            daysUntilDue = daysUntilDue,
            amountCop = amountCop,
            hideBalances = hideBalances
        )
        val title = if (daysUntilDue <= 0) "Pago pendiente para hoy" else "Recordatorio de pago pendiente"

        postNotification(context, ("ob_${obligationName}_$daysUntilDue").hashCode(), title, message)
    }

    fun sendDailyReminderNotification(
        context: Context,
        availableTodayCop: Long,
        hideBalances: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannelCreated(context)

        val title = "Recordatorio diario de MiPlata"
        val body = formatDailyReminderMessage(availableTodayCop, hideBalances)
        postNotification(context, 9001, title, body)
    }

    private fun postNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String
    ) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permiso no concedido en tiempo de ejecución
        }
    }
}
