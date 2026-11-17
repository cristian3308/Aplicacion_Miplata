package com.example.domain

import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.PeriodMode
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import java.util.Calendar
import kotlin.math.max

/**
 * Resultado del cálculo "Disponible para gastar" para la tarjeta principal de Inicio:
 * - currentDebitBalance: Balance actual = suma de las cuentas de Débito incluidas (Ahorros nunca cuenta).
 * - projectedIncomeInPeriod: Ingresos pendientes dentro del período.
 * - projectedBalance: Balance proyectado = balance actual + ingresos pendientes dentro del período.
 * - pendingPaymentsInPeriod: Pagos/gastos pendientes dentro del período.
 * - creditCardPaymentDueInPeriod: Pago de tarjeta de crédito que vence dentro del período (mostrado en línea aparte).
 * - deductCreditCardPayment: Ajuste (activado por defecto) para restar también el pago de tarjeta de crédito.
 * - projectedExpense: Gasto proyectado total = pagos pendientes + (pago de tarjeta si está activo).
 * - availableForPeriod: Restante = Balance proyectado − Gasto proyectado.
 */
data class AvailableToSpendResult(
    val currentDebitBalance: Long = 0L,
    val projectedIncomeInPeriod: Long = 0L,
    val projectedBalance: Long = 0L,
    val pendingPaymentsInPeriod: Long = 0L,
    val creditCardPaymentDueInPeriod: Long = 0L,
    val deductCreditCardPayment: Boolean = true,
    val projectedExpense: Long = 0L,
    // Campos de compatibilidad adicionales
    val periodIncome: Long = 0L,
    val pendingFixedObligations: Long = 0L,
    val monthlySavingsTarget: Long = 0L,
    val variableExpensesDone: Long = 0L,
    val availableForPeriod: Long = 0L,
    val remainingDaysInPeriod: Int = 30,
    val totalDaysInPeriod: Int = 30,
    val availablePerDay: Long = 0L,
    val isOverBudget: Boolean = false,
    val overBudgetAmount: Long = 0L,
    val periodLabel: String = "Este mes",
    val spentProgressFraction: Float = 0f
)

/**
 * Estado calculado de una tarjeta de crédito:
 * - currentDebt: Cupo utilizado total, incluye todas las cuotas pendientes (se usa en la pantalla de la tarjeta).
 * - dueInPeriod: Lo que hay que pagar en la próxima fecha de pago = compras pagadas hasta la última fecha de corte
 *   + cuotas cuya fecha sea anterior o igual a ese corte − pagos de tarjeta ya hechos, sin bajar de 0.
 * - nextPaymentDueTimestamp: Próxima fecha real de pago (este mes si aún no pasó, si no el mes siguiente).
 * - lastCutoffTimestamp: Fecha de corte correspondiente a la próxima fecha de pago.
 * - availableCredit: Disponible sobre el cupo (cupo - currentDebt).
 * - usedPercentage: Porcentaje usado del cupo (0..100).
 * - daysUntilPaymentDue: Días que faltan para la próxima fecha de pago.
 * - dueWarningText: Advertencia "Tienes $X por pagar en N días".
 */
data class CreditCardSummary(
    val account: AccountEntity,
    val currentDebt: Long,
    val dueInPeriod: Long = 0L,
    val nextPaymentDueTimestamp: Long = 0L,
    val lastCutoffTimestamp: Long = 0L,
    val availableCredit: Long,
    val usedPercentage: Int,
    val daysUntilPaymentDue: Int,
    val dueWarningText: String
)

/**
 * Celda diaria para la vista de Calendario (saldo proyectado al cierre del día y flujo neto del día).
 */
data class CalendarDayProjection(
    val dayOfMonth: Int,
    val timestampStartOfDay: Long,
    val isToday: Boolean,
    val projectedClosingBalance: Long,
    val netDailyFlow: Long,
    val paidCount: Int,
    val pendingCount: Int,
    val hasTransactions: Boolean
)

