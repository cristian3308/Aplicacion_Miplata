package com.example.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.MiPlataDatabase
import com.example.data.MiPlataRepository
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.domain.AvailableToSpendCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Programador de recordatorios reales con WorkManager:
 * 1) Recordatorio diario a la hora configurada:
 *    "Hoy puedes gastar $X. ¿Ya registraste tus gastos?"
 *    Se reprograma al cambiar la hora o el interruptor y al reiniciar el teléfono.
 * 2) Recordatorio un día antes de cada pago pendiente y el mismo día por la mañana:
 *    "Mañana vence <nota>: $X" / "Hoy vence <nota>: $X".
 */
object ReminderScheduler {

    const val UNIQUE_DAILY_REMINDER_WORK = "mi_plata_daily_reminder_work"
    const val TAG_DAILY_REMINDER = "tag_daily_reminder"
    const val TAG_PAYMENT_REMINDER = "tag_payment_reminder"

    const val KEY_HOUR_OF_DAY = "key_hour_of_day"
    const val KEY_INITIAL_DELAY_MS = "key_initial_delay_ms"
    const val KEY_TX_ID = "key_tx_id"
    const val KEY_OBLIGATION_NOTE = "key_obligation_note"
    const val KEY_OBLIGATION_AMOUNT = "key_obligation_amount"
    const val KEY_DAYS_UNTIL_DUE = "key_days_until_due"

    fun hourTag(hourOfDay: Int): String = "daily_reminder_hour_${hourOfDay.coerceIn(0, 23)}"

    /**
     * Calcula los milisegundos que faltan desde `nowMillis` hasta la próxima ocurrencia de `targetHour:00:00`.
     */
    fun computeInitialDelayToHourMillis(
        targetHour: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val safeHour = targetHour.coerceIn(0, 23)
        val targetCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, safeHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (targetCal.timeInMillis <= nowMillis) {
            targetCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return (targetCal.timeInMillis - nowMillis).coerceAtLeast(1_000L)
    }

    /**
     * Calcula el timestamp a las 8:00 AM del día de vencimiento (`daysOffset = 0`)
     * o del día anterior (`daysOffset = -1`).
     */
    fun computeMorningTimestampForDueDate(
        dueTimestamp: Long,
        daysOffset: Int,
        morningHour: Int = 8
    ): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dueTimestamp
            add(Calendar.DAY_OF_YEAR, daysOffset)
            set(Calendar.HOUR_OF_DAY, morningHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Programa o cancela el trabajo periódico de recordatorio diario en WorkManager.
     * Al cambiar la hora cancela el trabajo anterior y programa el nuevo.
     */
    fun scheduleOrCancelDailyReminder(
        context: Context,
        enabled: Boolean,
        hourOfDay: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): UUID? {
        val workManager = WorkManager.getInstance(context)
        val safeHour = hourOfDay.coerceIn(0, 23)

        // Cancelar cualquier trabajo previo del recordatorio diario
        workManager.cancelAllWorkByTag(TAG_DAILY_REMINDER)

        if (!enabled) {
            return null
        }

        val initialDelayMs = computeInitialDelayToHourMillis(safeHour, nowMillis)
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_DAILY_REMINDER)
            .addTag(hourTag(safeHour))
            .setInputData(
                workDataOf(
                    KEY_HOUR_OF_DAY to safeHour,
                    KEY_INITIAL_DELAY_MS to initialDelayMs
                )
            )
            .build()

        workManager.enqueue(request)
        return request.id
    }

    /**
     * Sincroniza los avisos para cada pago pendiente:
     * - Un día antes por la mañana (8:00 AM): "Mañana vence <nota>: $X"
     * - El mismo día por la mañana (8:00 AM): "Hoy vence <nota>: $X"
     */
    fun syncPendingPaymentReminders(
        context: Context,
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(TAG_PAYMENT_REMINDER)

        val pendingOutflows = transactions.filter { tx ->
            tx.status == TransactionStatus.PENDIENTE &&
                !AvailableToSpendCalculator.isIncomeTransactionForTotals(tx) &&
                tx.type != TransactionType.REEMBOLSO
        }

        for (tx in pendingOutflows) {
            val dueTs = tx.dueTimestamp ?: tx.timestamp
            val cleanNote = tx.note.trim().ifBlank { tx.type.label }

            // 1. Un día antes por la mañana
            val dayBeforeTs = computeMorningTimestampForDueDate(dueTs, daysOffset = -1, morningHour = 8)
            if (dayBeforeTs > nowMillis) {
                val delayMs = (dayBeforeTs - nowMillis).coerceAtLeast(1_000L)
                val req = OneTimeWorkRequestBuilder<UpcomingPaymentReminderWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .addTag(TAG_PAYMENT_REMINDER)
                    .addTag("payment_tx_${tx.id}_daybefore")
                    .setInputData(
                        workDataOf(
                            KEY_TX_ID to tx.id,
                            KEY_OBLIGATION_NOTE to cleanNote,
                            KEY_OBLIGATION_AMOUNT to tx.amount,
                            KEY_DAYS_UNTIL_DUE to 1
                        )
                    )
                    .build()
                workManager.enqueueUniqueWork(
                    "mi_plata_payment_due_${tx.id}_daybefore",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
            }

            // 2. El mismo día del vencimiento por la mañana
            val sameDayTs = computeMorningTimestampForDueDate(dueTs, daysOffset = 0, morningHour = 8)
            if (sameDayTs > nowMillis) {
                val delayMs = (sameDayTs - nowMillis).coerceAtLeast(1_000L)
                val req = OneTimeWorkRequestBuilder<UpcomingPaymentReminderWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .addTag(TAG_PAYMENT_REMINDER)
                    .addTag("payment_tx_${tx.id}_sameday")
                    .setInputData(
                        workDataOf(
                            KEY_TX_ID to tx.id,
                            KEY_OBLIGATION_NOTE to cleanNote,
                            KEY_OBLIGATION_AMOUNT to tx.amount,
                            KEY_DAYS_UNTIL_DUE to 0
                        )
                    )
                    .build()
                workManager.enqueueUniqueWork(
                    "mi_plata_payment_due_${tx.id}_sameday",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
            }
        }
    }
}

/**
 * Worker que ejecuta el recordatorio diario:
 * Calcula el disponible por día actual y envía "Hoy puedes gastar $X. ¿Ya registraste tus gastos?"
 * respetando el permiso de notificaciones y "ocultar saldos".
 */
class DailyReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!NotificationHelper.hasNotificationPermission(applicationContext)) {
            return Result.success()
        }
        val repo = MiPlataRepository(applicationContext)
        val prefs = repo.userPreferences.first()
        if (!prefs.dailyReminderEnabled) {
            return Result.success()
        }

        val dao = MiPlataDatabase.getInstance(applicationContext).dao()
        val accounts = dao.getAccountsOnce()
        val transactions = dao.getTransactionsOnce()
        val nowMillis = System.currentTimeMillis()
        val window = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = prefs.periodMode,
            customCutoffDay = prefs.customCutoffDay,
            nowMillis = nowMillis
        )
        val available = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = transactions,
            periodWindow = window,
            deductCreditCardPayment = prefs.deductCreditCardDueInAvailable,
            nowMillis = nowMillis
        )

        NotificationHelper.sendDailyReminderNotification(
            context = applicationContext,
            availableTodayCop = available.availablePerDay,
            hideBalances = prefs.hideBalances
        )
        return Result.success()
    }
}

