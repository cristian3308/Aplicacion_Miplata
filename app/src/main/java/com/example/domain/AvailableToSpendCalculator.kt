package com.example.domain

import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.PeriodMode
import com.example.data.RecurringObligationEntity
import com.example.data.SavingGoalEntity
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
 * - currentDebt: Saldo utilizado / deuda actual (positivo = monto que debo en la tarjeta).
 * - availableCredit: Disponible sobre el cupo (cupo - deuda).
 * - usedPercentage: Porcentaje usado del cupo (0..100).
 * - daysUntilPaymentDue: Días que faltan para la fecha de pago.
 * - dueWarningText: Advertencia "Tienes $X por pagar en N días".
 */
data class CreditCardSummary(
    val account: AccountEntity,
    val currentDebt: Long,
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

    /**
     * Cálculo oficial de la tarjeta "Disponible para gastar" en Inicio:
     * - Balance actual = suma de las cuentas de Débito incluidas (Ahorros queda siempre fuera).
     * - Balance proyectado = Balance actual + ingresos pendientes dentro del período.
     * - Gasto proyectado = pagos pendientes dentro del período + (si [deductCreditCardPayment] es true,
     *   el pago de tarjeta de crédito que vence dentro del período).
     * - Restante = Balance proyectado − Gasto proyectado.
     */
    fun calculateProjectedAvailable(
        accounts: List<AccountEntity>,
        allTransactions: List<TransactionEntity>,
        periodWindow: PeriodWindow,
        deductCreditCardPayment: Boolean = true,
        selectedDebitAccountIds: Set<Long>? = null
    ): AvailableToSpendResult {
        // 1. Filtrar únicamente cuentas de DÉBITO (Ahorros NUNCA cuenta como disponible)
        val debitAccounts = accounts.filter { acc ->
            acc.type.normalized() == AccountType.DEBITO &&
                acc.includeInAvailableFilter &&
                (selectedDebitAccountIds == null || selectedDebitAccountIds.isEmpty() || acc.id in selectedDebitAccountIds)
        }
        val debitAccountIds = debitAccounts.map { it.id }.toSet()

        // Balance actual = suma de los saldos actuales (solo transacciones PAGADAS) de las cuentas de débito
        val currentDebitBalance = debitAccounts.sumOf { acc ->
            computeAccountBalance(acc, allTransactions, includePending = false)
        }

        // Transacciones PENDIENTES dentro de la ventana del período
        val pendingInPeriod = allTransactions.filter { tx ->
            tx.status == TransactionStatus.PENDIENTE &&
                tx.timestamp in periodWindow.startTimestamp..periodWindow.endTimestamp
        }

        // Ingresos pendientes en cuentas de débito
        val projectedIncomeInPeriod = pendingInPeriod
            .filter { tx ->
                tx.accountId in debitAccountIds &&
                    (tx.type == TransactionType.INGRESO || tx.type == TransactionType.REEMBOLSO || tx.type == TransactionType.CASHBACK)
            }
            .sumOf { it.amount }

        val projectedBalance = currentDebitBalance + projectedIncomeInPeriod

        // Pagos pendientes dentro del período desde cuentas de débito (excluyendo pagos de tarjeta que van en línea aparte)
        val pendingDebitPayments = pendingInPeriod
            .filter { tx ->
                tx.accountId in debitAccountIds &&
                    (tx.type == TransactionType.GASTO || tx.type == TransactionType.PAGO_PROGRAMADO)
            }
            .sumOf { it.amount }

        // Calcular el pago de tarjeta de crédito que vence dentro del período
        val creditCards = accounts.filter { it.type.normalized() == AccountType.TARJETA_CREDITO }
        val scheduledCardPaymentTxs = pendingInPeriod
            .filter { it.type == TransactionType.PAGO_TARJETA && it.accountId in debitAccountIds }
            .sumOf { it.amount }

        val cardsDebtDueInPeriod = creditCards.sumOf { card ->
            val summary = computeCreditCardSummary(card, allTransactions, periodWindow.startTimestamp)
            // Si la tarjeta vence dentro de los días restantes del período, su deuda actual vence en el período
            if (summary.daysUntilPaymentDue <= periodWindow.remainingDays) {
                summary.currentDebt
            } else {
                0L
            }
        }

        val creditCardPaymentDue = max(scheduledCardPaymentTxs, cardsDebtDueInPeriod)

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
                val endCal = (nowCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 30)
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
     * Compatibilidad con llamadas existentes que pasan listas de obligaciones y metas.
     */
    fun calculateFromEntities(
        transactionsInPeriod: List<TransactionEntity>,
        obligations: List<RecurringObligationEntity>,
        savingGoals: List<SavingGoalEntity>,
        periodWindow: PeriodWindow,
        configuredBaseIncome: Long = 0L,
        currentYearMonth: String = CopFormatter.currentYearMonthKey()
    ): AvailableToSpendResult {
        val recordedIncome = transactionsInPeriod
            .filter { isIncomeTransactionForTotals(it) && !it.isInitialBalance }
            .sumOf { it.amount }

        val effectiveIncome = max(recordedIncome, configuredBaseIncome)

        val pendingObligationsTotal = obligations
            .filter { it.lastPaidYearMonth != currentYearMonth }
            .sumOf { it.amount }

        val totalMonthlySavingsQuota = savingGoals
            .filter { it.savedAmount < it.targetAmount }
            .sumOf { it.monthlyTargetContribution }

        val variableExpenses = transactionsInPeriod
            .filter { isExpenseTransactionForTotals(it) && it.linkedGoalId == null }
            .sumOf { netCategoryExpenseAmount(it) }

        val periodFactorSavings = if (periodWindow.totalDays <= 16) {
            totalMonthlySavingsQuota / 2L
        } else {
            totalMonthlySavingsQuota
        }

        return calculate(
            periodIncome = effectiveIncome,
            pendingFixedObligations = pendingObligationsTotal,
            monthlySavingsTarget = periodFactorSavings,
            variableExpensesDone = variableExpenses,
            remainingDaysInPeriod = periodWindow.remainingDays,
            totalDaysInPeriod = periodWindow.totalDays,
            periodLabel = periodWindow.label
        )
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
        val normType = account.type.normalized()

        return when (normType) {
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
        val base = when (account.type.normalized()) {
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
        return balance
    }

    /**
     * Calcula el estado detallado de una Tarjeta de Crédito (incluyendo compras a cuotas y pagos de tarjeta).
     */
    fun computeCreditCardSummary(
        cardAccount: AccountEntity,
        allTransactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): CreditCardSummary {
        // En tarjeta de crédito, las compras pagadas y las cuotas pendientes generadas de compras a cuotas
        // ocupan el cupo de la tarjeta; los pagos de tarjeta realizados lo liberan.
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

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dueDay = cardAccount.paymentDueDay.coerceIn(1, 28)
        val daysUntilDue = if (dueDay >= today) {
            dueDay - today
        } else {
            (maxDay - today) + dueDay
        }

        val warning = if (safeDebt > 0L) {
            "Tienes ${CopFormatter.format(safeDebt)} por pagar en $daysUntilDue días"
        } else {
            "Estás al día • Próximo corte día ${cardAccount.billingCutoffDay}"
        }

        return CreditCardSummary(
            account = cardAccount,
            currentDebt = safeDebt,
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
            val norm = acc.type.normalized()
            if (forCreditCards) {
                norm == AccountType.TARJETA_CREDITO
            } else {
                norm == AccountType.DEBITO
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