/**
 * Motor matemático de MiPlata con reglas estrictas de contabilidad personal:
 * 1. Los saldos se calculan SIEMPRE desde las transacciones.
 * 2. Las cuentas de Ahorros nunca entran en el "Disponible para gastar".
 * 3. Un gasto con tarjeta de crédito cuenta como gasto en su categoría el día de la compra y sube la deuda
 *    de la tarjeta, pero NO baja el efectivo; el efectivo baja con el "Pago de tarjeta" (PAGO_TARJETA).
 * 4. Las transferencias y los pagos de tarjeta no entran en los totales de gastos ni de ingresos.
 * 5. Las transacciones pendientes solo afectan los saldos proyectados, no el balance actual.
 */
object AvailableToSpendCalculator {

    /**
     * Versión directa para pruebas unitarias de la fórmula clásica y del modelo proyectado.
     */
    fun calculate(
        periodIncome: Long,
        pendingFixedObligations: Long,
        monthlySavingsTarget: Long,
        variableExpensesDone: Long,
        remainingDaysInPeriod: Int,
        totalDaysInPeriod: Int = 30,
        periodLabel: String = "Este mes"
    ): AvailableToSpendResult {
        val safeRemainingDays = max(1, remainingDaysInPeriod)
        val safeTotalDays = max(safeRemainingDays, totalDaysInPeriod)

        val availableForPeriod = periodIncome -
            pendingFixedObligations -
            monthlySavingsTarget -
            variableExpensesDone

        val availablePerDay = availableForPeriod / safeRemainingDays
        val isOverBudget = availableForPeriod < 0L
        val overBudgetAmount = if (isOverBudget) -availableForPeriod else 0L

        val spendablePool = periodIncome - pendingFixedObligations - monthlySavingsTarget
        val progress = when {
            spendablePool <= 0L -> if (variableExpensesDone > 0L) 1f else 0f
            else -> (variableExpensesDone.toFloat() / spendablePool.toFloat()).coerceIn(0f, 1f)
        }

        return AvailableToSpendResult(
            currentDebitBalance = periodIncome - variableExpensesDone,
            projectedIncomeInPeriod = 0L,
            projectedBalance = periodIncome - variableExpensesDone,
            pendingPaymentsInPeriod = pendingFixedObligations + monthlySavingsTarget,
            creditCardPaymentDueInPeriod = 0L,
            deductCreditCardPayment = true,
            projectedExpense = pendingFixedObligations + monthlySavingsTarget,
            periodIncome = periodIncome,
            pendingFixedObligations = pendingFixedObligations,
            monthlySavingsTarget = monthlySavingsTarget,
            variableExpensesDone = variableExpensesDone,
            availableForPeriod = availableForPeriod,
            remainingDaysInPeriod = safeRemainingDays,
            totalDaysInPeriod = safeTotalDays,
            availablePerDay = availablePerDay,
            isOverBudget = isOverBudget,
            overBudgetAmount = overBudgetAmount,
            periodLabel = periodLabel,
            spentProgressFraction = progress
        )
    }

