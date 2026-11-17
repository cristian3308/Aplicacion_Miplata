package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.domain.CopFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Preferencias locales del usuario (incluyendo ajuste de restar pago de tarjeta en Disponible,
 * tema oscuro azul marino por defecto y marca de tiempo de la última copia de seguridad).
 */
data class UserPreferencesState(
    val onboardingCompleted: Boolean = false,
    val periodMode: PeriodMode = PeriodMode.MENSUAL,
    val customCutoffDay: Int = 1,
    val baseMonthlyIncome: Long = 0L,
    val deductCreditCardDueInAvailable: Boolean = true, // Activado por defecto según especificación
    val hideBalances: Boolean = false,
    val pinLockEnabled: Boolean = false,
    val pinCode: String = "",
    val dailyReminderEnabled: Boolean = true,
    val dailyReminderHour: Int = 20,
    val darkModeOverride: Boolean? = true, // Tema oscuro azul marino por defecto (con opción de cambiar a claro)
    val lastUsedAccountId: Long = 3L,
    val isDemoLoaded: Boolean = false,
    val lastBackupTimestamp: Long = 0L
)

/**
 * Repositorio central de MiPlata (Room v2 + Recurrencia automática + Compras a cuotas + Backup CSV/JSON).
 */
class MiPlataRepository(
    context: Context,
    private val dao: MiPlataDao = MiPlataDatabase.getInstance(context).dao()
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE)

    private val _userPrefs = MutableStateFlow(loadPreferences())
    val userPreferences: StateFlow<UserPreferencesState> = _userPrefs.asStateFlow()

    val accountsFlow: Flow<List<AccountEntity>> = dao.observeAccounts()
    val categoriesFlow: Flow<List<CategoryEntity>> = dao.observeCategories()
    val transactionsFlow: Flow<List<TransactionEntity>> = dao.observeTransactions()
    val budgetsFlow: Flow<List<BudgetEntity>> = dao.observeBudgets()
    val savingGoalsFlow: Flow<List<SavingGoalEntity>> = dao.observeSavingGoals()
    val obligationsFlow: Flow<List<RecurringObligationEntity>> = dao.observeObligations()
    val debtsFlow: Flow<List<DebtEntity>> = dao.observeDebts()

    private fun loadPreferences(): UserPreferencesState {
        val modeStr = prefs.getString("period_mode", PeriodMode.MENSUAL.name) ?: PeriodMode.MENSUAL.name
        val mode = runCatching { PeriodMode.valueOf(modeStr) }.getOrDefault(PeriodMode.MENSUAL)
        val darkInt = prefs.getInt("dark_mode_override", 1) // 1 = oscuro azul marino por defecto
        val darkOverride = when (darkInt) {
            1 -> true
            0 -> false
            else -> null
        }
        return UserPreferencesState(
            onboardingCompleted = prefs.getBoolean("onboarding_completed", false),
            periodMode = mode,
            customCutoffDay = prefs.getInt("custom_cutoff_day", 1).coerceIn(1, 28),
            baseMonthlyIncome = prefs.getLong("base_monthly_income", 0L),
            deductCreditCardDueInAvailable = prefs.getBoolean("deduct_credit_card_due", true),
            hideBalances = prefs.getBoolean("hide_balances", false),
            pinLockEnabled = prefs.getBoolean("pin_lock_enabled", false),
            pinCode = prefs.getString("pin_code", "") ?: "",
            dailyReminderEnabled = prefs.getBoolean("daily_reminder_enabled", true),
            dailyReminderHour = prefs.getInt("daily_reminder_hour", 20),
            darkModeOverride = darkOverride,
            lastUsedAccountId = prefs.getLong("last_used_account_id", 3L),
            isDemoLoaded = prefs.getBoolean("is_demo_loaded", false),
            lastBackupTimestamp = prefs.getLong("last_backup_timestamp", 0L)
        )
    }

    fun updatePreferences(transform: (UserPreferencesState) -> UserPreferencesState) {
        val updated = transform(_userPrefs.value)
        prefs.edit()
            .putBoolean("onboarding_completed", updated.onboardingCompleted)
            .putString("period_mode", updated.periodMode.name)
            .putInt("custom_cutoff_day", updated.customCutoffDay)
            .putLong("base_monthly_income", updated.baseMonthlyIncome)
            .putBoolean("deduct_credit_card_due", updated.deductCreditCardDueInAvailable)
            .putBoolean("hide_balances", updated.hideBalances)
            .putBoolean("pin_lock_enabled", updated.pinLockEnabled)
            .putString("pin_code", updated.pinCode)
            .putBoolean("daily_reminder_enabled", updated.dailyReminderEnabled)
            .putInt("daily_reminder_hour", updated.dailyReminderHour)
            .putInt(
                "dark_mode_override",
                when (updated.darkModeOverride) {
                    true -> 1
                    false -> 0
                    null -> -1
                }
            )
            .putLong("last_used_account_id", updated.lastUsedAccountId)
            .putBoolean("is_demo_loaded", updated.isDemoLoaded)
            .putLong("last_backup_timestamp", updated.lastBackupTimestamp)
            .apply()
        _userPrefs.value = updated
    }

    /**
     * Inicializa las cuentas y categorías por defecto sin borrar datos existentes.
     */
    suspend fun ensureDefaultsSeeded() = withContext(Dispatchers.IO) {
        if (dao.getAccountsOnce().isEmpty()) {
            dao.insertAccounts(MiPlataDatabase.defaultAccounts())
        }
        if (dao.getCategoriesOnce().isEmpty()) {
            dao.insertCategories(MiPlataDatabase.defaultCategories())
        }
    }

    // --- OPERACIONES DE CUENTAS Y CATEGORÍAS ---
    suspend fun saveAccount(account: AccountEntity): Long = dao.insertAccount(account)
    suspend fun updateAccount(account: AccountEntity) = dao.updateAccount(account)
    suspend fun deleteAccount(account: AccountEntity) = dao.deleteAccount(account)

    suspend fun updateCategoryLimitAndGroup(
        category: CategoryEntity,
        newLimit: Long,
        newGroupName: String
    ) = withContext(Dispatchers.IO) {
        val updated = category.copy(
            monthlyLimit = newLimit.coerceAtLeast(0L),
            groupName = newGroupName.ifBlank { category.groupName }
        )
        dao.updateCategory(updated)
        if (newLimit > 0L) {
            saveBudget(BudgetEntity(categoryId = category.id, monthlyLimit = newLimit))
        } else {
            dao.getBudgetForCategory(category.id)?.let { dao.deleteBudget(it) }
        }
    }

    /**
     * Calcula la fecha de la siguiente ocurrencia de una transacción recurrente.
     */
    private fun computeNextOccurrenceTimestamp(
        currentTimestamp: Long,
        recurrence: RecurrenceFrequency,
        customDays: Int
    ): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = currentTimestamp }
        when (recurrence) {
            RecurrenceFrequency.SEMANAL -> cal.add(Calendar.DAY_OF_YEAR, 7)
            RecurrenceFrequency.QUINCENAL -> cal.add(Calendar.DAY_OF_YEAR, 15)
            RecurrenceFrequency.MENSUAL -> cal.add(Calendar.MONTH, 1)
            RecurrenceFrequency.ANUAL -> cal.add(Calendar.YEAR, 1)
            RecurrenceFrequency.PERSONALIZADA -> cal.add(Calendar.DAY_OF_YEAR, customDays.coerceAtLeast(1))
            RecurrenceFrequency.NINGUNA -> {}
        }
        return cal.timeInMillis
    }

    /**
     * Guarda una transacción.
     * - Si es COMPRA_CUOTAS con totalInstallments > 1 y es nueva (id == 0L), genera la cuota 1
     *   como PAGADA y las cuotas 2..N como PENDIENTES mes a mes.
     * - Si es recurrente (recurrence != NINGUNA) y está PAGADA y aún no ha generado su siguiente
     *   ocurrencia, genera automáticamente la próxima ocurrencia como PENDIENTE.
     */
    suspend fun saveTransaction(tx: TransactionEntity): Long = withContext(Dispatchers.IO) {
        if (tx.id == 0L && tx.type == TransactionType.COMPRA_CUOTAS && tx.totalInstallments > 1) {
            val count = tx.totalInstallments.coerceIn(2, 48)
            val perInstallment = (tx.amount / count).coerceAtLeast(1L)
            val remainder = tx.amount - (perInstallment * count)
            var firstId = 0L

            for (idx in 1..count) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = tx.timestamp
                    if (idx > 1) add(Calendar.MONTH, idx - 1)
                }
                val instAmount = if (idx == 1) perInstallment + remainder else perInstallment
                val instStatus = if (idx == 1) TransactionStatus.PAGADO else TransactionStatus.PENDIENTE
                val baseNote = tx.note.ifBlank { "Compra a cuotas" }
                val instTx = tx.copy(
                    id = 0L,
                    amount = instAmount,
                    timestamp = cal.timeInMillis,
                    status = instStatus,
                    installmentNumber = idx,
                    totalInstallments = count,
                    note = "$baseNote (Cuota $idx/$count)"
                )
                val inserted = dao.insertTransaction(instTx)
                if (idx == 1) firstId = inserted
            }
            updatePreferences { it.copy(lastUsedAccountId = tx.accountId) }
            return@withContext firstId
        }

        val shouldGenerateNext = tx.recurrence != RecurrenceFrequency.NINGUNA &&
            tx.status == TransactionStatus.PAGADO &&
            !tx.hasGeneratedNextOccurrence

        val savedTx = if (shouldGenerateNext) {
            tx.copy(isRecurring = true, hasGeneratedNextOccurrence = true)
        } else {
            tx.copy(isRecurring = tx.recurrence != RecurrenceFrequency.NINGUNA || tx.isRecurring)
        }

        val id = dao.insertTransaction(savedTx)

        if (shouldGenerateNext) {
            val nextTime = computeNextOccurrenceTimestamp(
                currentTimestamp = tx.timestamp,
                recurrence = tx.recurrence,
                customDays = tx.customRecurrenceDays
            )
            val nextPending = savedTx.copy(
                id = 0L,
                timestamp = nextTime,
                status = TransactionStatus.PENDIENTE,
                parentRecurringId = id,
                hasGeneratedNextOccurrence = false
            )
            dao.insertTransaction(nextPending)
        }

        updatePreferences { it.copy(lastUsedAccountId = tx.accountId) }
        id
    }

    /**
     * Marca una transacción PENDIENTE como PAGADA y, si es recurrente, genera su siguiente ocurrencia pendiente.
     */
    suspend fun markTransactionAsPaid(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        val paidTx = tx.copy(status = TransactionStatus.PAGADO, timestamp = System.currentTimeMillis())
        saveTransaction(paidTx)
    }

    /**
     * Omite una ocurrencia pendiente: si es recurrente, genera la del siguiente ciclo y elimina la actual.
     */
    suspend fun skipPendingOccurrence(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        if (tx.recurrence != RecurrenceFrequency.NINGUNA && !tx.hasGeneratedNextOccurrence) {
            val nextTime = computeNextOccurrenceTimestamp(
                currentTimestamp = tx.timestamp,
                recurrence = tx.recurrence,
                customDays = tx.customRecurrenceDays
            )
            dao.insertTransaction(
                tx.copy(
                    id = 0L,
                    timestamp = nextTime,
                    status = TransactionStatus.PENDIENTE,
                    parentRecurringId = tx.id,
                    hasGeneratedNextOccurrence = false
                )
            )
        }
        dao.deleteTransaction(tx)
    }

    suspend fun deleteTransaction(tx: TransactionEntity) = dao.deleteTransaction(tx)

    /**
     * Registra un abono a una cuenta de tipo PRESTADO (lo que me deben) o PRESTAMO (lo que debo)
     * y genera el movimiento en la cuenta de débito correspondiente.
     */
    suspend fun registerLoanOrLentPayment(
        targetAccount: AccountEntity,
        amount: Long,
        debitAccountId: Long
    ) = withContext(Dispatchers.IO) {
        val updatedPaid = targetAccount.receivedOrPaidAmount + amount
        dao.updateAccount(targetAccount.copy(receivedOrPaidAmount = updatedPaid))

        if (targetAccount.type.normalized() == AccountType.PRESTADO) {
            // Me pagaron algo que me debían -> entra dinero a mi cuenta de débito
            dao.insertTransaction(
                TransactionEntity(
                    type = TransactionType.TRANSFERENCIA,
                    amount = amount,
                    accountId = targetAccount.id,
                    toAccountId = debitAccountId,
                    note = "Abono recibido de ${targetAccount.personName.ifBlank { targetAccount.name }}",
                    status = TransactionStatus.PAGADO
                )
            )
        } else {
            // Pagué cuota de mi préstamo -> sale dinero de mi cuenta de débito hacia el préstamo
            dao.insertTransaction(
                TransactionEntity(
                    type = TransactionType.TRANSFERENCIA,
                    amount = amount,
                    accountId = debitAccountId,
                    toAccountId = targetAccount.id,
                    note = "Abono a préstamo: ${targetAccount.name}",
                    status = TransactionStatus.PAGADO
                )
            )
        }
    }

    // --- PRESUPUESTOS, METAS, OBLIGACIONES Y DEUDAS ---
    suspend fun saveBudget(budget: BudgetEntity) {
        val existing = dao.getBudgetForCategory(budget.categoryId)
        if (existing != null) {
            dao.insertBudget(budget.copy(id = existing.id))
        } else {
            dao.insertBudget(budget)
        }
    }

    suspend fun deleteBudget(budget: BudgetEntity) = dao.deleteBudget(budget)

    suspend fun saveSavingGoal(goal: SavingGoalEntity): Long = dao.insertSavingGoal(goal)
    suspend fun deleteSavingGoal(goal: SavingGoalEntity) = dao.deleteSavingGoal(goal)

    suspend fun contributeToSavingGoal(
        goal: SavingGoalEntity,
        amount: Long,
        accountId: Long
    ) = withContext(Dispatchers.IO) {
        val updatedGoal = goal.copy(savedAmount = goal.savedAmount + amount)
        dao.updateSavingGoal(updatedGoal)
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = amount,
                accountId = accountId,
                categoryId = 12L,
                note = "Aporte a meta: ${goal.name}",
                linkedGoalId = goal.id,
                isDemo = goal.isDemo,
                status = TransactionStatus.PAGADO
            )
        )
        updatePreferences { it.copy(lastUsedAccountId = accountId) }
    }

    suspend fun saveObligation(obligation: RecurringObligationEntity): Long =
        dao.insertObligation(obligation)

    suspend fun deleteObligation(obligation: RecurringObligationEntity) =
        dao.deleteObligation(obligation)

    suspend fun toggleObligationPaidThisMonth(
        obligation: RecurringObligationEntity,
        currentYearMonth: String = CopFormatter.currentYearMonthKey()
    ) = withContext(Dispatchers.IO) {
        val alreadyPaid = obligation.lastPaidYearMonth == currentYearMonth
        if (alreadyPaid) {
            dao.updateObligation(obligation.copy(lastPaidYearMonth = ""))
        } else {
            dao.updateObligation(obligation.copy(lastPaidYearMonth = currentYearMonth))
            dao.insertTransaction(
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = obligation.amount,
                    accountId = obligation.accountId,
                    categoryId = obligation.categoryId,
                    note = "Pago obligación fija: ${obligation.name}",
                    isRecurring = true,
                    recurrence = RecurrenceFrequency.MENSUAL,
                    linkedObligationId = obligation.id,
                    isDemo = obligation.isDemo,
                    status = TransactionStatus.PAGADO
                )
            )
        }
    }

    suspend fun saveDebt(debt: DebtEntity): Long = dao.insertDebt(debt)
    suspend fun deleteDebt(debt: DebtEntity) = dao.deleteDebt(debt)

    suspend fun payDebtInstallment(
        debt: DebtEntity,
        amount: Long,
        accountId: Long
    ) = withContext(Dispatchers.IO) {
        val newRemaining = (debt.remainingBalance - amount).coerceAtLeast(0L)
        dao.updateDebt(debt.copy(remainingBalance = newRemaining))
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = amount,
                accountId = accountId,
                categoryId = 10L,
                note = "Abono deuda: ${debt.creditorName}",
                linkedDebtId = debt.id,
                isDemo = debt.isDemo,
                status = TransactionStatus.PAGADO
            )
        )
        updatePreferences { it.copy(lastUsedAccountId = accountId) }
    }

    /**
     * Carga 3 meses de datos de ejemplo completos con los nuevos tipos de cuenta (Débito, Ahorros,
     * Tarjeta de crédito, Préstamo y Prestado) y los 8 tipos de transacción (incluyendo Pagos pendientes
     * en "Próximo", Compras a cuotas, Pagos de tarjeta, Reembolso y Cashback).
     */
    suspend fun loadThreeMonthDemoData(nowMillis: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            ensureDefaultsSeeded()
            clearDemoDataOnly()

            // Configurar cuentas completas de ejemplo
            val demoAccounts = listOf(
                AccountEntity(
                    id = 1L,
                    name = "Efectivo",
                    type = AccountType.DEBITO,
                    initialBalance = 220_000L,
                    colorHex = 0xFF10B981,
                    iconName = "payments",
                    isDefault = true
                ),
                AccountEntity(
                    id = 2L,
                    name = "Nu Débito",
                    type = AccountType.DEBITO,
                    initialBalance = 1_850_000L,
                    colorHex = 0xFF8B5CF6,
                    iconName = "account_balance",
                    isDefault = true
                ),
                AccountEntity(
                    id = 3L,
                    name = "Nequi",
                    type = AccountType.DEBITO,
                    initialBalance = 540_000L,
                    colorHex = 0xFFEC4899,
                    iconName = "smartphone",
                    isDefault = true
                ),
                AccountEntity(
                    id = 4L,
                    name = "Cajita Ahorros Nu",
                    type = AccountType.AHORROS,
                    initialBalance = 3_200_000L,
                    colorHex = 0xFF38BDF8,
                    iconName = "savings",
                    isDefault = false,
                    includeInAvailableFilter = false
                ),
                AccountEntity(
                    id = 5L,
                    name = "Tarjeta Nu",
                    type = AccountType.TARJETA_CREDITO,
                    initialBalance = 0L,
                    colorHex = 0xFFA855F7,
                    iconName = "credit_card",
                    isDefault = false,
                    creditLimit = 4_000_000L,
                    billingCutoffDay = 20,
                    paymentDueDay = 5,
                    lastFourDigits = "4821",
                    includeInAvailableFilter = false
                ),
                AccountEntity(
                    id = 6L,
                    name = "Crédito Moto Sufí",
                    type = AccountType.PRESTAMO,
                    initialBalance = 0L,
                    colorHex = 0xFFF59E0B,
                    iconName = "credit_card",
                    originalAmount = 4_800_000L,
                    receivedOrPaidAmount = 2_100_000L,
                    monthlyInstallment = 320_000L,
                    includeInAvailableFilter = false
                ),
                AccountEntity(
                    id = 7L,
                    name = "Carlos Ramírez",
                    type = AccountType.PRESTADO,
                    initialBalance = 0L,
                    colorHex = 0xFF14B8A6,
                    iconName = "payments",
                    personName = "Carlos Ramírez (Primo)",
                    originalAmount = 600_000L,
                    receivedOrPaidAmount = 250_000L,
                    includeInAvailableFilter = false
                )
            )
            dao.insertAccounts(demoAccounts)

            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            fun dayOffset(days: Int, hour: Int = 14): Long {
                return (cal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, days)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, 30)
                }.timeInMillis
            }

            val demoTransactions = listOf(
                // Transacciones PAGADAS del mes actual
                TransactionEntity(
                    type = TransactionType.INGRESO,
                    amount = 3_400_000L,
                    timestamp = dayOffset(-8, 9),
                    accountId = 2L,
                    categoryId = 13L,
                    note = "Pago nómina 1ª quincena",
                    status = TransactionStatus.PAGADO,
                    recurrence = RecurrenceFrequency.QUINCENAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = 1_150_000L,
                    timestamp = dayOffset(-7, 10),
                    accountId = 2L,
                    categoryId = 3L,
                    note = "Arriendo apartamento",
                    status = TransactionStatus.PAGADO,
                    recurrence = RecurrenceFrequency.MENSUAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = 420_000L,
                    timestamp = dayOffset(-5, 11),
                    accountId = 2L,
                    categoryId = 5L,
                    note = "Mercado Éxito y D1",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                // Compras con Tarjeta de Crédito (id=5L)
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = 185_000L,
                    timestamp = dayOffset(-4, 20),
                    accountId = 5L,
                    categoryId = 1L,
                    note = "Cena restaurante Crepes & Waffles",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.COMPRA_CUOTAS,
                    amount = 220_000L,
                    timestamp = dayOffset(-3, 16),
                    accountId = 5L,
                    categoryId = 8L,
                    note = "Tenis deportivos (Cuota 1/3)",
                    status = TransactionStatus.PAGADO,
                    installmentNumber = 1,
                    totalInstallments = 3,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.CASHBACK,
                    amount = 15_000L,
                    timestamp = dayOffset(-3, 17),
                    accountId = 5L,
                    categoryId = 16L,
                    note = "Cashback Tarjeta Nu",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.REEMBOLSO,
                    amount = 25_000L,
                    timestamp = dayOffset(-2, 12),
                    accountId = 3L,
                    categoryId = 1L,
                    note = "Reembolso pedido incompleto Rappi",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.PAGO_TARJETA,
                    amount = 150_000L,
                    timestamp = dayOffset(-1, 15),
                    accountId = 2L,
                    toAccountId = 5L,
                    note = "Abono anticipado a Tarjeta Nu",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = 48_000L,
                    timestamp = dayOffset(0, 13),
                    accountId = 3L,
                    categoryId = 2L,
                    note = "DiDi y recarga transporte",
                    status = TransactionStatus.PAGADO,
                    isDemo = true
                ),
                // Transacciones PENDIENTES (aparecen en la lista "Próximo" de Inicio y en el Calendario)
                TransactionEntity(
                    type = TransactionType.PAGO_PROGRAMADO,
                    amount = 135_000L,
                    timestamp = dayOffset(2, 9),
                    accountId = 3L,
                    categoryId = 4L,
                    note = "Internet Fibra + Plan móvil",
                    status = TransactionStatus.PENDIENTE,
                    recurrence = RecurrenceFrequency.MENSUAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.PAGO_PROGRAMADO,
                    amount = 44_900L,
                    timestamp = dayOffset(4, 10),
                    accountId = 5L,
                    categoryId = 7L,
                    note = "Suscripción Streaming + Música",
                    status = TransactionStatus.PENDIENTE,
                    recurrence = RecurrenceFrequency.MENSUAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.INGRESO,
                    amount = 1_450_000L,
                    timestamp = dayOffset(6, 11),
                    accountId = 2L,
                    categoryId = 15L,
                    note = "Pago cliente proyecto / 2ª quincena",
                    status = TransactionStatus.PENDIENTE,
                    recurrence = RecurrenceFrequency.QUINCENAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.PAGO_PROGRAMADO,
                    amount = 95_000L,
                    timestamp = dayOffset(9, 8),
                    accountId = 1L,
                    categoryId = 9L,
                    note = "Tanqueada gasolina quincenal",
                    status = TransactionStatus.PENDIENTE,
                    recurrence = RecurrenceFrequency.QUINCENAL,
                    isRecurring = true,
                    isDemo = true
                ),
                TransactionEntity(
                    type = TransactionType.COMPRA_CUOTAS,
                    amount = 220_000L,
                    timestamp = dayOffset(27, 16),
                    accountId = 5L,
                    categoryId = 8L,
                    note = "Tenis deportivos (Cuota 2/3)",
                    status = TransactionStatus.PENDIENTE,
                    installmentNumber = 2,
                    totalInstallments = 3,
                    isDemo = true
                )
            )
            dao.insertTransactions(demoTransactions)

            // Presupuestos sincronizados con categorías
            val demoBudgets = listOf(
                BudgetEntity(categoryId = 1L, monthlyLimit = 400_000L, isDemo = true),
                BudgetEntity(categoryId = 3L, monthlyLimit = 1_150_000L, isDemo = true),
                BudgetEntity(categoryId = 4L, monthlyLimit = 220_000L, isDemo = true),
                BudgetEntity(categoryId = 5L, monthlyLimit = 500_000L, isDemo = true),
                BudgetEntity(categoryId = 7L, monthlyLimit = 180_000L, isDemo = true),
                BudgetEntity(categoryId = 2L, monthlyLimit = 180_000L, isDemo = true)
            )
            dao.insertBudgets(demoBudgets)

            updatePreferences {
                it.copy(
                    onboardingCompleted = true,
                    baseMonthlyIncome = 4_850_000L,
                    isDemoLoaded = true,
                    lastBackupTimestamp = nowMillis
                )
            }
        }

    suspend fun clearDemoDataOnly() = withContext(Dispatchers.IO) {
        dao.deleteDemoTransactions()
        dao.deleteDemoBudgets()
        dao.deleteDemoGoals()
        dao.deleteDemoObligations()
        dao.deleteDemoDebts()

        val accounts = dao.getAccountsOnce()
        accounts.forEach { acc ->
            dao.updateAccount(acc.copy(initialBalance = 0L, receivedOrPaidAmount = 0L))
        }
        updatePreferences { it.copy(isDemoLoaded = false, baseMonthlyIncome = 0L) }
    }

    /**
     * Exporta todos los movimientos a formato CSV compatible con Excel.
     */
    suspend fun exportTransactionsToCsv(): String = withContext(Dispatchers.IO) {
        val accounts = dao.getAccountsOnce().associateBy { it.id }
        val categories = dao.getCategoriesOnce().associateBy { it.id }
        val transactions = dao.getTransactionsOnce()

        val sb = StringBuilder()
        sb.appendLine("ID,Fecha,Hora,Tipo,Estado,Monto_COP,Cuenta,Cuenta_Destino,Categoria,Nota,Recurrencia,Cuota")
        for (tx in transactions) {
            val dateStr = CopFormatter.formatShortDate(tx.timestamp)
            val timeStr = CopFormatter.formatTime(tx.timestamp)
            val accName = accounts[tx.accountId]?.name ?: "Cuenta"
            val toAccName = tx.toAccountId?.let { accounts[it]?.name } ?: ""
            val catName = tx.categoryId?.let { categories[it]?.name } ?: tx.type.label
            val safeNote = tx.note.replace("\"", "\"\"")
            val cuotaStr = if (tx.totalInstallments > 1) "${tx.installmentNumber}/${tx.totalInstallments}" else "1/1"
            sb.appendLine("${tx.id},$dateStr,$timeStr,${tx.type.label},${tx.status.label},${tx.amount},\"$accName\",\"$toAccName\",\"$catName\",\"$safeNote\",${tx.recurrence.label},$cuotaStr")
        }
        updatePreferences { it.copy(lastBackupTimestamp = System.currentTimeMillis()) }
        sb.toString()
    }

    /**
     * Crea una copia de seguridad completa en formato JSON (v2).
     */
    suspend fun createBackupJson(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val root = JSONObject()
        root.put("app", "MiPlata")
        root.put("version", 2)
        root.put("exportedAt", now)

        val accountsArr = JSONArray()
        dao.getAccountsOnce().forEach { a ->
            accountsArr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("type", a.type.normalized().name)
                    put("initialBalance", a.initialBalance)
                    put("colorHex", a.colorHex)
                    put("iconName", a.iconName)
                    put("isDefault", a.isDefault)
                    put("creditLimit", a.creditLimit)
                    put("billingCutoffDay", a.billingCutoffDay)
                    put("paymentDueDay", a.paymentDueDay)
                    put("lastFourDigits", a.lastFourDigits)
                    put("personName", a.personName)
                    put("originalAmount", a.originalAmount)
                    put("receivedOrPaidAmount", a.receivedOrPaidAmount)
                    put("monthlyInstallment", a.monthlyInstallment)
                }
            )
        }
        root.put("accounts", accountsArr)

        val txArr = JSONArray()
        dao.getTransactionsOnce().forEach { t ->
            txArr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("type", t.type.name)
                    put("status", t.status.name)
                    put("recurrence", t.recurrence.name)
                    put("amount", t.amount)
                    put("timestamp", t.timestamp)
                    put("accountId", t.accountId)
                    if (t.toAccountId != null) put("toAccountId", t.toAccountId)
                    if (t.categoryId != null) put("categoryId", t.categoryId)
                    put("note", t.note)
                    put("installmentNumber", t.installmentNumber)
                    put("totalInstallments", t.totalInstallments)
                }
            )
        }
        root.put("transactions", txArr)

        updatePreferences { it.copy(lastBackupTimestamp = now) }
        root.toString(2)
    }

    /**
     * Restaura los datos desde un archivo JSON de copia de seguridad.
     */
    suspend fun restoreFromBackupJson(jsonContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonContent)
            ensureDefaultsSeeded()
            dao.clearAllTransactions()

            root.optJSONArray("accounts")?.let { arr ->
                val list = mutableListOf<AccountEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += AccountEntity(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        type = runCatching { AccountType.valueOf(o.getString("type")).normalized() }.getOrDefault(AccountType.DEBITO),
                        initialBalance = o.optLong("initialBalance", 0L),
                        colorHex = o.optLong("colorHex", 0xFF10B981),
                        iconName = o.optString("iconName", "payments"),
                        isDefault = o.optBoolean("isDefault", false),
                        creditLimit = o.optLong("creditLimit", 0L),
                        billingCutoffDay = o.optInt("billingCutoffDay", 15),
                        paymentDueDay = o.optInt("paymentDueDay", 5),
                        lastFourDigits = o.optString("lastFourDigits", ""),
                        personName = o.optString("personName", ""),
                        originalAmount = o.optLong("originalAmount", 0L),
                        receivedOrPaidAmount = o.optLong("receivedOrPaidAmount", 0L),
                        monthlyInstallment = o.optLong("monthlyInstallment", 0L)
                    )
                }
                if (list.isNotEmpty()) dao.insertAccounts(list)
            }

            root.optJSONArray("transactions")?.let { arr ->
                val list = mutableListOf<TransactionEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += TransactionEntity(
                        id = o.optLong("id", 0L),
                        type = runCatching { TransactionType.valueOf(o.getString("type")) }.getOrDefault(TransactionType.GASTO),
                        status = runCatching { TransactionStatus.valueOf(o.optString("status", "PAGADO")) }.getOrDefault(TransactionStatus.PAGADO),
                        recurrence = runCatching { RecurrenceFrequency.valueOf(o.optString("recurrence", "NINGUNA")) }.getOrDefault(RecurrenceFrequency.NINGUNA),
                        amount = o.getLong("amount"),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        accountId = o.optLong("accountId", 1L),
                        toAccountId = if (o.has("toAccountId")) o.getLong("toAccountId") else null,
                        categoryId = if (o.has("categoryId")) o.getLong("categoryId") else null,
                        note = o.optString("note", ""),
                        installmentNumber = o.optInt("installmentNumber", 1),
                        totalInstallments = o.optInt("totalInstallments", 1)
                    )
                }
                dao.insertTransactions(list)
            }
            updatePreferences { it.copy(lastBackupTimestamp = System.currentTimeMillis()) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
