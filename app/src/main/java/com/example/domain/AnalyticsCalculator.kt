package com.example.domain

import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.MiPlataRepository
import com.example.data.SystemCategoryKeys
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.ui.AnalyticsRangeFilter
import com.example.ui.BalanceEvolutionPoint
import com.example.ui.BudgetProgressItem
import com.example.ui.CategoryMonthComparisonItem
import com.example.ui.CategorySliceData
import com.example.ui.DailyExpensePoint
import com.example.ui.MonthlyBarPoint
import com.example.ui.SmartAlertItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Estado calculado exclusivamente para la pantalla de Análisis (en su propio StateFlow y Dispatchers.Default).
 */
data class AnalyticsUiState(
    val filter: AnalyticsRangeFilter = AnalyticsRangeFilter.MES,
    val customDays: Int = 60,
    val donutSlices: List<CategorySliceData> = emptyList(),
    val sixMonthBars: List<MonthlyBarPoint> = emptyList(),
    val balanceEvolution: List<BalanceEvolutionPoint> = emptyList(),
    val dailyExpensesCurrentMonth: List<DailyExpensePoint> = emptyList(),
    val dailyAverageExpense: Long = 0L,
    val categoryComparisons: List<CategoryMonthComparisonItem> = emptyList()
)

data class DailyExpensesCalculation(
    val dailyPoints: List<DailyExpensePoint>,
    val dailyAverageExpense: Long,
    val totalMonthExpense: Long
)

object AnalyticsCalculator {

    private val esCoLocale = Locale("es", "CO")
    const val OTHER_CATEGORIES_ID = -999L
    const val OTHER_CATEGORIES_NAME = "Otras"

    /**
     * Calcula todos los datos de la pantalla de Análisis para el filtro seleccionado.
     */
    fun computeAnalyticsState(
        filter: AnalyticsRangeFilter,
        customDays: Int,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): AnalyticsUiState {
        val donutSlices = computeDonutSlices(
            filter = filter,
            customDays = customDays,
            categories = categories,
            transactions = transactions,
            nowMillis = nowMillis
        )
        val sixMonthBars = computeSixMonthBars(
            transactions = transactions,
            nowMillis = nowMillis
        )
        val balanceEvolution = computeBalanceEvolution(
            filter = filter,
            customDays = customDays,
            accounts = accounts,
            transactions = transactions,
            nowMillis = nowMillis
        )
        val dailyResult = computeDailyExpensesForMonth(
            transactions = transactions,
            nowMillis = nowMillis
        )
        val comparisons = computeCategoryComparisons(
            categories = categories,
            transactions = transactions,
            nowMillis = nowMillis
        )

        return AnalyticsUiState(
            filter = filter,
            customDays = customDays,
            donutSlices = donutSlices,
            sixMonthBars = sixMonthBars,
            balanceEvolution = balanceEvolution,
            dailyExpensesCurrentMonth = dailyResult.dailyPoints,
            dailyAverageExpense = dailyResult.dailyAverageExpense,
            categoryComparisons = comparisons
        )
    }

    /**
     * Resuelve la ventana de inicio y fin para el filtro de Análisis elegido.
     */
    fun resolveAnalyticsRangeWindow(
        filter: AnalyticsRangeFilter,
        customDays: Int = 60,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Long, Long> {
        val endCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val startCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (filter) {
                AnalyticsRangeFilter.SEMANA -> add(Calendar.DAY_OF_YEAR, -6)
                AnalyticsRangeFilter.MES -> set(Calendar.DAY_OF_MONTH, 1)
                AnalyticsRangeFilter.ANIO -> add(Calendar.DAY_OF_YEAR, -364)
                AnalyticsRangeFilter.PERSONALIZADO -> {
                    val safeDays = customDays.coerceIn(7, 365)
                    add(Calendar.DAY_OF_YEAR, -(safeDays - 1))
                }
            }
        }
        return startCal.timeInMillis to endCal.timeInMillis
    }

