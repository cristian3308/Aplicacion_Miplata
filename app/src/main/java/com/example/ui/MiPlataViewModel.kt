package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.AiFinancialReport
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.AccountRemovalOutcome
import com.example.data.GeminiFinanceService
import com.example.data.MiPlataRepository
import com.example.data.OnboardingAccountChoice
import com.example.data.ParsedMovementDraft
import com.example.data.PeriodMode
import com.example.data.PinUnlockResult
import com.example.data.RecurrenceFrequency
import com.example.data.SavingGoalEntity
import com.example.data.SystemCategoryKeys
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.data.UserPreferencesState
import com.example.domain.AnalyticsCalculator
import com.example.domain.AnalyticsUiState
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.AvailableToSpendResult
import com.example.domain.CalendarDayProjection
import com.example.domain.CopFormatter
import com.example.domain.CreditCardSummary
import com.example.ui.widget.MiPlataWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * inmediatamente después de aplicarla y su etiqueta si está vencida ("Vencido" o "Ingreso atrasado").
 */
data class UpcomingPendingItem(
    val transaction: TransactionEntity,
    val accountName: String,
    val category: CategoryEntity?,
    val projectedBalanceAfter: Long,
    val isOverdue: Boolean = false,
    val statusLabel: String? = null
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
    val transactionsInMonth: List<TransactionEntity>,
    val projectedEndOfMonth: Long = 0L,
    val willExceedAtCurrentPace: Boolean = false
)