    fun startOfDayMillis(nowMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Etiqueta de estado para movimientos pendientes vencidos (anteriores a hoy):
     * - Gastos/pagos vencidos -> "Vencido"
     * - Ingresos vencidos -> "Ingreso atrasado"
     * - No vencidos -> null
     */
    fun pendingStatusLabel(
        tx: TransactionEntity,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        if (tx.status != TransactionStatus.PENDIENTE) return null
        val todayStart = startOfDayMillis(nowMillis)
        if (tx.timestamp >= todayStart) return null
        val isIncome = tx.type == TransactionType.INGRESO ||
            tx.type == TransactionType.REEMBOLSO ||
            tx.type == TransactionType.CASHBACK
        return if (isIncome) "Ingreso atrasado" else "Vencido"
    }

    /**
     * Cálculo oficial de la tarjeta "Disponible para gastar" en Inicio:
     * - Balance actual = suma de las cuentas de Débito incluidas (Ahorros queda siempre fuera).
     * - Balance proyectado = Balance actual + ingresos pendientes NO vencidos dentro del período.
     * - Gasto proyectado = pagos pendientes de débito (dentro del período + vencidos anteriores a hoy) +
     *   (si [deductCreditCardPayment] es true, la suma por tarjeta del mayor entre `dueInPeriod` si vence entre hoy
     *   y `periodWindow.endTimestamp`, y los `PAGO_TARJETA` pendientes programados para esa tarjeta).
     * - Restante = Balance proyectado − Gasto proyectado.
     */
    fun calculateProjectedAvailable(
        accounts: List<AccountEntity>,
        allTransactions: List<TransactionEntity>,
        periodWindow: PeriodWindow,
        deductCreditCardPayment: Boolean = true,
        selectedDebitAccountIds: Set<Long>? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): AvailableToSpendResult {
        val todayStartMillis = startOfDayMillis(nowMillis)

        // 1. Filtrar únicamente cuentas de DÉBITO activas (Ahorros y cuentas archivadas NUNCA cuentan como disponible)
        val debitAccounts = accounts.filter { acc ->
            !acc.isArchived &&
                acc.type == AccountType.DEBITO &&
                acc.includeInAvailableFilter &&
                (selectedDebitAccountIds == null || selectedDebitAccountIds.isEmpty() || acc.id in selectedDebitAccountIds)
        }
        val debitAccountIds = debitAccounts.map { it.id }.toSet()

        // Balance actual = suma de los saldos actuales (solo transacciones PAGADAS) de las cuentas de débito
        val currentDebitBalance = debitAccounts.sumOf { acc ->
            computeAccountBalance(acc, allTransactions, includePending = false)
        }

        // Ingresos pendientes en cuentas de débito dentro del período:
        // Los ingresos pendientes vencidos (< todayStartMillis) NO se suman al balance proyectado.
        val projectedIncomeInPeriod = allTransactions
            .filter { tx ->
                tx.status == TransactionStatus.PENDIENTE &&
                    tx.accountId in debitAccountIds &&
                    (tx.type == TransactionType.INGRESO || tx.type == TransactionType.REEMBOLSO || tx.type == TransactionType.CASHBACK) &&
                    tx.timestamp >= todayStartMillis &&
                    tx.timestamp in periodWindow.startTimestamp..periodWindow.endTimestamp
            }
            .sumOf { it.amount }

        val projectedBalance = currentDebitBalance + projectedIncomeInPeriod

        // Pagos pendientes desde cuentas de débito:
        // Incluye los del período actual Y los pendientes vencidos con fecha anterior a hoy sea cual sea el período.
        val pendingDebitPayments = allTransactions
            .filter { tx ->
                tx.status == TransactionStatus.PENDIENTE &&
                    tx.accountId in debitAccountIds &&
                    (tx.type == TransactionType.GASTO || tx.type == TransactionType.PAGO_PROGRAMADO) &&
                    (tx.timestamp in periodWindow.startTimestamp..periodWindow.endTimestamp || tx.timestamp < todayStartMillis)
            }
            .sumOf { it.amount }

        // Calcular el pago de tarjeta de crédito que vence dentro del período (entre hoy y periodWindow.endTimestamp),
        // tomando el mayor entre dueInPeriod y PAGO_TARJETA pendiente programado POR CADA TARJETA activa.
        val creditCards = accounts.filter { !it.isArchived && it.type == AccountType.TARJETA_CREDITO }
        val creditCardIds = creditCards.map { it.id }.toSet()

        val pendingCardPaymentTxs = allTransactions.filter { tx ->
            tx.status == TransactionStatus.PENDIENTE &&
                tx.type == TransactionType.PAGO_TARJETA &&
                tx.accountId in debitAccountIds &&
                (tx.timestamp in periodWindow.startTimestamp..periodWindow.endTimestamp || tx.timestamp < todayStartMillis)
        }

        val perCardDueTotal = creditCards.sumOf { card ->
            val summary = computeCreditCardSummary(card, allTransactions, nowMillis)
            val dueFallsInPeriod = summary.nextPaymentDueTimestamp in todayStartMillis..periodWindow.endTimestamp
            val cardDueInPeriod = if (dueFallsInPeriod) summary.dueInPeriod else 0L
            val scheduledForThisCard = pendingCardPaymentTxs
                .filter { it.toAccountId == card.id }
                .sumOf { it.amount }
            max(cardDueInPeriod, scheduledForThisCard)
        }

        val unlinkedScheduledCardPayments = pendingCardPaymentTxs
            .filter { it.toAccountId == null || it.toAccountId !in creditCardIds }
            .sumOf { it.amount }

        val creditCardPaymentDue = perCardDueTotal + unlinkedScheduledCardPayments

        val projectedExpense = pendingDebitPayments +
            if (deductCreditCardPayment) creditCardPaymentDue else 0L

        val remainingAvailable = projectedBalance - projectedExpense
        val safeRemainingDays = max(1, periodWindow.remainingDays)
        val availablePerDay = remainingAvailable / safeRemainingDays
        val isOverBudget = remainingAvailable < 0L
        val overBudgetAmount = if (isOverBudget) -remainingAvailable else 0L

        val progress = when {
            projectedBalance <= 0L -> if (projectedExpense > 0L || currentDebitBalance < 0L) 1f else 0f
            else -> (projectedExpense.toFloat() / projectedBalance.toFloat()).coerceIn(0f, 1f)
        }

        return AvailableToSpendResult(
            currentDebitBalance = currentDebitBalance,
            projectedIncomeInPeriod = projectedIncomeInPeriod,
            projectedBalance = projectedBalance,
            pendingPaymentsInPeriod = pendingDebitPayments,
            creditCardPaymentDueInPeriod = creditCardPaymentDue,
            deductCreditCardPayment = deductCreditCardPayment,
            projectedExpense = projectedExpense,
            periodIncome = projectedIncomeInPeriod,
            pendingFixedObligations = pendingDebitPayments,
            monthlySavingsTarget = if (deductCreditCardPayment) creditCardPaymentDue else 0L,
            variableExpensesDone = 0L,
            availableForPeriod = remainingAvailable,
            remainingDaysInPeriod = safeRemainingDays,
            totalDaysInPeriod = max(1, periodWindow.totalDays),
            availablePerDay = availablePerDay,
            isOverBudget = isOverBudget,
            overBudgetAmount = overBudgetAmount,
            periodLabel = periodWindow.label,
            spentProgressFraction = progress
        )
    }

    /**
     * Ventana de período para: Este mes, Esta quincena, Próximos 30 días o Personalizado.
     */
    data class PeriodWindow(
        val startTimestamp: Long,
        val endTimestamp: Long,
        val remainingDays: Int,
        val totalDays: Int,
        val label: String
    )

    fun resolvePeriodWindow(
        mode: PeriodMode,
        customCutoffDay: Int = 1,
        nowMillis: Long = System.currentTimeMillis()
    ): PeriodWindow {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentDay = nowCal.get(Calendar.DAY_OF_MONTH)
        val maxDayInMonth = nowCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        return when (mode) {
            PeriodMode.MENSUAL -> {
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, maxDayInMonth)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                val remaining = max(1, maxDayInMonth - currentDay + 1)
                PeriodWindow(
                    startTimestamp = startCal.timeInMillis,
                    endTimestamp = endCal.timeInMillis,
                    remainingDays = remaining,
                    totalDays = maxDayInMonth,
                    label = "Este mes"
                )
            }

            PeriodMode.QUINCENAL -> {
                val isFirstHalf = currentDay <= 15
                val startDay = if (isFirstHalf) 1 else 16
                val endDay = if (isFirstHalf) 15 else maxDayInMonth
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, startDay)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, endDay)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                val totalDays = endDay - startDay + 1
                val remaining = max(1, endDay - currentDay + 1)
                PeriodWindow(
                    startTimestamp = startCal.timeInMillis,
                    endTimestamp = endCal.timeInMillis,
                    remainingDays = remaining,
                    totalDays = totalDays,
                    label = "Esta quincena"
                )
            }

