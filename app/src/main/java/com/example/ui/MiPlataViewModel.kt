package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.AiFinancialReport
import com.example.data.BudgetEntity
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.DebtEntity
import com.example.data.GeminiFinanceService
import com.example.data.MiPlataRepository
import com.example.data.ParsedMovementDraft
import com.example.data.PeriodMode
import com.example.data.RecurrenceFrequency
import com.example.data.RecurringObligationEntity
import com.example.data.SavingGoalEntity
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.data.UserPreferencesState
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.AvailableToSpendResult
import com.example.domain.CalendarDayProjection
import com.example.domain.CopFormatter
import com.example.domain.CreditCardSummary
import com.example.ui.widget.MiPlataWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Saldo calculado de una cuenta junto con sus metadatos.
 */
data class AccountWithBalance(
    val account: AccountEntity,
    val currentBalance: Long,
    val projectedBalance: Long,
    val periodIncome: Long,
    val periodExpense: Long
)

/**
 * Ítem de la lista "Próximo" en Inicio: transacción pendiente con el saldo proyectado que quedará
 * inmediatamente después de aplicarla.
 */
data class UpcomingPendingItem(
    val transaction: TransactionEntity,
    val accountName: String,
    val category: CategoryEntity?,
    val projectedBalanceAfter: Long
)

/**
 * Ítem enriquecido para la pestaña Historial: incluye el saldo de la cuenta después de la transacción.
 */
data class HistoryTransactionItem(
    val transaction: TransactionEntity,
    val accountName: String,
    val toAccountName: String?,
    val category: CategoryEntity?,
    val accountBalanceAfter: Long
)

/**
 * Estado de una categoría en la pantalla de Presupuesto (con grupo, total gastado, N transacciones y límite).
 */
data class CategoryBudgetSummaryItem(
    val category: CategoryEntity,
    val groupName: String,
    val spentInMonth: Long,
    val transactionsCount: Int,
    val monthlyLimit: Long, // 0 = Sin límite
    val percentageUsed: Int,
    val transactionsInMonth: List<TransactionEntity>
)

data class BudgetProgressItem(
    val budget: BudgetEntity,
    val category: CategoryEntity,
    val spentThisMonth: Long,
    val percentage: Int,
    val projectedEndOfMonth: Long,
    val willExceedAtCurrentPace: Boolean
)

data class CategorySliceData(
    val category: CategoryEntity,
    val amount: Long,
    val percentage: Float,
    val transactionsCount: Int
)

data class MonthlyBarPoint(
    val monthLabel: String,
    val yearMonthKey: String,
    val income: Long,
    val expense: Long
)

data class BalanceEvolutionPoint(
    val dateLabel: String,
    val timestamp: Long,
    val totalBalance: Long
)

data class DailyExpensePoint(
    val dayOfMonth: Int,
    val label: String,
    val amount: Long
)

data class CategoryMonthComparisonItem(
    val category: CategoryEntity,
    val currentMonthAmount: Long,
    val previousMonthAmount: Long,
    val deltaPercentage: Int
)

data class SmartAlertItem(
    val title: String,
    val description: String,
    val isCriticalRed: Boolean
)