data class BudgetProgressItem(
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
    val archivedAccounts: List<AccountWithBalance> = emptyList(),
    val accountIdsWithMovements: Set<Long> = emptySet(),
    val categoryMovementCounts: Map<Long, Int> = emptyMap(),
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
    val recentFiveTransactions: List<TransactionEntity> = emptyList(),
    val budgetItems: List<BudgetProgressItem> = emptyList(),
    val categoryBudgetItems: List<CategoryBudgetSummaryItem> = emptyList(),
    val savingGoals: List<SavingGoalEntity> = emptyList(),
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
    val geminiService = GeminiFinanceService(
        isAiEnabledProvider = {
            val p = repository.userPreferences.value
            p.aiFeaturesEnabled && p.aiPrivacyConsentAccepted
        }
    )

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
    private var backgroundedAtMillis: Long? = null

    private val _dailyGeminiTip = MutableStateFlow(
        "Usa el menú superior para abrir el Asistente Gemini o toca el botón '+' para registrar en cualquiera de las 8 modalidades."
    )
    val dailyGeminiTip: StateFlow<String> = _dailyGeminiTip.asStateFlow()

    private val _isGeneratingReport = MutableStateFlow(false)
    val isGeneratingReport: StateFlow<Boolean> = _isGeneratingReport.asStateFlow()

    private val _aiReport = MutableStateFlow<AiFinancialReport?>(repository.loadPersistedAiReport())
    val aiReport: StateFlow<AiFinancialReport?> = _aiReport.asStateFlow()

    private val _aiReportError = MutableStateFlow<String?>(null)
    val aiReportError: StateFlow<String?> = _aiReportError.asStateFlow()

    private val _showAiPrivacyConsentDialog = MutableStateFlow(false)
    val showAiPrivacyConsentDialog: StateFlow<Boolean> = _showAiPrivacyConsentDialog.asStateFlow()
    private var pendingAiActionAfterConsent: (() -> Unit)? = null

    private val _chatMessages = MutableStateFlow<List<ChatMessageItem>>(
        listOf(
            ChatMessageItem(
                id = 0L,
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

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MiPlataUiState> = combine(
        repository.userPreferences,
        repository.accountsFlow,
        repository.categoriesFlow,
        repository.transactionsFlow,
        repository.savingGoalsFlow,
        _navState
    ) { values: Array<Any?> ->
        val prefs = values[0] as UserPreferencesState
        val accounts = values[1] as List<AccountEntity>
        val categories = values[2] as List<CategoryEntity>
        val transactions = values[3] as List<TransactionEntity>
        val goals = values[4] as List<SavingGoalEntity>
        val nav = values[5] as CalendarAndBudgetNavState

        buildUiState(
            prefs = prefs,
            accounts = accounts,
            categories = categories,
            transactions = transactions,
            goals = goals,
            nav = nav
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MiPlataUiState()
    )

    /**
     * StateFlow dedicado exclusivamente a la pantalla de Análisis:
     * - Calcula los 5 gráficos en Dispatchers.Default.
     * - Solo se mantiene activo mientras la pantalla de Análisis esté abierta (SharingStarted.WhileSubscribed()).
     */
    val analyticsUiState: StateFlow<AnalyticsUiState> = combine(
        repository.accountsFlow,
        repository.categoriesFlow,
        repository.transactionsFlow,
        _navState.map { it.analyticsFilter to it.customAnalyticsDays }.distinctUntilChanged()
    ) { accounts, categories, transactions, (filter, customDays) ->
        AnalyticsCalculator.computeAnalyticsState(
            filter = filter,
            customDays = customDays,
            accounts = accounts,
            categories = categories,
            transactions = transactions
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = AnalyticsUiState()
        )

    init {
        viewModelScope.launch {
            repository.ensureDefaultsSeeded()
        }
        // Colector independiente para el widget: solo dispara cuando cambia el valor mostrado
        viewModelScope.launch {
            uiState
                .filter { !it.isLoading }
                .map { state ->
                    MiPlataWidgetProvider.buildWidgetDisplayData(
                        available = state.availableToSpend,
                        hideBalances = state.prefs.hideBalances
                    )
                }
                .distinctUntilChanged()
                .collect { widgetData ->
                    MiPlataWidgetProvider.pushWidgetUpdate(
                        context = appContext,
                        availablePerDay = widgetData.availablePerDay,
                        availableForPeriod = widgetData.availableForPeriod,
                        periodLabel = widgetData.periodLabel,
                        hideBalances = widgetData.hideBalances
                    )
                }
        }
        // Programación automática del recordatorio diario con WorkManager al cambiar hora o interruptor
        viewModelScope.launch {
            repository.userPreferences
                .map { it.dailyReminderEnabled to it.dailyReminderHour }
                .distinctUntilChanged()
                .collect { (enabled, hour) ->
                    ReminderScheduler.scheduleOrCancelDailyReminder(
                        context = appContext,
                        enabled = enabled,
                        hourOfDay = hour
                    )
                }
        }
        // Programación automática en WorkManager de recordatorios un día antes y el mismo día de pagos pendientes
        viewModelScope.launch {
            repository.transactionsFlow
                .map { list -> list.filter { it.status == TransactionStatus.PENDIENTE } }
                .distinctUntilChanged()
                .collect { pendingTxs ->
                    ReminderScheduler.syncPendingPaymentReminders(
                        context = appContext,
                        transactions = pendingTxs
                    )
                }
        }
    }

    private fun buildUiState(
        prefs: UserPreferencesState,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        goals: List<SavingGoalEntity>,
        nav: CalendarAndBudgetNavState,
        nowMillis: Long = System.currentTimeMillis()
    ): MiPlataUiState {
        val esLocale = Locale("es", "CO")
        val categoriesById = categories.associateBy { it.id }
        val fallbackOtrosCategoryId = MiPlataRepository.resolveCategoryId(
            categories = categories,
            systemKey = SystemCategoryKeys.OTROS_GASTO,
            fallbackType = CategoryType.GASTO
        )

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
        val allAccountsWithBalance = accounts.map { acc ->
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
        // Para el historial conservamos todas las cuentas (incluidas las archivadas)
        val accountsById = allAccountsWithBalance.associateBy { it.account.id }
        // En selectores y totales solo participan las cuentas activas (!isArchived)
        val accountsWithBalance = allAccountsWithBalance.filter { !it.account.isArchived }
        val archivedAccounts = allAccountsWithBalance.filter { it.account.isArchived }

        val accountIdsWithMovements = buildSet {
            for (tx in transactions) {
                add(tx.accountId)
                tx.toAccountId?.let { add(it) }
            }
        }
        val categoryMovementCounts = transactions
            .mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()

        val totalDebitBalance = accountsWithBalance
            .filter { it.account.type == AccountType.DEBITO }
            .sumOf { it.currentBalance }

        val totalSavingsBalance = accountsWithBalance
            .filter { it.account.type == AccountType.AHORROS }
            .sumOf { it.currentBalance }

        val creditCardSummaries = accounts
            .filter { !it.isArchived && it.type == AccountType.TARJETA_CREDITO }
            .map { card -> AvailableToSpendCalculator.computeCreditCardSummary(card, transactions, nowMillis) }

        val totalCreditCardDebt = creditCardSummaries.sumOf { it.currentDebt }

        val totalLoansDebt = accountsWithBalance
            .filter { it.account.type == AccountType.PRESTAMO }
            .sumOf { it.currentBalance }

        val totalLentReceivable = accountsWithBalance
            .filter { it.account.type == AccountType.PRESTADO }
            .sumOf { it.currentBalance }

        // 2. Cálculo de "Disponible para gastar" en Inicio (Débito incluido, Ahorros excluido, Pago TC opcional)
        val availableResult = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = transactions,
            periodWindow = periodWindow,
            deductCreditCardPayment = prefs.deductCreditCardDueInAvailable,
            nowMillis = nowMillis
        )

        // 3. Carrusel horizontal "Este mes" con 3 tarjetas: Gasto general, Tarjetas de crédito y Flujo de efectivo
        val creditCardIds = accounts
            .filter { it.type == AccountType.TARJETA_CREDITO }
            .map { it.id }
            .toSet()

        val generalExpensesThisMonth = txThisMonthPaid.filter {
            AvailableToSpendCalculator.isExpenseTransactionForTotals(it)
        }
        val generalExpenseTotal = generalExpensesThisMonth
            .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
            .coerceAtLeast(0L)

        val topTwoGeneral = generalExpensesThisMonth
            .groupBy { it.categoryId ?: fallbackOtrosCategoryId }
            .mapNotNull { (catId, list) ->
                val cat = (if (catId != null) categoriesById[catId] else null) ?: return@mapNotNull null
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
            .groupBy { it.categoryId ?: fallbackOtrosCategoryId }
            .mapNotNull { (catId, list) ->
                val cat = (if (catId != null) categoriesById[catId] else null) ?: return@mapNotNull null
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

        // 4. Lista "Próximo": pendientes vencidos van arriba con etiqueta "Vencido" o "Ingreso atrasado"
        val todayStartMillis = AvailableToSpendCalculator.startOfDayMillis(nowMillis)
        val pendingSortedAsc = transactions
            .filter { it.status == TransactionStatus.PENDIENTE }
            .sortedWith(
                compareBy<TransactionEntity> { it.timestamp >= todayStartMillis }
                    .thenBy { it.timestamp }
            )

        var runningDebitProjected = availableResult.currentDebitBalance
        val upcomingPendingItems = pendingSortedAsc.map { tx ->
            val accItem = accountsById[tx.accountId]
            val acc = accItem?.account
            val isOverdue = tx.timestamp < todayStartMillis
            val statusLabel = AvailableToSpendCalculator.pendingStatusLabel(tx, nowMillis)
            val isIncome = tx.type == TransactionType.INGRESO ||
                tx.type == TransactionType.REEMBOLSO ||
                tx.type == TransactionType.CASHBACK

            if (acc != null && acc.type == AccountType.DEBITO) {
                // Los ingresos pendientes vencidos NO se suman al balance proyectado
                if (!(isOverdue && isIncome)) {
                    runningDebitProjected += AvailableToSpendCalculator.computeAccountDelta(acc, tx)
                }
            } else if (tx.type == TransactionType.PAGO_TARJETA) {
                runningDebitProjected -= tx.amount
            }
            UpcomingPendingItem(
                transaction = tx,
                accountName = acc?.name ?: "Cuenta",
                category = tx.categoryId?.let { categoriesById[it] },
                projectedBalanceAfter = runningDebitProjected,
                isOverdue = isOverdue,
                statusLabel = statusLabel
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

        // 7. Cálculos de Presupuesto para el mes seleccionado en nav (categories.monthlyLimit es la única fuente)
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

        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowYear = nowCal.get(Calendar.YEAR)
        val nowMonth = nowCal.get(Calendar.MONTH)
        val budgetDaysElapsed = when {
            nav.budgetYear == nowYear && nav.budgetMonth == nowMonth ->
                nowCal.get(Calendar.DAY_OF_MONTH).coerceIn(1, budgetMaxDay)
            nav.budgetYear < nowYear || (nav.budgetYear == nowYear && nav.budgetMonth < nowMonth) ->
                budgetMaxDay
            else -> 1
        }

        val categoryBudgetItems = categories
            .filter { it.type == CategoryType.GASTO }
            .map { cat ->
                val catTxs = txsInBudgetMonth.filter { it.categoryId == cat.id }
                val spent = catTxs.sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }.coerceAtLeast(0L)
                val limit = cat.monthlyLimit.coerceAtLeast(0L)
                val paceProjection = AnalyticsCalculator.computeBudgetProgressItem(
                    category = cat,
                    spentThisMonth = spent,
                    daysElapsedInMonth = budgetDaysElapsed,
                    daysInMonth = budgetMaxDay
                )
                CategoryBudgetSummaryItem(
                    category = cat,
                    groupName = cat.groupName.ifBlank { "Sin grupo" },
                    spentInMonth = spent,
                    transactionsCount = catTxs.size,
                    monthlyLimit = limit,
                    percentageUsed = paceProjection.percentage,
                    transactionsInMonth = catTxs,
                    projectedEndOfMonth = paceProjection.projectedEndOfMonth,
                    willExceedAtCurrentPace = paceProjection.willExceedAtCurrentPace
                )
            }

        val budgetItems = categoryBudgetItems
            .filter { it.monthlyLimit > 0L }
            .map { item ->
                BudgetProgressItem(
                    category = item.category,
                    spentThisMonth = item.spentInMonth,
                    percentage = item.percentageUsed,
                    projectedEndOfMonth = item.projectedEndOfMonth,
                    willExceedAtCurrentPace = item.willExceedAtCurrentPace
                )
            }

        // Proyección del mes actual para las alertas de Inicio
        val currentMonthMaxDay = nowCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val currentMonthDaysElapsed = nowCal.get(Calendar.DAY_OF_MONTH).coerceIn(1, currentMonthMaxDay)
        val currentMonthBudgetItems = categories
            .filter { it.type == CategoryType.GASTO && it.monthlyLimit > 0L }
            .map { cat ->
                val catSpent = generalExpensesThisMonth
                    .filter { it.categoryId == cat.id }
                    .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
                    .coerceAtLeast(0L)
                AnalyticsCalculator.computeBudgetProgressItem(
                    category = cat,
                    spentThisMonth = catSpent,
                    daysElapsedInMonth = currentMonthDaysElapsed,
                    daysInMonth = currentMonthMaxDay
                )
            }

        val smartAlerts = AnalyticsCalculator.computeHomeSmartAlerts(
            accounts = accounts,
            transactions = transactions,
            budgetItems = currentMonthBudgetItems,
            creditCardSummaries = creditCardSummaries,
            hideBalances = prefs.hideBalances,
            nowMillis = nowMillis
        )

        return MiPlataUiState(
            isLoading = false,
            prefs = prefs,
            accountsWithBalance = accountsWithBalance,
            archivedAccounts = archivedAccounts,
            accountIdsWithMovements = accountIdsWithMovements,
            categoryMovementCounts = categoryMovementCounts,
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
            recentFiveTransactions = transactions.take(5),
            budgetItems = budgetItems,
            categoryBudgetItems = categoryBudgetItems,
            savingGoals = goals,
            smartAlerts = smartAlerts,
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

    fun setDailyReminderConfig(
        enabled: Boolean,
        hourOfDay: Int,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val safeHour = hourOfDay.coerceIn(0, 23)
        repository.updatePreferences {
            it.copy(
                dailyReminderEnabled = enabled,
                dailyReminderHour = safeHour
            )
        }
        ReminderScheduler.scheduleOrCancelDailyReminder(
            context = appContext,
            enabled = enabled,
            hourOfDay = safeHour,
            nowMillis = nowMillis
        )
    }

    fun configureOrChangePin(
        enablePin: Boolean,
        currentPin: String = "",
        newPin: String = "",
        confirmNewPin: String = newPin,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        val error = repository.configureOrUpdatePin(
            enablePin = enablePin,
            currentPin = currentPin,
            newPin = newPin,
            confirmNewPin = confirmNewPin,
            nowMillis = nowMillis
        )
        if (error == null) {
            _isAppUnlocked.value = true
        }
        return error
    }

    fun verifyPinToUnlock(
        enteredPin: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PinUnlockResult {
        val result = repository.verifyPinAttempt(enteredPin, nowMillis)
        if (result is PinUnlockResult.Success) {
            _isAppUnlocked.value = true
        }
        return result
    }

    fun getRemainingPinLockoutSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        return repository.getRemainingLockoutSeconds(nowMillis)
    }

    fun unlockWithBiometricDeviceCredential() {
        repository.resetPinLockoutAndAttempts()
        _isAppUnlocked.value = true
    }

    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        backgroundedAtMillis = nowMillis
    }

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        val bgAt = backgroundedAtMillis
        backgroundedAtMillis = null
        if (bgAt != null &&
            (uiState.value.prefs.pinLockEnabled || repository.hasConfiguredPinHash()) &&
            (nowMillis - bgAt) > MiPlataRepository.BACKGROUND_RELOCK_TIMEOUT_MS
        ) {
            _isAppUnlocked.value = false
        }
    }

    fun completeOnboarding(
        selectedAccounts: List<OnboardingAccountChoice>,
        periodMode: PeriodMode,
        customCutoffDay: Int,
        baseMonthlyIncome: Long,
        firstObligationName: String,
        firstObligationAmount: Long,
        firstObligationDay: Int,
        firstObligationAccountName: String? = null
    ) {
        viewModelScope.launch {
            repository.completeOnboardingSetup(
                selectedAccounts = selectedAccounts,
                periodMode = periodMode,
                customCutoffDay = customCutoffDay,
                approximateIncome = baseMonthlyIncome,
                fixedPaymentName = firstObligationName,
                fixedPaymentAmount = firstObligationAmount,
                fixedPaymentDayOfMonth = firstObligationDay,
                fixedPaymentAccountName = firstObligationAccountName
            )
            _statusBannerMessage.value = "¡Bienvenido a MiPlata! Tus cuentas ya están listas."
        }
    }

    fun skipOnboardingAndLoadDemo() {
        viewModelScope.launch {
            repository.ensureDefaultsSeeded()
            repository.updatePreferences { it.copy(onboardingCompleted = true) }
            repository.loadThreeMonthDemoData()
            _statusBannerMessage.value = "Se cargaron datos de ejemplo."
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
        type: TransactionType? = null,
        amount: Long,
        accountId: Long? = null,
        toAccountId: Long? = null,
        categoryId: Long? = null,
        note: String? = null,
        receiptUri: String? = null,
        isRecurring: Boolean? = null,
        timestamp: Long? = null,
        status: TransactionStatus? = null,
        recurrence: RecurrenceFrequency? = null,
        customRecurrenceDays: Int? = null,
        totalInstallments: Int? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (amount <= 0L) return
        viewModelScope.launch {
            val savedId = repository.saveOrEditMovementFromForm(
                id = id,
                type = type,
                amount = amount,
                accountId = accountId,
                toAccountId = toAccountId,
                categoryId = categoryId,
                note = note,
                receiptUri = receiptUri,
                isRecurring = isRecurring,
                timestamp = timestamp,
                status = status,
                recurrence = recurrence,
                customRecurrenceDays = customRecurrenceDays,
                totalInstallments = totalInstallments,
                nowMillis = nowMillis
            )

            if (id == 0L && savedId > 0L && categoryId != null) {
                val state = uiState.value
                val catItem = state.categoryBudgetItems.find { it.category.id == categoryId }
                if (catItem != null && catItem.monthlyLimit > 0L) {
                    val newSpent = catItem.spentInMonth + amount
                    val newPct = ((newSpent.toDouble() / catItem.monthlyLimit.toDouble()) * 100.0).roundToInt()
                    if (newPct >= 80) {
                        NotificationHelper.sendBudgetAlertNotification(
                            context = appContext,
                            categoryName = catItem.category.name,
                            percentage = newPct,
                            spentCop = newSpent,
                            limitCop = catItem.monthlyLimit,
                            hideBalances = state.prefs.hideBalances
                        )
                    }
                }
            }
        }
    }

    fun markTransactionPaid(
        tx: TransactionEntity,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            repository.markTransactionAsPaid(tx, nowMillis = nowMillis)
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
            repository.restoreDeletedTransaction(deleted)
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
                    type = type,
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
                    includeInAvailableFilter = type == AccountType.DEBITO
                )
            )
            _statusBannerMessage.value = "Cuenta \"${name.trim()}\" creada."
        }
    }

    fun updateAccountFull(account: AccountEntity) {
        if (account.name.isBlank()) return
        viewModelScope.launch {
            repository.updateAccount(
                account.copy(
                    name = account.name.trim(),
                    billingCutoffDay = account.billingCutoffDay.coerceIn(1, 28),
                    paymentDueDay = account.paymentDueDay.coerceIn(1, 28),
                    lastFourDigits = account.lastFourDigits.takeLast(4),
                    personName = account.personName.trim()
                )
            )
            _statusBannerMessage.value = "Cuenta \"${account.name.trim()}\" actualizada."
        }
    }

    fun deleteOrArchiveAccount(account: AccountEntity) {
        viewModelScope.launch {
            when (repository.deleteOrArchiveAccount(account.id)) {
                AccountRemovalOutcome.DELETED -> {
                    _statusBannerMessage.value = "Cuenta \"${account.name}\" eliminada."
                }
                AccountRemovalOutcome.ARCHIVED -> {
                    _statusBannerMessage.value = "Cuenta \"${account.name}\" archivada. Sus movimientos siguen en el historial y puedes desarchivarla en Ajustes."
                }
                AccountRemovalOutcome.NOT_FOUND -> {}
            }
        }
    }

    fun unarchiveAccount(account: AccountEntity) {
        viewModelScope.launch {
            repository.setAccountArchived(account.id, archived = false)
            _statusBannerMessage.value = "Cuenta \"${account.name}\" desarchivada."
        }
    }

    fun registerLoanOrLentAbono(targetAccount: AccountEntity, amount: Long, debitAccountId: Long) {
        if (amount <= 0L) return
        viewModelScope.launch {
            val errorMsg = repository.registerLoanOrLentPayment(targetAccount, amount, debitAccountId)
            if (errorMsg != null) {
                _statusBannerMessage.value = errorMsg
            } else {
                _statusBannerMessage.value = "Abono de ${CopFormatter.format(amount)} registrado correctamente."
            }
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

    suspend fun saveOrUpdateCategoryValidated(
        id: Long = 0L,
        name: String,
        type: CategoryType,
        iconName: String,
        colorHex: Long,
        groupName: String,
        monthlyLimit: Long = 0L
    ): String? {
        val err = repository.saveOrUpdateCategory(
            id = id,
            name = name,
            type = type,
            iconName = iconName,
            colorHex = colorHex,
            groupName = groupName,
            monthlyLimit = monthlyLimit
        )
        if (err == null) {
            _statusBannerMessage.value = if (id > 0L) {
                "Categoría \"${name.trim()}\" actualizada."
            } else {
                "Categoría \"${name.trim()}\" creada."
            }
        }
        return err
    }

    suspend fun deleteCategoryWithReassignmentValidated(
        category: CategoryEntity,
        reassignToCategoryId: Long? = null
    ): String? {
        val err = repository.deleteCategoryWithReassignment(category, reassignToCategoryId)
        if (err == null) {
            _statusBannerMessage.value = "Categoría \"${category.name}\" eliminada."
        }
        return err
    }

    fun renameCategory(category: CategoryEntity, newName: String) {
        viewModelScope.launch {
            val ok = repository.renameCategory(category, newName)
            if (!ok) {
                _statusBannerMessage.value = "Ya existe una categoría con ese nombre en ${category.type.label}."
            }
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            val err = repository.deleteCategoryWithReassignment(category)
            if (err != null) {
                _statusBannerMessage.value = err
            }
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

    // --- FUNCIONES DE GEMINI Y PRIVACIDAD DE IA ---

    fun setAiFeaturesEnabled(enabled: Boolean) {
        repository.updatePreferences {
            it.copy(aiFeaturesEnabled = enabled)
        }
        if (!enabled) {
            _isChatLoading.value = false
            _isGeneratingReport.value = false
            _isParsingAiDraft.value = false
            pendingAiActionAfterConsent = null
            _showAiPrivacyConsentDialog.value = false
        }
        _statusBannerMessage.value = if (enabled) {
            "Funciones de IA con Gemini activadas."
        } else {
            "Funciones de IA con Gemini desactivadas."
        }
    }

    fun requestAiPrivacyConsentOrRun(
        onDisabledMessage: (String) -> Unit = { _statusBannerMessage.value = it },
        action: () -> Unit
    ) {
        val prefs = repository.userPreferences.value
        if (!prefs.aiFeaturesEnabled) {
            onDisabledMessage("Las funciones de inteligencia artificial están desactivadas en Ajustes.")
            return
        }
        if (!prefs.aiPrivacyConsentAccepted) {
            pendingAiActionAfterConsent = action
            _showAiPrivacyConsentDialog.value = true
            return
        }
        action()
    }

    fun acceptAiPrivacyConsent() {
        repository.updatePreferences {
            it.copy(aiPrivacyConsentAccepted = true)
        }
        _showAiPrivacyConsentDialog.value = false
        val action = pendingAiActionAfterConsent
        pendingAiActionAfterConsent = null
        action?.invoke()
    }

    fun declineAiPrivacyConsent() {
        _showAiPrivacyConsentDialog.value = false
        pendingAiActionAfterConsent = null
    }

    fun startNewChatConversation() {
        _isChatLoading.value = false
        _chatMessages.value = listOf(
            ChatMessageItem(
                id = 0L,
                isUser = false,
                text = "¡Qué más! Soy tu asistente financiero de MiPlata. Pregúntame por tu disponible proyectado, tus tarjetas de crédito, compras a cuotas o presupuestos en COP."
            )
        )
    }

    fun buildAggregatedFinancialSummary(nowMillis: Long = System.currentTimeMillis()): String {
        val s = uiState.value
        return GeminiFinanceService.buildPrivacySafeFinancialSummary(
            availableToSpend = s.availableToSpend,
            accountsWithBalance = s.accountsWithBalance,
            creditCardSummaries = s.creditCardSummaries,
            allTransactions = s.allTransactions,
            categories = s.categories,
            categoryBudgetItems = s.categoryBudgetItems,
            savingGoals = s.savingGoals,
            totalSavingsBalance = s.totalSavingsBalance,
            totalLoansDebt = s.totalLoansDebt,
            totalLentReceivable = s.totalLentReceivable,
            nowMillis = nowMillis
        )
    }

    fun sendChatMessageToGemini(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank() || _isChatLoading.value) return

        requestAiPrivacyConsentOrRun(
            onDisabledMessage = { msg -> _statusBannerMessage.value = msg }
        ) {
            val now = System.currentTimeMillis()
            val historySnapshot = _chatMessages.value
                .filter { it.id != 0L && !it.isStreaming && !it.isError && it.text.isNotBlank() }
                .takeLast(GeminiFinanceService.MAX_CHAT_HISTORY_MESSAGES)

            val userMsg = ChatMessageItem(id = now, isUser = true, text = trimmed)
            val aiMsgId = now + 100L
            val placeholderAiMsg = ChatMessageItem(id = aiMsgId, isUser = false, text = "", isStreaming = true)

            _chatMessages.value = _chatMessages.value + userMsg + placeholderAiMsg
            _isChatLoading.value = true

            viewModelScope.launch {
                val aggregatedContext = buildAggregatedFinancialSummary(now)
                val accumulated = StringBuilder()
                try {
                    geminiService.streamFinancialChatAnswer(
                        question = trimmed,
                        aggregatedContext = aggregatedContext,
                        previousMessages = historySnapshot
                    ).collect { chunk ->
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
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        val userErr = GeminiFinanceService.mapExceptionToUserException(e).message
                        _chatMessages.value = _chatMessages.value.map { msg ->
                            if (msg.id == aiMsgId) msg.copy(text = userErr, isStreaming = false, isError = true) else msg
                        }
                    } else {
                        throw e
                    }
                } catch (e: Exception) {
                    val userErr = GeminiFinanceService.mapExceptionToUserException(e).message
                    _chatMessages.value = _chatMessages.value.map { msg ->
                        if (msg.id == aiMsgId) msg.copy(text = userErr, isStreaming = false, isError = true) else msg
                    }
                } finally {
                    _isChatLoading.value = false
                }
            }
        }
    }

    fun requestGeminiReportAndTip(nowMillis: Long = System.currentTimeMillis()) {
        if (_isGeneratingReport.value) return
        requestAiPrivacyConsentOrRun(
            onDisabledMessage = { msg -> _aiReportError.value = msg }
        ) {
            _isGeneratingReport.value = true
            _aiReportError.value = null

            viewModelScope.launch {
                val contextSummary = buildAggregatedFinancialSummary(nowMillis)
                val result = geminiService.generateStructuredReport(
                    aggregatedContext = contextSummary,
                    nowMillis = nowMillis
                )
                result.onSuccess { report ->
                    repository.savePersistedAiReport(report)
                    _aiReport.value = report
                    if (report.shortDailyTip.isNotBlank()) {
                        _dailyGeminiTip.value = report.shortDailyTip
                    }
                }.onFailure { err ->
                    _aiReportError.value = GeminiFinanceService.mapExceptionToUserException(err).message
                }
                _isGeneratingReport.value = false
            }
        }
    }

    fun parseVoiceOrTextToDraft(
        rawText: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (rawText.isBlank()) return
        requestAiPrivacyConsentOrRun(
            onDisabledMessage = { msg -> _parseDraftError.value = msg }
        ) {
            _isParsingAiDraft.value = true
            _parseDraftError.value = null
            _parsedDraft.value = null

            viewModelScope.launch {
                val categories = uiState.value.categories
                val accounts = uiState.value.accountsWithBalance.map { it.account }
                val res = geminiService.parseNaturalLanguageMovement(
                    userText = rawText,
                    availableCategories = categories,
                    availableAccounts = accounts,
                    nowMillis = nowMillis
                )
                res.onSuccess { draft -> _parsedDraft.value = draft }
                    .onFailure { err ->
                        _parseDraftError.value = GeminiFinanceService.mapExceptionToUserException(err).message
                    }
                _isParsingAiDraft.value = false
            }
        }
    }

    fun parseReceiptBitmapToDraft(
        bitmap: Bitmap,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        requestAiPrivacyConsentOrRun(
            onDisabledMessage = { msg -> _parseDraftError.value = msg }
        ) {
            _isParsingAiDraft.value = true
            _parseDraftError.value = null
            _parsedDraft.value = null

            viewModelScope.launch {
                val categories = uiState.value.categories.filter { it.type == CategoryType.GASTO }
                val accounts = uiState.value.accountsWithBalance.map { it.account }
                val res = geminiService.parseReceiptPhoto(
                    bitmap = bitmap,
                    availableCategories = categories,
                    availableAccounts = accounts,
                    nowMillis = nowMillis
                )
                res.onSuccess { draft -> _parsedDraft.value = draft }
                    .onFailure { err ->
                        _parseDraftError.value = GeminiFinanceService.mapExceptionToUserException(err).message
                    }
                _isParsingAiDraft.value = false
            }
        }
    }

    fun autoCategorizeNote(note: String, onCategoryResolved: (CategoryEntity) -> Unit) {
        if (note.isBlank()) return
        val allCats = uiState.value.categories
        val previousTxs = uiState.value.allTransactions

        // 1. Buscar primero en movimientos anteriores sin requerir red ni consentimiento de IA
        val localMatch = GeminiFinanceService.findCategoryFromPreviousTransactions(
            note = note,
            availableCategories = allCats,
            previousTransactions = previousTxs
        )
        if (localMatch != null) {
            onCategoryResolved(localMatch)
            return
        }

        // 2. Solo si no hay coincidencia local, verificar si la IA está activa y con consentimiento
        if (!repository.userPreferences.value.aiFeaturesEnabled) {
            _statusBannerMessage.value = "Las funciones de inteligencia artificial están desactivadas en Ajustes."
            return
        }

        requestAiPrivacyConsentOrRun {
            viewModelScope.launch {
                val res = geminiService.suggestCategoryFromNote(
                    note = note,
                    availableCategories = allCats,
                    previousTransactions = previousTxs
                )
                res.onSuccess { matchedCat ->
                    if (matchedCat != null) {
                        onCategoryResolved(matchedCat)
                    }
                }.onFailure { err ->
                    _statusBannerMessage.value = GeminiFinanceService.mapExceptionToUserException(err).message
                }
            }
        }
    }

    fun clearParsedDraft() {
        _parsedDraft.value = null
        _parseDraftError.value = null
    }
}