    /**
     * 1. Dona de gastos por categoría:
     * - Todas las categorías con gasto neto positivo en el rango elegido.
     * - Los reembolsos restan del gasto de su categoría; si el reembolso supera al gasto, el neto de esa categoría es 0.
     * - Las transferencias y pagos de tarjeta no cuentan como gasto ni ingreso.
     * - Las categorías con menos del 3 % del total se agrupan en "Otras".
     */
    fun computeDonutSlices(
        filter: AnalyticsRangeFilter,
        customDays: Int = 60,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CategorySliceData> {
        val (startMillis, endMillis) = resolveAnalyticsRangeWindow(filter, customDays, nowMillis)
        val categoriesById = categories.associateBy { it.id }
        val fallbackCategoryId = MiPlataRepository.resolveCategoryId(
            categories = categories,
            systemKey = SystemCategoryKeys.OTROS_GASTO,
            fallbackType = CategoryType.GASTO
        )
        val fallbackCategory = (if (fallbackCategoryId != null) categoriesById[fallbackCategoryId] else null)
            ?: CategoryEntity(
                id = -1L,
                name = "Otros",
                iconName = "category",
                colorHex = 0xFF94A3B8,
                type = CategoryType.GASTO,
                groupName = "Sin grupo"
            )

        val validTxs = transactions.filter { tx ->
            tx.status == TransactionStatus.PAGADO &&
                !tx.isInitialBalance &&
                tx.timestamp in startMillis..endMillis &&
                AvailableToSpendCalculator.isExpenseTransactionForTotals(tx)
        }
        if (validTxs.isEmpty()) return emptyList()

        data class RawCatExpense(
            val category: CategoryEntity,
            val netAmount: Long,
            val txCount: Int
        )

        val rawList = validTxs
            .groupBy { it.categoryId ?: fallbackCategory.id }
            .mapNotNull { (catId, list) ->
                val cat = categoriesById[catId] ?: fallbackCategory
                val netAmount = list
                    .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
                    .coerceAtLeast(0L)
                if (netAmount <= 0L) null else RawCatExpense(cat, netAmount, list.size)
            }
            .sortedByDescending { it.netAmount }

        val totalNetExpense = rawList.sumOf { it.netAmount }
        if (totalNetExpense <= 0L) return emptyList()

        val majorSlices = mutableListOf<CategorySliceData>()
        var minorAmount = 0L
        var minorTxCount = 0

        for (item in rawList) {
            val pct = (item.netAmount.toDouble() / totalNetExpense.toDouble()) * 100.0
            if (pct < 3.0) {
                minorAmount += item.netAmount
                minorTxCount += item.txCount
            } else {
                majorSlices += CategorySliceData(
                    category = item.category,
                    amount = item.netAmount,
                    percentage = pct.toFloat(),
                    transactionsCount = item.txCount
                )
            }
        }

        if (minorAmount > 0L) {
            val minorPct = ((minorAmount.toDouble() / totalNetExpense.toDouble()) * 100.0).toFloat()
            val otrasCategory = CategoryEntity(
                id = OTHER_CATEGORIES_ID,
                name = OTHER_CATEGORIES_NAME,
                iconName = "category",
                colorHex = 0xFF64748B,
                type = CategoryType.GASTO,
                groupName = "Otros"
            )
            majorSlices += CategorySliceData(
                category = otrasCategory,
                amount = minorAmount,
                percentage = minorPct,
                transactionsCount = minorTxCount
            )
        }

        return majorSlices
    }

    /**
     * 2. Ingresos vs. gastos de los últimos 6 meses (terminando en el mes de nowMillis).
     * - Transferencias y pagos de tarjeta no cuentan como gasto ni ingreso.
     * - Los reembolsos restan del gasto de su categoría (sin que una categoría baje de $0).
     */
    fun computeSixMonthBars(
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<MonthlyBarPoint> {
        val monthLabelFormatter = SimpleDateFormat("MMM", esCoLocale)
        val baseCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val bars = mutableListOf<MonthlyBarPoint>()
        for (offset in -5..0) {
            val monthStartCal = (baseCal.clone() as Calendar).apply {
                add(Calendar.MONTH, offset)
            }
            val year = monthStartCal.get(Calendar.YEAR)
            val monthZeroBased = monthStartCal.get(Calendar.MONTH)
            val maxDay = monthStartCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val monthEndCal = (monthStartCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, maxDay)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            val monthTxs = transactions.filter { tx ->
                tx.status == TransactionStatus.PAGADO &&
                    !tx.isInitialBalance &&
                    tx.timestamp in monthStartCal.timeInMillis..monthEndCal.timeInMillis
            }

            val income = monthTxs
                .filter { AvailableToSpendCalculator.isIncomeTransactionForTotals(it) }
                .sumOf { it.amount }

            val expense = computeNetExpenseByCategoryClamped(monthTxs)

            val rawLabel = monthLabelFormatter.format(Date(monthStartCal.timeInMillis))
                .trimEnd('.')
                .replaceFirstChar { it.uppercase(esCoLocale) }
            val key = String.format(Locale.US, "%04d-%02d", year, monthZeroBased + 1)

            bars += MonthlyBarPoint(
                monthLabel = rawLabel,
                yearMonthKey = key,
                income = income,
                expense = expense
            )
        }
        return bars
    }

    /**
     * 3. Evolución del saldo total en cuentas débito activas en el rango elegido.
     */
    fun computeBalanceEvolution(
        filter: AnalyticsRangeFilter,
        customDays: Int = 60,
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<BalanceEvolutionPoint> {
        val debitAccounts = accounts.filter { !it.isArchived && it.type == AccountType.DEBITO }
        if (debitAccounts.isEmpty()) return emptyList()

        val debitAccountIds = debitAccounts.map { it.id }.toSet()
        val relevantPaidTxs = transactions.filter { tx ->
            tx.status == TransactionStatus.PAGADO &&
                !tx.isInitialBalance &&
                (tx.accountId in debitAccountIds || tx.toAccountId in debitAccountIds)
        }
        if (relevantPaidTxs.isEmpty()) return emptyList()

        val (startMillis, endMillis) = resolveAnalyticsRangeWindow(filter, customDays, nowMillis)
        val totalDays = (((endMillis - startMillis) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
        val stepDays = when {
            totalDays <= 31 -> 1
            totalDays <= 120 -> 3
            else -> 12
        }

        val dayLabelFormatter = SimpleDateFormat("dd MMM", esCoLocale)
        val points = mutableListOf<BalanceEvolutionPoint>()

        var dayOffset = 0
        while (dayOffset < totalDays) {
            val dayEndCal = Calendar.getInstance().apply {
                timeInMillis = startMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            val cutoffTs = dayEndCal.timeInMillis
            val totalBalAtCutoff = debitAccounts.sumOf { acc ->
                var bal = acc.initialBalance
                for (tx in relevantPaidTxs) {
                    if (tx.timestamp <= cutoffTs) {
                        bal += AvailableToSpendCalculator.computeAccountDelta(acc, tx)
                    }
                }
                bal
            }
            points += BalanceEvolutionPoint(
                dateLabel = dayLabelFormatter.format(Date(cutoffTs)),
                timestamp = cutoffTs,
                totalBalance = totalBalAtCutoff
            )
            dayOffset += stepDays
        }

        // Asegurar que el último punto corresponda al día actual (endMillis)
        if (points.lastOrNull()?.timestamp != endMillis) {
            val finalBal = debitAccounts.sumOf { acc ->
                var bal = acc.initialBalance
                for (tx in relevantPaidTxs) {
                    if (tx.timestamp <= endMillis) {
                        bal += AvailableToSpendCalculator.computeAccountDelta(acc, tx)
                    }
                }
                bal
            }
            points += BalanceEvolutionPoint(
                dateLabel = dayLabelFormatter.format(Date(endMillis)),
                timestamp = endMillis,
                totalBalance = finalBal
            )
        }

        return points
    }

    /**
     * 4. Gasto por día del mes actual (hasta el día transcurrido de nowMillis) y promedio diario.
     * - Si el mes no tiene gastos netos (> 0), devuelve lista vacía y promedio 0 para mostrar el estado vacío.
     */
    fun computeDailyExpensesForMonth(
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): DailyExpensesCalculation {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val year = nowCal.get(Calendar.YEAR)
        val month = nowCal.get(Calendar.MONTH)
        val daysElapsed = nowCal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)

        val monthStartCal = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthEndCal = Calendar.getInstance().apply {
            set(year, month, daysElapsed, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val monthExpenseTxs = transactions.filter { tx ->
            tx.status == TransactionStatus.PAGADO &&
                !tx.isInitialBalance &&
                tx.timestamp in monthStartCal.timeInMillis..monthEndCal.timeInMillis &&
                AvailableToSpendCalculator.isExpenseTransactionForTotals(tx)
        }

        val dailyPoints = mutableListOf<DailyExpensePoint>()
        for (day in 1..daysElapsed) {
            val dStart = (monthStartCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dEnd = (monthStartCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis

            val dayTxs = monthExpenseTxs.filter { it.timestamp in dStart..dEnd }
            val dayAmount = computeNetExpenseByCategoryClamped(dayTxs)
            dailyPoints += DailyExpensePoint(
                dayOfMonth = day,
                label = day.toString(),
                amount = dayAmount
            )
        }

        val totalMonthExpense = computeNetExpenseByCategoryClamped(monthExpenseTxs)
        if (totalMonthExpense <= 0L && dailyPoints.all { it.amount == 0L }) {
            return DailyExpensesCalculation(
                dailyPoints = emptyList(),
                dailyAverageExpense = 0L,
                totalMonthExpense = 0L
            )
        }

        val dailyAverage = totalMonthExpense / daysElapsed.toLong()
        return DailyExpensesCalculation(
            dailyPoints = dailyPoints,
            dailyAverageExpense = dailyAverage,
            totalMonthExpense = totalMonthExpense
        )
    }

    /**
     * 5. Comparación por categoría contra el mes anterior.
     * - Reembolsos restan del gasto de su categoría sin bajar de $0.
     * - Transferencias y pagos de tarjeta no entran.
     */
    fun computeCategoryComparisons(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CategoryMonthComparisonItem> {
        val currentMonthStart = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val currentMaxDay = currentMonthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val currentMonthEnd = (currentMonthStart.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, currentMaxDay)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val prevMonthStart = (currentMonthStart.clone() as Calendar).apply {
            add(Calendar.MONTH, -1)
        }
        val prevMaxDay = prevMonthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val prevMonthEnd = (prevMonthStart.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, prevMaxDay)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val currentTxs = transactions.filter { tx ->
            tx.status == TransactionStatus.PAGADO &&
                !tx.isInitialBalance &&
                tx.timestamp in currentMonthStart.timeInMillis..currentMonthEnd.timeInMillis &&
                AvailableToSpendCalculator.isExpenseTransactionForTotals(tx)
        }
        val prevTxs = transactions.filter { tx ->
            tx.status == TransactionStatus.PAGADO &&
                !tx.isInitialBalance &&
                tx.timestamp in prevMonthStart.timeInMillis..prevMonthEnd.timeInMillis &&
                AvailableToSpendCalculator.isExpenseTransactionForTotals(tx)
        }

        return categories
            .filter { it.type == CategoryType.GASTO }
            .mapNotNull { cat ->
                val currentAmt = currentTxs
                    .filter { it.categoryId == cat.id }
                    .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
                    .coerceAtLeast(0L)
                val prevAmt = prevTxs
                    .filter { it.categoryId == cat.id }
                    .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
                    .coerceAtLeast(0L)

                if (currentAmt == 0L && prevAmt == 0L) {
                    null
                } else {
                    val deltaPct = when {
                        prevAmt > 0L -> (((currentAmt - prevAmt).toDouble() / prevAmt.toDouble()) * 100.0).roundToInt()
                        currentAmt > 0L -> 100
                        else -> 0
                    }
                    CategoryMonthComparisonItem(
                        category = cat,
                        currentMonthAmount = currentAmt,
                        previousMonthAmount = prevAmt,
                        deltaPercentage = deltaPct
                    )
                }
            }
            .sortedByDescending { max(it.currentMonthAmount, it.previousMonthAmount) }
    }

    /**
     * Proyección real de presupuesto al ritmo actual:
     * gasto al ritmo actual = gastado ÷ días transcurridos × días del mes.
     * En aritmética entera Long: (spentThisMonth * daysInMonth) / daysElapsed.
     * willExceedAtCurrentPace es verdadero si esa proyección supera el límite (projectedEndOfMonth > limit).
     */
    fun computeBudgetProgressItem(
        category: CategoryEntity,
        spentThisMonth: Long,
        daysElapsedInMonth: Int,
        daysInMonth: Int
    ): BudgetProgressItem {
        val safeSpent = spentThisMonth.coerceAtLeast(0L)
        val limit = category.monthlyLimit.coerceAtLeast(0L)
        val safeDaysInMonth = daysInMonth.coerceAtLeast(1)
        val safeDaysElapsed = daysElapsedInMonth.coerceIn(1, safeDaysInMonth)

        val projectedEndOfMonth = (safeSpent * safeDaysInMonth.toLong()) / safeDaysElapsed.toLong()
        val percentage = if (limit > 0L) {
            ((safeSpent.toDouble() / limit.toDouble()) * 100.0).roundToInt()
        } else 0
        val willExceed = limit > 0L && projectedEndOfMonth > limit

        return BudgetProgressItem(
            category = category,
            spentThisMonth = safeSpent,
            percentage = percentage,
            projectedEndOfMonth = projectedEndOfMonth,
            willExceedAtCurrentPace = willExceed
        )
    }

    /**
     * Calcula las alertas inteligentes para Inicio (máximo 3, la más grave primero):
     * 1) Pago vencido (prioridad 1, crítica)
     * 2) Pago que vence en 3 días o menos sin saldo suficiente en su cuenta (prioridad 2, crítica)
     * 3) Categoría que superará su límite al ritmo actual (prioridad 3)
     * 4) Tarjeta con más del 80 % del cupo usado (prioridad 4)
     */
    fun computeHomeSmartAlerts(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        budgetItems: List<BudgetProgressItem>,
        creditCardSummaries: List<CreditCardSummary>,
        hideBalances: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): List<SmartAlertItem> {
        val alerts = mutableListOf<SmartAlertItem>()
        val todayStartMillis = AvailableToSpendCalculator.startOfDayMillis(nowMillis)
        val threeDaysAheadEndMillis = Calendar.getInstance().apply {
            timeInMillis = todayStartMillis
            add(Calendar.DAY_OF_YEAR, 3)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val activeAccountsById = accounts
            .filter { !it.isArchived }
            .associateBy { it.id }

        val currentBalanceByAccountId = activeAccountsById.mapValues { (_, acc) ->
            AvailableToSpendCalculator.computeAccountBalance(acc, transactions, includePending = false)
        }

        val pendingOutflows = transactions
            .filter { tx ->
                tx.status == TransactionStatus.PENDIENTE &&
                    !AvailableToSpendCalculator.isIncomeTransactionForTotals(tx) &&
                    tx.type != TransactionType.REEMBOLSO
            }
            .sortedBy { it.dueTimestamp ?: it.timestamp }

        // 1. Pagos vencidos (la más grave)
        for (tx in pendingOutflows) {
            val dueTs = tx.dueTimestamp ?: tx.timestamp
            if (dueTs < todayStartMillis) {
                val label = tx.note.ifBlank { tx.type.label }
                val accName = activeAccountsById[tx.accountId]?.name ?: "tu cuenta"
                val amountStr = CopFormatter.format(tx.amount, hideBalance = hideBalances)
                alerts += SmartAlertItem(
                    title = "Pago vencido: $label",
                    description = "Venció el ${CopFormatter.formatShortDate(dueTs)} por $amountStr en $accName.",
                    isCriticalRed = true
                )
            }
        }

        // 2. Pagos que vencen en 3 días o menos sin saldo suficiente en su cuenta
        for (tx in pendingOutflows) {
            val dueTs = tx.dueTimestamp ?: tx.timestamp
            if (dueTs in todayStartMillis..threeDaysAheadEndMillis) {
                val acc = activeAccountsById[tx.accountId] ?: continue
                if (acc.type == AccountType.TARJETA_CREDITO) continue
                val currentBal = currentBalanceByAccountId[acc.id] ?: 0L
                if (currentBal < tx.amount) {
                    val dueDayStart = AvailableToSpendCalculator.startOfDayMillis(dueTs)
                    val daysUntil = ((dueDayStart - todayStartMillis) / 86_400_000L).toInt().coerceAtLeast(0)
                    val whenLabel = when (daysUntil) {
                        0 -> "vence hoy"
                        1 -> "vence mañana"
                        else -> "vence en $daysUntil días"
                    }
                    val label = tx.note.ifBlank { tx.type.label }
                    alerts += SmartAlertItem(
                        title = "Saldo insuficiente para $label",
                        description = "El pago $whenLabel (${CopFormatter.format(tx.amount, hideBalances)}) y ${acc.name} tiene ${CopFormatter.format(currentBal, hideBalances)}.",
                        isCriticalRed = true
                    )
                }
            }
        }

        // 3. Categorías que superarán su límite al ritmo actual
        val exceedingCategories = budgetItems
            .filter { it.willExceedAtCurrentPace && it.category.monthlyLimit > 0L }
            .sortedByDescending { it.projectedEndOfMonth - it.category.monthlyLimit }

        for (item in exceedingCategories) {
            val projStr = CopFormatter.format(item.projectedEndOfMonth, hideBalance = hideBalances)
            val limitStr = CopFormatter.format(item.category.monthlyLimit, hideBalance = hideBalances)
            alerts += SmartAlertItem(
                title = "Proyección de exceso en ${item.category.name}",
                description = "Al ritmo actual gastarás $projStr este mes (límite $limitStr).",
                isCriticalRed = item.spentThisMonth >= item.category.monthlyLimit
            )
        }

        // 4. Tarjetas con más del 80 % del cupo usado
        val highUsageCards = creditCardSummaries
            .filter { it.account.creditLimit > 0L && (it.usedPercentage > 80 || it.currentDebt * 100L > it.account.creditLimit * 80L) }
            .sortedByDescending { it.usedPercentage }

        for (card in highUsageCards) {
            val debtStr = CopFormatter.format(card.currentDebt, hideBalance = hideBalances)
            val limitStr = CopFormatter.format(card.account.creditLimit, hideBalance = hideBalances)
            alerts += SmartAlertItem(
                title = "Cupo alto en ${card.account.name}",
                description = "Has usado el ${card.usedPercentage}% del cupo ($debtStr de $limitStr).",
                isCriticalRed = card.usedPercentage >= 95
            )
        }

        return alerts.take(3)
    }

    private fun computeNetExpenseByCategoryClamped(transactions: List<TransactionEntity>): Long {
        val expenseTxs = transactions.filter { AvailableToSpendCalculator.isExpenseTransactionForTotals(it) }
        if (expenseTxs.isEmpty()) return 0L
        return expenseTxs
            .groupBy { it.categoryId ?: -1L }
            .values
            .sumOf { catList ->
                catList
                    .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
                    .coerceAtLeast(0L)
            }
    }
}