/**
 * Worker que envía el aviso de pago pendiente (un día antes o el mismo día por la mañana)
 * solo si el movimiento sigue PENDIENTE y el usuario dio permiso de notificaciones.
 */
class UpcomingPaymentReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!NotificationHelper.hasNotificationPermission(applicationContext)) {
            return Result.success()
        }
        val txId = inputData.getLong(ReminderScheduler.KEY_TX_ID, 0L)
        val fallbackNote = inputData.getString(ReminderScheduler.KEY_OBLIGATION_NOTE) ?: "Pago pendiente"
        val fallbackAmount = inputData.getLong(ReminderScheduler.KEY_OBLIGATION_AMOUNT, 0L)
        val daysUntilDue = inputData.getInt(ReminderScheduler.KEY_DAYS_UNTIL_DUE, 1)

        val repo = MiPlataRepository(applicationContext)
        val prefs = repo.userPreferences.first()
        val dao = MiPlataDatabase.getInstance(applicationContext).dao()
        val tx = if (txId > 0L) dao.getTransactionById(txId) else null

        if (txId > 0L && (tx == null || tx.status != TransactionStatus.PENDIENTE)) {
            return Result.success()
        }

        val note = tx?.note?.ifBlank { fallbackNote } ?: fallbackNote
        val amount = tx?.amount ?: fallbackAmount

        NotificationHelper.sendUpcomingObligationAlert(
            context = applicationContext,
            obligationName = note,
            daysUntilDue = daysUntilDue,
            amountCop = amount,
            hideBalances = prefs.hideBalances
        )
        return Result.success()
    }
}

/**
 * Reprograma el recordatorio diario y los avisos de pagos pendientes al reiniciar el teléfono o actualizar la app.
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appContext = context.applicationContext
                    val repo = MiPlataRepository(appContext)
                    val prefs = repo.userPreferences.first()
                    val dao = MiPlataDatabase.getInstance(appContext).dao()
                    val transactions = dao.getTransactionsOnce()

                    ReminderScheduler.scheduleOrCancelDailyReminder(
                        context = appContext,
                        enabled = prefs.dailyReminderEnabled,
                        hourOfDay = prefs.dailyReminderHour
                    )
                    ReminderScheduler.syncPendingPaymentReminders(
                        context = appContext,
                        transactions = transactions
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