            PeriodMode.PROXIMOS_30_DIAS -> {
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // Hoy (día 1) + 29 días adicionales = 30 días exactos en el rango [startTimestamp, endTimestamp]
                val endCal = (nowCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 29)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                PeriodWindow(
                    startTimestamp = startCal.timeInMillis,
                    endTimestamp = endCal.timeInMillis,
                    remainingDays = 30,
                    totalDays = 30,
                    label = "Próximos 30 días"
                )
            }

            PeriodMode.PERSONALIZADO -> {
                val safeCutoff = customCutoffDay.coerceIn(1, 28)
                val startCal = (nowCal.clone() as Calendar)
                val endCal = (nowCal.clone() as Calendar)

                if (currentDay >= safeCutoff) {
                    startCal.set(Calendar.DAY_OF_MONTH, safeCutoff)
                    endCal.add(Calendar.MONTH, 1)
                    endCal.set(Calendar.DAY_OF_MONTH, safeCutoff)
                    endCal.add(Calendar.DAY_OF_YEAR, -1)
                } else {
                    startCal.add(Calendar.MONTH, -1)
                    startCal.set(Calendar.DAY_OF_MONTH, safeCutoff)
                    endCal.set(Calendar.DAY_OF_MONTH, safeCutoff)
                    endCal.add(Calendar.DAY_OF_YEAR, -1)
                }
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
                startCal.set(Calendar.MILLISECOND, 0)

                endCal.set(Calendar.HOUR_OF_DAY, 23)
                endCal.set(Calendar.MINUTE, 59)
                endCal.set(Calendar.SECOND, 59)
                endCal.set(Calendar.MILLISECOND, 999)

                val diffMillis = endCal.timeInMillis - nowCal.timeInMillis
                val remaining = max(1, (diffMillis / (24L * 60 * 60 * 1000)).toInt() + 1)
                val totalMillis = endCal.timeInMillis - startCal.timeInMillis
                val totalDays = max(1, (totalMillis / (24L * 60 * 60 * 1000)).toInt() + 1)

                PeriodWindow(
                    startTimestamp = startCal.timeInMillis,
                    endTimestamp = endCal.timeInMillis,
                    remainingDays = remaining,
                    totalDays = totalDays,
                    label = "Corte día $safeCutoff"
                )
            }
        }
    }

    /**
     * Determina el delta contable que produce una transacción sobre una cuenta específica:
     * - En cuentas DEBITO / AHORROS:
     *   - INGRESO, REEMBOLSO, CASHBACK -> +amount
     *   - GASTO, PAGO_PROGRAMADO, COMPRA_CUOTAS -> -amount
     *   - TRANSFERENCIA -> -amount en cuenta origen, +amount en cuenta destino
     *   - PAGO_TARJETA -> -amount en cuenta débito origen (`accountId`)
     * - En cuentas TARJETA_CREDITO (representamos el saldo utilizado/deuda como valor positivo en currentDebt,
     *   y en balance neto general de cuentas como negativo):
     *   Aquí `computeAccountBalance` devuelve:
     *   - Para DEBITO, AHORROS y PRESTADO: valor positivo = dinero a favor.
     *   - Para TARJETA_CREDITO: devuelve el saldo utilizado (deuda acumulada >= 0) para visualización de tarjeta,
     *     o usa `computeSignedNetWorthBalance` cuando se suma en patrimonio.
     */
    fun computeAccountDelta(account: AccountEntity, tx: TransactionEntity): Long {
        if (tx.isInitialBalance && tx.accountId == account.id) return 0L

        return when (account.type) {
            AccountType.TARJETA_CREDITO -> {
                // En tarjeta de crédito: compras suben el saldo utilizado (deuda), pagos de tarjeta y reembolsos lo bajan
                when (tx.type) {
                    TransactionType.GASTO,
                    TransactionType.PAGO_PROGRAMADO,
                    TransactionType.COMPRA_CUOTAS -> if (tx.accountId == account.id) tx.amount else 0L

                    TransactionType.REEMBOLSO,
                    TransactionType.CASHBACK -> if (tx.accountId == account.id) -tx.amount else 0L

                    TransactionType.PAGO_TARJETA -> {
                        if (tx.toAccountId == account.id || tx.accountId == account.id) -tx.amount else 0L
                    }

                    TransactionType.TRANSFERENCIA -> {
                        when {
                            tx.accountId == account.id -> tx.amount
                            tx.toAccountId == account.id -> -tx.amount
                            else -> 0L
                        }
                    }

                    TransactionType.INGRESO -> if (tx.accountId == account.id) -tx.amount else 0L
                }
            }

            AccountType.PRESTAMO -> {
                // Préstamo (lo que yo debo): transferencias/pagos hacia el préstamo bajan la deuda
                when {
                    tx.toAccountId == account.id -> -tx.amount
                    tx.accountId == account.id && tx.type == TransactionType.GASTO -> -tx.amount
                    else -> 0L
                }
            }

            AccountType.PRESTADO -> {
                // Prestado (lo que me deben): cuando me pagan (INGRESO o TRANSFERENCIA desde esta cuenta) baja lo pendiente
                when {
                    tx.accountId == account.id && tx.type == TransactionType.INGRESO -> -tx.amount
                    tx.accountId == account.id && tx.type == TransactionType.TRANSFERENCIA -> -tx.amount
                    else -> 0L
                }
            }

            else -> {
                // DEBITO y AHORROS
                when (tx.type) {
                    TransactionType.INGRESO,
                    TransactionType.REEMBOLSO,
                    TransactionType.CASHBACK -> if (tx.accountId == account.id) tx.amount else 0L

                    TransactionType.GASTO,
                    TransactionType.PAGO_PROGRAMADO,
                    TransactionType.COMPRA_CUOTAS -> if (tx.accountId == account.id) -tx.amount else 0L

                    TransactionType.PAGO_TARJETA -> {
                        // El pago de tarjeta sale de la cuenta de débito (accountId) hacia la tarjeta (toAccountId)
                        if (tx.accountId == account.id) -tx.amount else 0L
                    }

                    TransactionType.TRANSFERENCIA -> {
                        when {
                            tx.accountId == account.id -> -tx.amount
                            tx.toAccountId == account.id -> tx.amount
                            else -> 0L
                        }
                    }
                }
            }
        }
    }

    /**
     * Regla de oro:
     * - Las transacciones PENDIENTE solo afectan los saldos proyectados (`includePending = true`),
     *   NUNCA el balance actual (`includePending = false`).
     */
    fun computeAccountBalance(
        account: AccountEntity,
        allTransactions: List<TransactionEntity>,
        includePending: Boolean = false
    ): Long {
        val base = when (account.type) {
            AccountType.PRESTAMO, AccountType.PRESTADO -> {
                if (account.originalAmount > 0L) {
                    (account.originalAmount - account.receivedOrPaidAmount).coerceAtLeast(0L)
                } else {
                    account.initialBalance
                }
            }
            else -> account.initialBalance
        }

        var balance = base
        for (tx in allTransactions) {
            if (!includePending && tx.status != TransactionStatus.PAGADO) continue
            balance += computeAccountDelta(account, tx)
        }
        return when (account.type) {
            AccountType.PRESTAMO, AccountType.PRESTADO -> balance.coerceAtLeast(0L)
            else -> balance
        }
    }

    /**
     * Calcula el estado detallado de una Tarjeta de Crédito:
     * - `currentDebt`: cupo utilizado total (incluye todas las compras pagadas y todas las cuotas pendientes).
     * - `dueInPeriod`: lo que hay que pagar en la próxima fecha de pago = compras pagadas hasta la última fecha
     *   de corte + cuotas cuya fecha sea anterior o igual a ese corte − pagos de tarjeta ya hechos, sin bajar de 0.
     * - `nextPaymentDueTimestamp`: fecha real del próximo pago (este mes si aún no pasó, si no el mes siguiente).
     */
    fun computeCreditCardSummary(
        cardAccount: AccountEntity,
        allTransactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): CreditCardSummary {
        // 1. currentDebt: cupo utilizado total, incluye todas las cuotas pendientes
        var debt = cardAccount.initialBalance
        for (tx in allTransactions) {
            val isInstallmentChild = tx.type == TransactionType.COMPRA_CUOTAS && tx.accountId == cardAccount.id
            if (tx.status == TransactionStatus.PAGADO || isInstallmentChild) {
                debt += computeAccountDelta(cardAccount, tx)
            }
        }
        val safeDebt = debt.coerceAtLeast(0L)
        val limit = max(cardAccount.creditLimit, safeDebt)
        val availableCredit = (limit - safeDebt).coerceAtLeast(0L)
        val usedPct = if (limit > 0L) {
            ((safeDebt.toDouble() / limit.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        // 2. Próxima fecha de pago como fecha real (este mes si aún no pasó, si no el mes siguiente)
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val todayStartCal = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayDay = nowCal.get(Calendar.DAY_OF_MONTH)
        val dueDay = cardAccount.paymentDueDay.coerceIn(1, 28)
        val cutoffDay = cardAccount.billingCutoffDay.coerceIn(1, 28)

        val nextDueCal = (todayStartCal.clone() as Calendar).apply {
            if (todayDay > dueDay) {
                add(Calendar.MONTH, 1)
            }
            val maxD = getActualMaximum(Calendar.DAY_OF_MONTH)
            set(Calendar.DAY_OF_MONTH, dueDay.coerceAtMost(maxD))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val nextDueStartOfDayMillis = (nextDueCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val daysUntilDue = ((nextDueStartOfDayMillis - todayStartCal.timeInMillis) / 86_400_000L)
            .toInt()
            .coerceAtLeast(0)

        // 3. Fecha de corte correspondiente a la próxima fecha de pago
        val cutoffCal = (nextDueCal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            if (cutoffDay > dueDay) {
                add(Calendar.MONTH, -1)
            }
            val maxCutoffD = getActualMaximum(Calendar.DAY_OF_MONTH)
            set(Calendar.DAY_OF_MONTH, cutoffDay.coerceAtMost(maxCutoffD))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val cutoffTimestamp = cutoffCal.timeInMillis

        // 4. dueInPeriod = compras pagadas hasta ese corte + cuotas con fecha <= corte - pagos de tarjeta ya hechos
        var dueAmount = cardAccount.initialBalance
        for (tx in allTransactions) {
            if (tx.isInitialBalance && tx.accountId == cardAccount.id) continue
            when (tx.type) {
                TransactionType.GASTO,
                TransactionType.PAGO_PROGRAMADO -> {
                    if (tx.accountId == cardAccount.id &&
                        tx.status == TransactionStatus.PAGADO &&
                        tx.timestamp <= cutoffTimestamp
                    ) {
                        dueAmount += tx.amount
                    }
                }

                TransactionType.COMPRA_CUOTAS -> {
                    val instDate = tx.dueTimestamp ?: tx.timestamp
                    if (tx.accountId == cardAccount.id &&
                        (tx.status == TransactionStatus.PAGADO || tx.status == TransactionStatus.PENDIENTE) &&
                        instDate <= cutoffTimestamp
                    ) {
                        dueAmount += tx.amount
                    }
                }

                TransactionType.PAGO_TARJETA -> {
                    if (tx.status == TransactionStatus.PAGADO &&
                        (tx.toAccountId == cardAccount.id || tx.accountId == cardAccount.id)
                    ) {
                        dueAmount -= tx.amount
                    }
                }

                TransactionType.REEMBOLSO,
                TransactionType.CASHBACK,
                TransactionType.INGRESO -> {
                    if (tx.accountId == cardAccount.id && tx.status == TransactionStatus.PAGADO) {
                        dueAmount -= tx.amount
                    }
                }

                TransactionType.TRANSFERENCIA -> {
                    if (tx.status == TransactionStatus.PAGADO) {
                        if (tx.accountId == cardAccount.id && tx.timestamp <= cutoffTimestamp) {
                            dueAmount += tx.amount
                        } else if (tx.toAccountId == cardAccount.id) {
                            dueAmount -= tx.amount
                        }
                    }
                }
            }
        }
        val safeDueInPeriod = dueAmount.coerceAtLeast(0L)

        val warning = if (safeDueInPeriod > 0L) {
            "Tienes ${CopFormatter.format(safeDueInPeriod)} por pagar en $daysUntilDue días"
        } else if (safeDebt > 0L) {
            "Próximo corte día ${cardAccount.billingCutoffDay} • En cuotas: ${CopFormatter.format(safeDebt)}"
        } else {
            "Estás al día • Próximo corte día ${cardAccount.billingCutoffDay}"
        }

        return CreditCardSummary(
            account = cardAccount,
            currentDebt = safeDebt,
            dueInPeriod = safeDueInPeriod,
            nextPaymentDueTimestamp = nextDueCal.timeInMillis,
            lastCutoffTimestamp = cutoffTimestamp,
            availableCredit = availableCredit,
            usedPercentage = usedPct,
            daysUntilPaymentDue = daysUntilDue,
            dueWarningText = warning
        )
    }

    /**
     * Reglas para totales de Gastos e Ingresos:
     * - Las transferencias (TRANSFERENCIA) y los pagos de tarjeta (PAGO_TARJETA) NO entran en los totales de gastos ni de ingresos.
     * - Un REEMBOLSO resta del gasto de su categoría.
     * - Un gasto con tarjeta de crédito cuenta como gasto en su categoría el día de la compra.
     */
    fun isExpenseTransactionForTotals(tx: TransactionEntity): Boolean {
        return tx.type == TransactionType.GASTO ||
            tx.type == TransactionType.PAGO_PROGRAMADO ||
            tx.type == TransactionType.COMPRA_CUOTAS ||
            tx.type == TransactionType.REEMBOLSO
    }

    fun netCategoryExpenseAmount(tx: TransactionEntity): Long {
        return when (tx.type) {
            TransactionType.GASTO,
            TransactionType.PAGO_PROGRAMADO,
            TransactionType.COMPRA_CUOTAS -> tx.amount
            TransactionType.REEMBOLSO -> -tx.amount
            else -> 0L
        }
    }

    fun isIncomeTransactionForTotals(tx: TransactionEntity): Boolean {
        return tx.type == TransactionType.INGRESO || tx.type == TransactionType.CASHBACK
    }

    /**
     * Calcula el saldo proyectado al cierre de cada día de un mes (para la pantalla de Calendario
     * y las pruebas unitarias de saldo proyectado por día).
     * - Parte del balance actual (transacciones PAGADAS previas o hasta hoy) y aplica las transacciones
     *   PENDIENTES día por día.
     */
    fun computeDailyProjectedBalancesForMonth(
        year: Int,
        monthZeroBased: Int,
        accounts: List<AccountEntity>,
        allTransactions: List<TransactionEntity>,
        forCreditCards: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CalendarDayProjection> {
        val targetAccounts = accounts.filter { acc ->
            !acc.isArchived && if (forCreditCards) {
                acc.type == AccountType.TARJETA_CREDITO
            } else {
                acc.type == AccountType.DEBITO
            }
        }
        val targetAccountIds = targetAccounts.map { it.id }.toSet()

        val monthStartCal = Calendar.getInstance().apply {
            set(year, monthZeroBased, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val maxDay = monthStartCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val isSameYearMonth = nowCal.get(Calendar.YEAR) == year && nowCal.get(Calendar.MONTH) == monthZeroBased
        val todayDay = if (isSameYearMonth) nowCal.get(Calendar.DAY_OF_MONTH) else -1

        // Saldo inicial antes del día 1 del mes seleccionado
        val beforeMonthMillis = monthStartCal.timeInMillis - 1L
        var runningBalance = targetAccounts.sumOf { acc ->
            var bal = acc.initialBalance
            allTransactions.forEach { tx ->
                if (tx.timestamp <= beforeMonthMillis) {
                    // Antes de hoy solo cuentan las pagadas; en el futuro cuentan también las pendientes
                    val shouldApply = tx.status == TransactionStatus.PAGADO || tx.timestamp >= nowMillis
                    if (shouldApply) {
                        bal += computeAccountDelta(acc, tx)
                    }
                }
            }
            bal
        }

        val results = mutableListOf<CalendarDayProjection>()
        for (day in 1..maxDay) {
            val dayStartCal = (monthStartCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayEndCal = (monthStartCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            val dayTxs = allTransactions.filter { tx ->
                tx.timestamp in dayStartCal.timeInMillis..dayEndCal.timeInMillis &&
                    (tx.accountId in targetAccountIds || tx.toAccountId in targetAccountIds)
            }

            var dailyDelta = 0L
            for (tx in dayTxs) {
                for (acc in targetAccounts) {
                    val delta = computeAccountDelta(acc, tx)
                    dailyDelta += if (forCreditCards) -delta else delta
                }
            }
            runningBalance += if (forCreditCards) -dailyDelta else dailyDelta

            val paidCount = dayTxs.count { it.status == TransactionStatus.PAGADO }
            val pendingCount = dayTxs.count { it.status == TransactionStatus.PENDIENTE }

            results += CalendarDayProjection(
                dayOfMonth = day,
                timestampStartOfDay = dayStartCal.timeInMillis,
                isToday = day == todayDay,
                projectedClosingBalance = runningBalance,
                netDailyFlow = dailyDelta,
                paidCount = paidCount,
                pendingCount = pendingCount,
                hasTransactions = dayTxs.isNotEmpty()
            )
        }
        return results
    }
}