data class ChatMessageItem(
    val id: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

enum class AnalyticsRangeFilter(val label: String, val days: Int) {
    SEMANA("Semana", 7),
    MES("Mes", 30),
    ANIO("Año", 365),
    PERSONALIZADO("Personalizado", 90)
}

/**
 * Resumen de las 3 tarjetas del carrusel horizontal "Este mes" en Inicio:
 * 1) Gasto general (total y las 2 categorías principales).
 * 2) Tarjetas de crédito (gasto del mes con tarjeta y categorías principales).
 * 3) Flujo de efectivo (neto, ingresos y egresos).
 */
data class ThisMonthCarouselSummary(
    val generalExpenseTotal: Long = 0L,
    val topTwoGeneralCategories: List<CategorySliceData> = emptyList(),
    val creditCardExpenseTotal: Long = 0L,
    val topTwoCardCategories: List<CategorySliceData> = emptyList(),
    val cashFlowNet: Long = 0L,
    val cashFlowIncomes: Long = 0L,
    val cashFlowOutflows: Long = 0L
)

/**
 * Estado consolidado de toda la interfaz de MiPlata.
 */
data class MiPlataUiState(
    val isLoading: Boolean = true,
    val prefs: UserPreferencesState = UserPreferencesState(),
    val accountsWithBalance: List<AccountWithBalance> = emptyList(),
    val creditCardSummaries: List<CreditCardSummary> = emptyList(),
    val totalDebitBalance: Long = 0L,
    val totalSavingsBalance: Long = 0L,
    val totalCreditCardDebt: Long = 0L,
    val totalLoansDebt: Long = 0L,
    val totalLentReceivable: Long = 0L,
    val totalBalance: Long = 0L,
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val availableToSpend: AvailableToSpendResult = AvailableToSpendResult(),
    val thisMonthCarousel: ThisMonthCarouselSummary = ThisMonthCarouselSummary(),
    val upcomingPendingItems: List<UpcomingPendingItem> = emptyList(),
    val historyItemsWithRunningBalance: List<HistoryTransactionItem> = emptyList(),
    val upcomingObligations7Days: List<RecurringObligationEntity> = emptyList(),
    val allObligations: List<RecurringObligationEntity> = emptyList(),
    val recentFiveTransactions: List<TransactionEntity> = emptyList(),
    val budgetItems: List<BudgetProgressItem> = emptyList(),
    val categoryBudgetItems: List<CategoryBudgetSummaryItem> = emptyList(),
    val savingGoals: List<SavingGoalEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val smartAlerts: List<SmartAlertItem> = emptyList(),
    val projectedEndOfMonthBalance: Long = 0L,
    // Datos de Calendario
    val calendarYear: Int = 2026,
    val calendarMonthZeroBased: Int = 10,
    val calendarMonthTitle: String = "Noviembre 2026",
    val calendarLastDayLabel: String = "30 de nov",
    val calendarShowFlowInsteadOfBalance: Boolean = false,
    val calendarShowCreditCards: Boolean = false,
    val calendarDays: List<CalendarDayProjection> = emptyList(),
    val calendarEndOfMonthAccountsBalance: Long = 0L,
    val calendarEndOfMonthCardsBalance: Long = 0L,
    val calendarMonthPendingTxs: List<TransactionEntity> = emptyList(),
    val calendarMonthPaidTxs: List<TransactionEntity> = emptyList(),
    // Datos de Presupuesto por mes
    val budgetYear: Int = 2026,
    val budgetMonthZeroBased: Int = 10,
    val budgetMonthTitle: String = "Noviembre 2026",
    // Gráficos adicionales
    val analyticsFilter: AnalyticsRangeFilter = AnalyticsRangeFilter.MES,
    val customAnalyticsDays: Int = 60,
    val donutSlices: List<CategorySliceData> = emptyList(),
    val sixMonthBars: List<MonthlyBarPoint> = emptyList(),
    val balanceEvolution: List<BalanceEvolutionPoint> = emptyList(),
    val dailyExpensesCurrentMonth: List<DailyExpensePoint> = emptyList(),
    val dailyAverageExpense: Long = 0L,
    val categoryComparisons: List<CategoryMonthComparisonItem> = emptyList()
)

private data class CalendarAndBudgetNavState(
    val calYear: Int,
    val calMonth: Int,
    val showFlow: Boolean,
    val showCreditCards: Boolean,
    val budgetYear: Int,
    val budgetMonth: Int,
    val analyticsFilter: AnalyticsRangeFilter,
    val customAnalyticsDays: Int
)

class MiPlataViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val repository = MiPlataRepository(appContext)
    private val geminiService = GeminiFinanceService()

    private val initialCal = Calendar.getInstance()
    private val _navState = MutableStateFlow(
        CalendarAndBudgetNavState(
            calYear = initialCal.get(Calendar.YEAR),
            calMonth = initialCal.get(Calendar.MONTH),
            showFlow = false,
            showCreditCards = false,
            budgetYear = initialCal.get(Calendar.YEAR),
            budgetMonth = initialCal.get(Calendar.MONTH),
            analyticsFilter = AnalyticsRangeFilter.MES,
            customAnalyticsDays = 60
        )
    )

    private val _lastDeletedTransaction = MutableStateFlow<TransactionEntity?>(null)
    val lastDeletedTransaction: StateFlow<TransactionEntity?> = _lastDeletedTransaction.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _dailyGeminiTip = MutableStateFlow(
        "Usa el menú superior para abrir el Asistente Gemini o toca el botón '+' para registrar en cualquiera de las 8 modalidades."
    )
    val dailyGeminiTip: StateFlow<String> = _dailyGeminiTip.asStateFlow()

    private val _isGeneratingReport = MutableStateFlow(false)
    val isGeneratingReport: StateFlow<Boolean> = _isGeneratingReport.asStateFlow()

    private val _aiReport = MutableStateFlow<AiFinancialReport?>(null)
    val aiReport: StateFlow<AiFinancialReport?> = _aiReport.asStateFlow()

    private val _aiReportError = MutableStateFlow<String?>(null)
    val aiReportError: StateFlow<String?> = _aiReportError.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessageItem>>(
        listOf(
            ChatMessageItem(
                id = 1L,
                isUser = false,
                text = "¡Qué más! Soy tu asistente financiero de MiPlata. Pregúntame por tu disponible proyectado, tus tarjetas de crédito, compras a cuotas o presupuestos en COP."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessageItem>> = _chatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private val _isParsingAiDraft = MutableStateFlow(false)
    val isParsingAiDraft: StateFlow<Boolean> = _isParsingAiDraft.asStateFlow()

    private val _parsedDraft = MutableStateFlow<ParsedMovementDraft?>(null)
    val parsedDraft: StateFlow<ParsedMovementDraft?> = _parsedDraft.asStateFlow()

    private val _parseDraftError = MutableStateFlow<String?>(null)
    val parseDraftError: StateFlow<String?> = _parseDraftError.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureDefaultsSeeded()
        }
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MiPlataUiState> = combine(
        repository.userPreferences,
        repository.accountsFlow,
        repository.categoriesFlow,
        repository.transactionsFlow,
        repository.budgetsFlow,
        repository.savingGoalsFlow,
        repository.obligationsFlow,
        repository.debtsFlow,
        _navState
    ) { values: Array<Any?> ->
        val prefs = values[0] as UserPreferencesState
        val accounts = values[1] as List<AccountEntity>
        val categories = values[2] as List<CategoryEntity>
        val transactions = values[3] as List<TransactionEntity>
        val budgets = values[4] as List<BudgetEntity>
        val goals = values[5] as List<SavingGoalEntity>
        val obligations = values[6] as List<RecurringObligationEntity>
        val debts = values[7] as List<DebtEntity>
        val nav = values[8] as CalendarAndBudgetNavState

        buildUiState(
            prefs = prefs,
            accounts = accounts,
            categories = categories,
            transactions = transactions,
            budgets = budgets,
            goals = goals,
            obligations = obligations,
            debts = debts,
            nav = nav
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MiPlataUiState()
    )

    private fun buildUiState(
        prefs: UserPreferencesState,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        budgets: List<BudgetEntity>,
        goals: List<SavingGoalEntity>,
        obligations: List<RecurringObligationEntity>,
        debts: List<DebtEntity>,
        nav: CalendarAndBudgetNavState
    ): MiPlataUiState {
        val nowMillis = System.currentTimeMillis()
        val esLocale = Locale("es", "CO")
        val categoriesById = categories.associateBy { it.id }

        val periodWindow = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = prefs.periodMode,
            customCutoffDay = prefs.customCutoffDay,
            nowMillis = nowMillis
        )
        val monthWindow = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            customCutoffDay = 1,
            nowMillis = nowMillis
        )

        val txThisMonthPaid = transactions.filter {
            it.status == TransactionStatus.PAGADO &&
                it.timestamp in monthWindow.startTimestamp..monthWindow.endTimestamp
        }

        // 1. Saldos de todas las cuentas (actual vs proyectado)
        val accountsWithBalance = accounts.map { acc ->
            val currentBal = AvailableToSpendCalculator.computeAccountBalance(acc, transactions, includePending = false)
            val projectedBal = AvailableToSpendCalculator.computeAccountBalance(acc, transactions, includePending = true)
            val inc = txThisMonthPaid
                .filter { AvailableToSpendCalculator.isIncomeTransactionForTotals(it) && it.accountId == acc.id }
                .sumOf { it.amount }
            val exp = txThisMonthPaid
                .filter { AvailableToSpendCalculator.isExpenseTransactionForTotals(it) && it.accountId == acc.id }
                .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
            AccountWithBalance(
                account = acc,
                currentBalance = currentBal,
                projectedBalance = projectedBal,
                periodIncome = inc,
                periodExpense = exp
            )
        }
        val accountsById = accountsWithBalance.associateBy { it.account.id }

        val totalDebitBalance = accountsWithBalance
            .filter { it.account.type.normalized() == AccountType.DEBITO }
            .sumOf { it.currentBalance }

        val totalSavingsBalance = accountsWithBalance
            .filter { it.account.type.normalized() == AccountType.AHORROS }
            .sumOf { it.currentBalance }

        val creditCardSummaries = accounts
            .filter { it.type.normalized() == AccountType.TARJETA_CREDITO }
            .map { card -> AvailableToSpendCalculator.computeCreditCardSummary(card, transactions, nowMillis) }

        val totalCreditCardDebt = creditCardSummaries.sumOf { it.currentDebt }

        val totalLoansDebt = accountsWithBalance
            .filter { it.account.type.normalized() == AccountType.PRESTAMO }
            .sumOf { it.currentBalance }

        val totalLentReceivable = accountsWithBalance
            .filter { it.account.type.normalized() == AccountType.PRESTADO }
            .sumOf { it.currentBalance }

        // 2. Cálculo de "Disponible para gastar" en Inicio (Débito incluido, Ahorros excluido, Pago TC opcional)
        val availableResult = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = transactions,
            periodWindow = periodWindow,
            deductCreditCardPayment = prefs.deductCreditCardDueInAvailable
        )

        MiPlataWidgetProvider.pushWidgetUpdate(
            context = appContext,
            availablePerDay = availableResult.availableForPeriod,
            periodSubtitle = "${availableResult.periodLabel} • Restante proyectado",
            hideBalances = prefs.hideBalances
        )

        // 3. Carrusel horizontal "Este mes" con 3 tarjetas: Gasto general, Tarjetas de crédito y Flujo de efectivo
        val creditCardIds = accounts
            .filter { it.type.normalized() == AccountType.TARJETA_CREDITO }
            .map { it.id }
            .toSet()

        val generalExpensesThisMonth = txThisMonthPaid.filter {
            AvailableToSpendCalculator.isExpenseTransactionForTotals(it)
        }
        val generalExpenseTotal = generalExpensesThisMonth
            .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
            .coerceAtLeast(0L)

        val topTwoGeneral = generalExpensesThisMonth
            .groupBy { it.categoryId ?: 12L }
            .mapNotNull { (catId, list) ->
                val cat = categoriesById[catId] ?: return@mapNotNull null
                val sum = list.sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }.coerceAtLeast(0L)
                if (sum <= 0L) return@mapNotNull null
                CategorySliceData(
                    category = cat,
                    amount = sum,
                    percentage = if (generalExpenseTotal > 0L) (sum.toFloat() / generalExpenseTotal.toFloat()) * 100f else 0f,
                    transactionsCount = list.size
                )
            }
            .sortedByDescending { it.amount }
            .take(2)

        val cardExpensesThisMonth = generalExpensesThisMonth.filter { it.accountId in creditCardIds }
        val cardExpenseTotal = cardExpensesThisMonth
            .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
            .coerceAtLeast(0L)

        val topTwoCard = cardExpensesThisMonth
            .groupBy { it.categoryId ?: 12L }
            .mapNotNull { (catId, list) ->
                val cat = categoriesById[catId] ?: return@mapNotNull null
                val sum = list.sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }.coerceAtLeast(0L)
                if (sum <= 0L) return@mapNotNull null
                CategorySliceData(
                    category = cat,
                    amount = sum,
                    percentage = if (cardExpenseTotal > 0L) (sum.toFloat() / cardExpenseTotal.toFloat()) * 100f else 0f,
                    transactionsCount = list.size
                )
            }
            .sortedByDescending { it.amount }
            .take(2)

        val cashFlowIncomes = txThisMonthPaid
            .filter { AvailableToSpendCalculator.isIncomeTransactionForTotals(it) && !it.isInitialBalance }
            .sumOf { it.amount }
        val cashFlowOutflows = generalExpenseTotal
        val cashFlowNet = cashFlowIncomes - cashFlowOutflows

        val thisMonthCarousel = ThisMonthCarouselSummary(
            generalExpenseTotal = generalExpenseTotal,
            topTwoGeneralCategories = topTwoGeneral,
            creditCardExpenseTotal = cardExpenseTotal,
            topTwoCardCategories = topTwoCard,
            cashFlowNet = cashFlowNet,
            cashFlowIncomes = cashFlowIncomes,
            cashFlowOutflows = cashFlowOutflows
        )

        // 4. Lista "Próximo": transacciones pendientes ordenadas por fecha ascendente con saldo proyectado después
        val pendingSortedAsc = transactions
            .filter { it.status == TransactionStatus.PENDIENTE }
            .sortedBy { it.timestamp }

        var runningDebitProjected = availableResult.currentDebitBalance
        val upcomingPendingItems = pendingSortedAsc.map { tx ->
            val accItem = accountsById[tx.accountId]
            val acc = accItem?.account
            if (acc != null && acc.type.normalized() == AccountType.DEBITO) {
                runningDebitProjected += AvailableToSpendCalculator.computeAccountDelta(acc, tx)
            } else if (tx.type == TransactionType.PAGO_TARJETA) {
                runningDebitProjected -= tx.amount
            }
            UpcomingPendingItem(
                transaction = tx,
                accountName = acc?.name ?: "Cuenta",
                category = tx.categoryId?.let { categoriesById[it] },
                projectedBalanceAfter = runningDebitProjected
            )
        }

        // 5. Lista de Historial con el saldo de la cuenta después de cada transacción
        val runningPerAccount = accounts.associate { it.id to it.initialBalance }.toMutableMap()
        val chronologicalAsc = transactions.sortedWith(compareBy<TransactionEntity> { it.timestamp }.thenBy { it.id })
        val afterBalanceByTxId = mutableMapOf<Long, Long>()

        for (tx in chronologicalAsc) {
            val acc = accountsById[tx.accountId]?.account
            if (acc != null && tx.status == TransactionStatus.PAGADO) {
                val updated = (runningPerAccount[acc.id] ?: 0L) + AvailableToSpendCalculator.computeAccountDelta(acc, tx)
                runningPerAccount[acc.id] = updated
                afterBalanceByTxId[tx.id] = updated
            } else if (acc != null) {
                afterBalanceByTxId[tx.id] = runningPerAccount[acc.id] ?: acc.initialBalance
            }
            // Si es transferencia o pago de tarjeta, actualizar también cuenta destino
            val toId = tx.toAccountId
            if (toId != null && tx.status == TransactionStatus.PAGADO) {
                val toAcc = accountsById[toId]?.account
                if (toAcc != null) {
                    val updatedTo = (runningPerAccount[toId] ?: 0L) + AvailableToSpendCalculator.computeAccountDelta(toAcc, tx)
                    runningPerAccount[toId] = updatedTo
                }
            }
        }

        val historyItemsWithRunningBalance = transactions.map { tx ->
            val accName = accountsById[tx.accountId]?.account?.name ?: "Cuenta"
            val toAccName = tx.toAccountId?.let { accountsById[it]?.account?.name }
            HistoryTransactionItem(
                transaction = tx,
                accountName = accName,
                toAccountName = toAccName,
                category = tx.categoryId?.let { categoriesById[it] },
                accountBalanceAfter = afterBalanceByTxId[tx.id] ?: (accountsById[tx.accountId]?.currentBalance ?: 0L)
            )
        }

        // 6. Cálculos del Calendario para el mes seleccionado en nav
        val calMonthInstance = Calendar.getInstance().apply {
            set(nav.calYear, nav.calMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val calMaxDay = calMonthInstance.getActualMaximum(Calendar.DAY_OF_MONTH)
        val calMonthEndInstance = (calMonthInstance.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, calMaxDay)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val monthFormatter = SimpleDateFormat("MMMM yyyy", esLocale)
        val calendarMonthTitle = monthFormatter.format(Date(calMonthInstance.timeInMillis))
            .replaceFirstChar { it.uppercase() }
        val shortMonthName = SimpleDateFormat("MMM", esLocale).format(Date(calMonthInstance.timeInMillis))
        val calendarLastDayLabel = "$calMaxDay de $shortMonthName"

        val calendarDaysForDebit = AvailableToSpendCalculator.computeDailyProjectedBalancesForMonth(
            year = nav.calYear,
            monthZeroBased = nav.calMonth,
            accounts = accounts,
            allTransactions = transactions,
            forCreditCards = false,
            nowMillis = nowMillis
        )
        val calendarDaysForCards = AvailableToSpendCalculator.computeDailyProjectedBalancesForMonth(
            year = nav.calYear,
            monthZeroBased = nav.calMonth,
            accounts = accounts,
            allTransactions = transactions,
            forCreditCards = true,
            nowMillis = nowMillis
        )
        val activeCalendarDays = if (nav.showCreditCards) calendarDaysForCards else calendarDaysForDebit
        val endOfMonthAccountsBal = calendarDaysForDebit.lastOrNull()?.projectedClosingBalance ?: totalDebitBalance
        val endOfMonthCardsBal = calendarDaysForCards.lastOrNull()?.projectedClosingBalance ?: totalCreditCardDebt

        val txsInCalendarMonth = transactions.filter {
            it.timestamp in calMonthInstance.timeInMillis..calMonthEndInstance.timeInMillis
        }
        val calendarPendingTxs = txsInCalendarMonth
            .filter { it.status == TransactionStatus.PENDIENTE }
            .sortedBy { it.timestamp }
        val calendarPaidTxs = txsInCalendarMonth
            .filter { it.status == TransactionStatus.PAGADO }
            .sortedByDescending { it.timestamp }

        // 7. Cálculos de Presupuesto para el mes seleccionado en nav
        val budgetCalStart = Calendar.getInstance().apply {
            set(nav.budgetYear, nav.budgetMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val budgetMaxDay = budgetCalStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val budgetCalEnd = (budgetCalStart.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, budgetMaxDay)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val budgetMonthTitle = monthFormatter.format(Date(budgetCalStart.timeInMillis))
            .replaceFirstChar { it.uppercase() }

        val txsInBudgetMonth = transactions.filter {
            it.status == TransactionStatus.PAGADO &&
                it.timestamp in budgetCalStart.timeInMillis..budgetCalEnd.timeInMillis &&
                AvailableToSpendCalculator.isExpenseTransactionForTotals(it)
        }

        val budgetsByCategoryId = budgets.associateBy { it.categoryId }
        val categoryBudgetItems = categories
            .filter { it.type == CategoryType.GASTO }
            .map { cat ->
                val catTxs = txsInBudgetMonth.filter { it.categoryId == cat.id }
                val spent = catTxs.sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }.coerceAtLeast(0L)
                val limit = max(cat.monthlyLimit, budgetsByCategoryId[cat.id]?.monthlyLimit ?: 0L)
                val pct = if (limit > 0L) {
                    ((spent.toDouble() / limit.toDouble()) * 100.0).roundToInt()
                } else 0
                CategoryBudgetSummaryItem(
                    category = cat.copy(monthlyLimit = limit),
                    groupName = cat.groupName.ifBlank { "Sin grupo" },
                    spentInMonth = spent,
                    transactionsCount = catTxs.size,
                    monthlyLimit = limit,
                    percentageUsed = pct,
                    transactionsInMonth = catTxs
                )
            }

        val budgetItems = categoryBudgetItems
            .filter { it.monthlyLimit > 0L }
            .map { item ->
                BudgetProgressItem(
                    budget = BudgetEntity(categoryId = item.category.id, monthlyLimit = item.monthlyLimit),
                    category = item.category,
                    spentThisMonth = item.spentInMonth,
                    percentage = item.percentageUsed,
                    projectedEndOfMonth = item.spentInMonth,
                    willExceedAtCurrentPace = item.percentageUsed in 80..99
                )
            }

        return MiPlataUiState(
            isLoading = false,
            prefs = prefs,
            accountsWithBalance = accountsWithBalance,
            creditCardSummaries = creditCardSummaries,
            totalDebitBalance = totalDebitBalance,
            totalSavingsBalance = totalSavingsBalance,
            totalCreditCardDebt = totalCreditCardDebt,
            totalLoansDebt = totalLoansDebt,
            totalLentReceivable = totalLentReceivable,
            totalBalance = totalDebitBalance,
            categories = categories,
            allTransactions = transactions,
            availableToSpend = availableResult,
            thisMonthCarousel = thisMonthCarousel,
            upcomingPendingItems = upcomingPendingItems,
            historyItemsWithRunningBalance = historyItemsWithRunningBalance,
            upcomingObligations7Days = obligations,
            allObligations = obligations,
            recentFiveTransactions = transactions.take(5),
            budgetItems = budgetItems,
            categoryBudgetItems = categoryBudgetItems,
            savingGoals = goals,
            debts = debts,
            smartAlerts = emptyList(),
            projectedEndOfMonthBalance = availableResult.availableForPeriod,
            calendarYear = nav.calYear,
            calendarMonthZeroBased = nav.calMonth,
            calendarMonthTitle = calendarMonthTitle,
            calendarLastDayLabel = calendarLastDayLabel,
            calendarShowFlowInsteadOfBalance = nav.showFlow,
            calendarShowCreditCards = nav.showCreditCards,
            calendarDays = activeCalendarDays,
            calendarEndOfMonthAccountsBalance = endOfMonthAccountsBal,
            calendarEndOfMonthCardsBalance = endOfMonthCardsBal,
            calendarMonthPendingTxs = calendarPendingTxs,
            calendarMonthPaidTxs = calendarPaidTxs,
            budgetYear = nav.budgetYear,
            budgetMonthZeroBased = nav.budgetMonth,
            budgetMonthTitle = budgetMonthTitle,
            analyticsFilter = nav.analyticsFilter,
            customAnalyticsDays = nav.customAnalyticsDays,
            donutSlices = topTwoGeneral
        )
    }

    // --- CONTROLES DE CALENDARIO, PRESUPUESTO Y DISPONIBLE ---

    fun changeCalendarMonth(deltaMonths: Int) {
        val curr = _navState.value
        val cal = Calendar.getInstance().apply {
            set(curr.calYear, curr.calMonth, 1)
            add(Calendar.MONTH, deltaMonths)
        }
        _navState.value = curr.copy(
            calYear = cal.get(Calendar.YEAR),
            calMonth = cal.get(Calendar.MONTH)
        )
    }

    fun toggleCalendarFlowMode(showFlow: Boolean) {
        _navState.value = _navState.value.copy(showFlow = showFlow)
    }

    fun toggleCalendarCreditCardsMode(showCreditCards: Boolean) {
        _navState.value = _navState.value.copy(showCreditCards = showCreditCards)
    }

    fun changeBudgetMonth(deltaMonths: Int) {
        val curr = _navState.value
        val cal = Calendar.getInstance().apply {
            set(curr.budgetYear, curr.budgetMonth, 1)
            add(Calendar.MONTH, deltaMonths)
        }
        _navState.value = curr.copy(
            budgetYear = cal.get(Calendar.YEAR),
            budgetMonth = cal.get(Calendar.MONTH)
        )
    }

    fun setDeductCreditCardInAvailable(enabled: Boolean) {
        repository.updatePreferences { it.copy(deductCreditCardDueInAvailable = enabled) }
    }

    fun toggleAccountIncludedInAvailable(account: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(account.copy(includeInAvailableFilter = !account.includeInAvailableFilter))
        }
    }

    fun setAnalyticsFilter(filter: AnalyticsRangeFilter, customDays: Int = 60) {
        _navState.value = _navState.value.copy(
            analyticsFilter = filter,
            customAnalyticsDays = customDays.coerceIn(7, 365)
        )
    }

    fun toggleHideBalances() {
        repository.updatePreferences { it.copy(hideBalances = !it.hideBalances) }
    }

    fun setPeriodConfiguration(mode: PeriodMode, cutoffDay: Int, baseIncome: Long) {
        repository.updatePreferences {
            it.copy(
                periodMode = mode,
                customCutoffDay = cutoffDay.coerceIn(1, 28),
                baseMonthlyIncome = baseIncome.coerceAtLeast(0L)
            )
        }
    }

    fun toggleThemeMode(darkOverride: Boolean?) {
        repository.updatePreferences { it.copy(darkModeOverride = darkOverride) }
    }

    fun configurePinLock(enabled: Boolean, pinCode: String) {
        repository.updatePreferences {
            it.copy(pinLockEnabled = enabled && pinCode.length >= 4, pinCode = pinCode)
        }
        _isAppUnlocked.value = true
    }

    fun unlockWithPin(enteredPin: String): Boolean {
        val currentPin = uiState.value.prefs.pinCode
        return if (enteredPin == currentPin || currentPin.isBlank()) {
            _isAppUnlocked.value = true
            true
        } else {
            false
        }
    }

    fun unlockWithBiometricDeviceCredential() {
        _isAppUnlocked.value = true
    }

    fun completeOnboarding(
        efectivoInitial: Long,
        nuInitial: Long,
        nequiInitial: Long,
        periodMode: PeriodMode,
        customCutoffDay: Int,
        baseMonthlyIncome: Long,
        firstObligationName: String,
        firstObligationAmount: Long,
        firstObligationDay: Int
    ) {
        viewModelScope.launch {
            repository.ensureDefaultsSeeded()
            val accounts = uiState.value.accountsWithBalance.map { it.account }
            accounts.forEach { acc ->
                val newInit = when {
                    acc.name.contains("Efectivo", ignoreCase = true) -> efectivoInitial
                    acc.name.contains("Nu Débito", ignoreCase = true) || acc.id == 2L -> nuInitial
                    acc.name.contains("Nequi", ignoreCase = true) -> nequiInitial
                    else -> acc.initialBalance
                }
                repository.updateAccount(acc.copy(initialBalance = newInit))
            }

            if (firstObligationName.isNotBlank() && firstObligationAmount > 0L) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, firstObligationDay.coerceIn(1, 28))
                }
                repository.saveTransaction(
                    TransactionEntity(
                        type = TransactionType.PAGO_PROGRAMADO,
                        amount = firstObligationAmount,
                        timestamp = cal.timeInMillis,
                        accountId = 2L,
                        categoryId = 3L,
                        note = firstObligationName.trim(),
                        status = TransactionStatus.PENDIENTE,
                        recurrence = RecurrenceFrequency.MENSUAL,
                        isRecurring = true
                    )
                )
            }

            repository.updatePreferences {
                it.copy(
                    onboardingCompleted = true,
                    periodMode = periodMode,
                    customCutoffDay = customCutoffDay.coerceIn(1, 28),
                    baseMonthlyIncome = baseMonthlyIncome
                )
            }
        }
    }

    fun loadThreeMonthDemo() {
        viewModelScope.launch {
            repository.loadThreeMonthDemoData()
            _statusBannerMessage.value = "Se cargaron datos de ejemplo (Débito, Ahorros, Tarjetas, Préstamos y Próximos pagos)."
        }
    }

    fun clearDemoData() {
        viewModelScope.launch {
            repository.clearDemoDataOnly()
            _statusBannerMessage.value = "Datos de demostración eliminados."
        }
    }

    fun dismissStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun showStatusMessage(message: String) {
        _statusBannerMessage.value = message
    }

    /**
     * Guarda una transacción con todos los campos ampliados (8 tipos, estado pagado/pendiente,
     * recurrencia y número de cuotas).
     */
    fun saveTransaction(
        id: Long = 0L,
        type: TransactionType,
        amount: Long,
        accountId: Long,
        toAccountId: Long? = null,
        categoryId: Long? = null,
        note: String = "",
        receiptUri: String? = null,
        isRecurring: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
        status: TransactionStatus = if (type == TransactionType.PAGO_PROGRAMADO) TransactionStatus.PENDIENTE else TransactionStatus.PAGADO,
        recurrence: RecurrenceFrequency = if (isRecurring) RecurrenceFrequency.MENSUAL else RecurrenceFrequency.NINGUNA,
        customRecurrenceDays: Int = 30,
        totalInstallments: Int = 1
    ) {
        if (amount <= 0L) return
        viewModelScope.launch {
            val needsDestinationAccount = type == TransactionType.TRANSFERENCIA || type == TransactionType.PAGO_TARJETA
            val tx = TransactionEntity(
                id = id,
                type = type,
                amount = amount,
                timestamp = timestamp,
                accountId = accountId,
                toAccountId = if (needsDestinationAccount) toAccountId else null,
                categoryId = if (needsDestinationAccount) null else categoryId,
                note = note.trim(),
                receiptUri = receiptUri,
                isRecurring = recurrence != RecurrenceFrequency.NINGUNA || isRecurring,
                status = status,
                recurrence = recurrence,
                customRecurrenceDays = customRecurrenceDays,
                totalInstallments = if (type == TransactionType.COMPRA_CUOTAS) totalInstallments.coerceAtLeast(2) else 1
            )
            repository.saveTransaction(tx)

            if (status == TransactionStatus.PAGADO && AvailableToSpendCalculator.isExpenseTransactionForTotals(tx) && categoryId != null) {
                val state = uiState.value
                val catItem = state.categoryBudgetItems.find { it.category.id == categoryId }
                if (catItem != null && catItem.monthlyLimit > 0L) {
                    val newSpent = catItem.spentInMonth + AvailableToSpendCalculator.netCategoryExpenseAmount(tx)
                    val newPct = ((newSpent.toDouble() / catItem.monthlyLimit.toDouble()) * 100.0).roundToInt()
                    if (newPct >= 80) {
                        NotificationHelper.sendBudgetAlertNotification(
                            context = appContext,
                            categoryName = catItem.category.name,
                            percentage = newPct,
                            spentCop = newSpent,
                            limitCop = catItem.monthlyLimit
                        )
                    }
                }
            }
        }
    }

    fun markTransactionPaid(tx: TransactionEntity) {
        viewModelScope.launch {
            repository.markTransactionAsPaid(tx)
            _statusBannerMessage.value = "Transacción marcada como pagada."
        }
    }

    fun skipPendingTransaction(tx: TransactionEntity) {
        viewModelScope.launch {
            repository.skipPendingOccurrence(tx)
            _statusBannerMessage.value = "Ocurrencia omitida."
        }
    }

    fun deleteTransactionWithUndo(tx: TransactionEntity) {
        viewModelScope.launch {
            _lastDeletedTransaction.value = tx
            repository.deleteTransaction(tx)
        }
    }

    fun undoDeleteTransaction() {
        val deleted = _lastDeletedTransaction.value ?: return
        viewModelScope.launch {
            repository.saveTransaction(deleted)
            _lastDeletedTransaction.value = null
        }
    }

    fun clearUndoState() {
        _lastDeletedTransaction.value = null
    }

    fun saveAccountFull(
        name: String,
        type: AccountType,
        initialBalance: Long,
        colorHex: Long,
        iconName: String,
        creditLimit: Long = 0L,
        billingCutoffDay: Int = 15,
        paymentDueDay: Int = 5,
        lastFourDigits: String = "",
        personName: String = "",
        originalAmount: Long = 0L,
        receivedOrPaidAmount: Long = 0L,
        monthlyInstallment: Long = 0L
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.saveAccount(
                AccountEntity(
                    name = name.trim(),
                    type = type.normalized(),
                    initialBalance = initialBalance,
                    colorHex = colorHex,
                    iconName = iconName,
                    creditLimit = creditLimit,
                    billingCutoffDay = billingCutoffDay.coerceIn(1, 28),
                    paymentDueDay = paymentDueDay.coerceIn(1, 28),
                    lastFourDigits = lastFourDigits.takeLast(4),
                    personName = personName.trim(),
                    originalAmount = originalAmount,
                    receivedOrPaidAmount = receivedOrPaidAmount,
                    monthlyInstallment = monthlyInstallment,
                    includeInAvailableFilter = type.normalized() == AccountType.DEBITO
                )
            )
        }
    }

    fun registerLoanOrLentAbono(targetAccount: AccountEntity, amount: Long, debitAccountId: Long) {
        if (amount <= 0L) return
        viewModelScope.launch {
            repository.registerLoanOrLentPayment(targetAccount, amount, debitAccountId)
            _statusBannerMessage.value = "Abono de ${CopFormatter.format(amount)} registrado correctamente."
        }
    }

    fun updateCategoryBudgetLimit(category: CategoryEntity, newLimit: Long, groupName: String = category.groupName) {
        viewModelScope.launch {
            repository.updateCategoryLimitAndGroup(category, newLimit, groupName)
            _statusBannerMessage.value = if (newLimit > 0L) {
                "Límite de ${CopFormatter.format(newLimit)} guardado para ${category.name}."
            } else {
                "${category.name} quedó configurada sin límite mensual."
            }
        }
    }

    fun saveBudget(categoryId: Long, monthlyLimit: Long) {
        val cat = uiState.value.categories.find { it.id == categoryId }
        if (cat != null) {
            updateCategoryBudgetLimit(cat, monthlyLimit)
        }
    }

    fun deleteBudget(budget: BudgetEntity) {
        val cat = uiState.value.categories.find { it.id == budget.categoryId }
        if (cat != null) {
            updateCategoryBudgetLimit(cat, 0L)
        }
    }

    fun saveSavingGoal(name: String, targetAmount: Long, monthlyContribution: Long, monthsToDeadline: Int) {
        if (name.isBlank() || targetAmount <= 0L) return
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + monthsToDeadline.coerceAtLeast(1) * 30L * 24L * 60 * 60 * 1000L
            repository.saveSavingGoal(
                SavingGoalEntity(
                    name = name.trim(),
                    targetAmount = targetAmount,
                    savedAmount = 0L,
                    deadlineTimestamp = deadline,
                    monthlyTargetContribution = monthlyContribution.coerceAtLeast(10_000L)
                )
            )
        }
    }

    fun contributeToGoal(goal: SavingGoalEntity, amount: Long, accountId: Long) {
        if (amount <= 0L) return
        viewModelScope.launch {
            repository.contributeToSavingGoal(goal, amount, accountId)
            _statusBannerMessage.value = "Aporte de ${CopFormatter.format(amount)} registrado."
        }
    }

    fun deleteSavingGoal(goal: SavingGoalEntity) {
        viewModelScope.launch { repository.deleteSavingGoal(goal) }
    }

    fun saveObligation(name: String, amount: Long, dayOfMonth: Int, accountId: Long, categoryId: Long) {
        if (name.isBlank() || amount <= 0L) return
        viewModelScope.launch {
            repository.saveObligation(
                RecurringObligationEntity(
                    name = name.trim(),
                    amount = amount,
                    dayOfMonth = dayOfMonth.coerceIn(1, 31),
                    accountId = accountId,
                    categoryId = categoryId
                )
            )
        }
    }

    fun toggleObligationPaid(obligation: RecurringObligationEntity) {
        viewModelScope.launch { repository.toggleObligationPaidThisMonth(obligation) }
    }

    fun deleteObligation(obligation: RecurringObligationEntity) {
        viewModelScope.launch { repository.deleteObligation(obligation) }
    }

    fun saveDebt(creditorName: String, totalAmount: Long, monthlyInstallment: Long, remainingBalance: Long, dueDay: Int) {
        if (creditorName.isBlank() || totalAmount <= 0L) return
        viewModelScope.launch {
            repository.saveDebt(
                DebtEntity(
                    creditorName = creditorName.trim(),
                    totalAmount = totalAmount,
                    monthlyInstallment = monthlyInstallment.coerceAtLeast(10_000L),
                    remainingBalance = remainingBalance.coerceIn(0L, totalAmount),
                    dueDayOfMonth = dueDay.coerceIn(1, 31)
                )
            )
        }
    }

    fun payDebt(debt: DebtEntity, amount: Long, accountId: Long) {
        if (amount <= 0L) return
        viewModelScope.launch {
            repository.payDebtInstallment(debt, amount, accountId)
        }
    }

    fun deleteDebt(debt: DebtEntity) {
        viewModelScope.launch { repository.deleteDebt(debt) }
    }

    // --- FUNCIONES DE GEMINI ---

    private fun buildAggregatedFinancialSummary(): String {
        val s = uiState.value
        val av = s.availableToSpend
        val accountsSummary = s.accountsWithBalance.joinToString("; ") {
            "${it.account.name} (${it.account.type.label}): ${CopFormatter.format(it.currentBalance)}"
        }
        val cardsSummary = s.creditCardSummaries.joinToString("; ") {
            "${it.account.name} ••••${it.account.lastFourDigits}: deuda ${CopFormatter.format(it.currentDebt)}, vence en ${it.daysUntilPaymentDue} días"
        }
        val categoriesSummary = s.categoryBudgetItems.filter { it.spentInMonth > 0L }.joinToString("; ") {
            "${it.category.name} (${it.groupName}): gastado ${CopFormatter.format(it.spentInMonth)}" +
                if (it.monthlyLimit > 0L) " de ${CopFormatter.format(it.monthlyLimit)} (${it.percentageUsed}%)" else ""
        }

        return """
            - Disponible restante del período (${av.periodLabel}): ${CopFormatter.format(av.availableForPeriod)}
            - Balance actual cuentas Débito: ${CopFormatter.format(av.currentDebitBalance)}
            - Balance proyectado (+ ingresos pendientes): ${CopFormatter.format(av.projectedBalance)}
            - Gasto proyectado del período: ${CopFormatter.format(av.projectedExpense)} (incluye Pago de tarjeta por ${CopFormatter.format(av.creditCardPaymentDueInPeriod)})
            - Dinero apartado en Ahorros (fuera del disponible): ${CopFormatter.format(s.totalSavingsBalance)}
            - Tarjetas de crédito: ${cardsSummary.ifBlank { "Sin deuda en tarjetas" }}
            - Préstamos por pagar: ${CopFormatter.format(s.totalLoansDebt)} | Dinero prestado por cobrar: ${CopFormatter.format(s.totalLentReceivable)}
            - Cuentas: $accountsSummary
            - Gastos por categoría este mes: ${categoriesSummary.ifBlank { "Sin gastos registrados" }}
        """.trimIndent()
    }

    fun sendChatMessageToGemini(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank() || _isChatLoading.value) return

        val userMsg = ChatMessageItem(id = System.currentTimeMillis(), isUser = true, text = trimmed)
        val aiMsgId = System.currentTimeMillis() + 1L
        val placeholderAiMsg = ChatMessageItem(id = aiMsgId, isUser = false, text = "", isStreaming = true)

        _chatMessages.value = _chatMessages.value + userMsg + placeholderAiMsg
        _isChatLoading.value = true

        viewModelScope.launch {
            val aggregatedContext = buildAggregatedFinancialSummary()
            val accumulated = StringBuilder()
            try {
                geminiService.streamFinancialChatAnswer(trimmed, aggregatedContext)
                    .collect { chunk ->
                        accumulated.append(chunk)
                        val currentText = accumulated.toString()
                        _chatMessages.value = _chatMessages.value.map { msg ->
                            if (msg.id == aiMsgId) msg.copy(text = currentText, isStreaming = true) else msg
                        }
                    }

                val finalText = accumulated.toString().ifBlank {
                    "No recibí texto de Gemini en este momento. Intenta preguntar de nuevo."
                }
                _chatMessages.value = _chatMessages.value.map { msg ->
                    if (msg.id == aiMsgId) msg.copy(text = finalText, isStreaming = false) else msg
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorText = "Sin conexión con Gemini (${e.localizedMessage ?: "modo offline"}). Tu disponible restante actual es ${CopFormatter.format(uiState.value.availableToSpend.availableForPeriod)}."
                _chatMessages.value = _chatMessages.value.map { msg ->
                    if (msg.id == aiMsgId) msg.copy(text = errorText, isStreaming = false, isError = true) else msg
                }
            } finally {
                _isChatLoading.value = false
            }
        }
    }

    fun requestGeminiReportAndTip() {
        if (_isGeneratingReport.value) return
        _isGeneratingReport.value = true
        _aiReportError.value = null

        viewModelScope.launch {
            val contextSummary = buildAggregatedFinancialSummary()
            val result = geminiService.generateStructuredReport(contextSummary)
            result.onSuccess { report ->
                _aiReport.value = report
                if (report.shortDailyTip.isNotBlank()) {
                    _dailyGeminiTip.value = report.shortDailyTip
                }
            }.onFailure { err ->
                _aiReportError.value = err.message ?: "No se pudo conectar con Gemini."
            }
            _isGeneratingReport.value = false
        }
    }

    fun parseVoiceOrTextToDraft(rawText: String) {
        if (rawText.isBlank()) return
        _isParsingAiDraft.value = true
        _parseDraftError.value = null
        _parsedDraft.value = null

        viewModelScope.launch {
            val categories = uiState.value.categories.map { it.name }
            val accounts = uiState.value.accountsWithBalance.map { it.account.name }
            val res = geminiService.parseNaturalLanguageMovement(rawText, categories, accounts)
            res.onSuccess { draft -> _parsedDraft.value = draft }
                .onFailure { err -> _parseDraftError.value = err.message }
            _isParsingAiDraft.value = false
        }
    }

    fun parseReceiptBitmapToDraft(bitmap: Bitmap) {
        _isParsingAiDraft.value = true
        _parseDraftError.value = null
        _parsedDraft.value = null

        viewModelScope.launch {
            val categories = uiState.value.categories.filter { it.type == CategoryType.GASTO }.map { it.name }
            val accounts = uiState.value.accountsWithBalance.map { it.account.name }
            val res = geminiService.parseReceiptPhoto(bitmap, categories, accounts)
            res.onSuccess { draft -> _parsedDraft.value = draft }
                .onFailure { err -> _parseDraftError.value = err.message }
            _isParsingAiDraft.value = false
        }
    }

    fun autoCategorizeNote(note: String, onCategoryResolved: (CategoryEntity) -> Unit) {
        if (note.isBlank()) return
        viewModelScope.launch {
            val allCats = uiState.value.categories
            val res = geminiService.suggestCategoryFromNote(note, allCats.map { it.name })
            res.onSuccess { suggestedName ->
                val match = allCats.find { it.name.equals(suggestedName, ignoreCase = true) }
                if (match != null) onCategoryResolved(match)
            }
        }
    }

    fun clearParsedDraft() {
        _parsedDraft.value = null
        _parseDraftError.value = null
    }
}
