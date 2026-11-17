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
 * - Alerta automática al llegar al 80% (amarillo) o 100% (rojo) de un presupuesto.
 * - Aviso 2 días antes de cada obligación fija pendiente.
 * - Recordatorio diario configurable para registrar gastos.
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
                description = "Avisos de presupuestos al 80%/100%, obligaciones fijas y recordatorio diario."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun sendBudgetAlertNotification(
        context: Context,
        categoryName: String,
        percentage: Int,
        spentCop: Long,
        limitCop: Long
    ) {
        ensureChannelCreated(context)
        if (!canPostNotifications(context)) return

        val title = if (percentage >= 100) {
            "⚠️ Presupuesto agotado en $categoryName ($percentage%)"
        } else {
            "Aviso de presupuesto en $categoryName ($percentage%)"
        }
        val body = "Llevas ${CopFormatter.format(spentCop)} de ${CopFormatter.format(limitCop)} este mes."

        postNotification(context, ("budget_$categoryName").hashCode(), title, body)
    }

    fun sendUpcomingObligationAlert(
        context: Context,
        obligationName: String,
        daysUntilDue: Int,
        amountCop: Long
    ) {
        ensureChannelCreated(context)
        if (!canPostNotifications(context)) return

        val whenLabel = when (daysUntilDue) {
            0 -> "vence HOY"
            1 -> "vence mañana"
            else -> "vence en $daysUntilDue días"
        }
        val title = "Próximo pago fijo: $obligationName ($whenLabel)"
        val body = "Monto programado: ${CopFormatter.format(amountCop)}. Tócalo para marcarlo como pagado."

        postNotification(context, ("ob_$obligationName").hashCode(), title, body)
    }

    fun sendDailyReminderNotification(context: Context, availableTodayCop: Long) {
        ensureChannelCreated(context)
        if (!canPostNotifications(context)) return

        val title = "¿Registraste tus gastos de hoy en MiPlata?"
        val body = "Tu disponible para hoy es ${CopFormatter.format(availableTodayCop)}. Registra en 3 toques."
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
