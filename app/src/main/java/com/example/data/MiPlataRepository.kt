package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.room.withTransaction
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CopFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max

/**
 * Preferencias locales del usuario (sin exponer nunca el PIN ni su hash en memoria de interfaz).
 */
data class UserPreferencesState(
    val onboardingCompleted: Boolean = false,
    val periodMode: PeriodMode = PeriodMode.MENSUAL,
    val customCutoffDay: Int = 1,
    val baseMonthlyIncome: Long = 0L,
    val deductCreditCardDueInAvailable: Boolean = true, // Activado por defecto según especificación
    val hideBalances: Boolean = false,
    val pinLockEnabled: Boolean = false,
    val dailyReminderEnabled: Boolean = true,
    val dailyReminderHour: Int = 20,
    val darkModeOverride: Boolean? = true, // Tema oscuro azul marino por defecto (con opción de cambiar a claro)
    val lastUsedAccountId: Long = 0L,
    val isDemoLoaded: Boolean = false,
    val lastBackupTimestamp: Long = 0L,
    val aiFeaturesEnabled: Boolean = true,
    val aiPrivacyConsentAccepted: Boolean = false
)

/**
 * Resultado al intentar desbloquear o verificar el PIN con control de intentos y backoff exponencial.
 */
sealed class PinUnlockResult {
    data object Success : PinUnlockResult()
    data class InvalidPin(
        val failedAttempts: Int,
        val remainingAttemptsBeforeLockout: Int
    ) : PinUnlockResult()
    data class LockedOut(
        val remainingSeconds: Long,
        val lockoutUntilMillis: Long
    ) : PinUnlockResult()
}

/**
 * Resultado detallado al restaurar una copia de seguridad JSON.
 */
sealed class BackupRestoreResult {
    data class Success(
        val accountsCount: Int,
        val transactionsCount: Int,
        val message: String = "Copia restaurada: $accountsCount cuentas y $transactionsCount movimientos."
    ) : BackupRestoreResult()

    data class Error(
        val reason: String
    ) : BackupRestoreResult()
}

/**
 * Cuenta seleccionada por el usuario en el Paso 1 del onboarding.
 */
data class OnboardingAccountChoice(
    val name: String,
    val initialBalance: Long = 0L,
    val colorHex: Long = 0xFF10B981,
    val iconName: String = "payments"
)

/**
 * Resultado al intentar eliminar una cuenta:
 * - DELETED: no tenía movimientos, se eliminó de la base de datos.
 * - ARCHIVED: tenía movimientos históricos, se archivó (isArchived = true) para conservar el historial.
 * - NOT_FOUND: la cuenta no existía.
 */
enum class AccountRemovalOutcome {
    DELETED,
    ARCHIVED,
    NOT_FOUND
}

/**
 * Repositorio central de MiPlata (Room v7 + Modelo unificado + Recurrencia automática + Compras a cuotas + Backup CSV/JSON + Seguridad PIN PBKDF2).
 */
class MiPlataRepository(
    context: Context,
    private val db: MiPlataDatabase = MiPlataDatabase.getInstance(context),
    private val dao: MiPlataDao = db.dao()
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE)

    init {
        migrateLegacyPlaintextPinIfPresent()
    }

    private val _userPrefs = MutableStateFlow(loadPreferences())
    val userPreferences: StateFlow<UserPreferencesState> = _userPrefs.asStateFlow()

    val accountsFlow: Flow<List<AccountEntity>> = dao.observeAccounts()
    val categoriesFlow: Flow<List<CategoryEntity>> = dao.observeCategories()
    val transactionsFlow: Flow<List<TransactionEntity>> = dao.observeTransactions()
    val savingGoalsFlow: Flow<List<SavingGoalEntity>> = dao.observeSavingGoals()

    /**
     * Migra un PIN en texto plano antiguo ("pin_code") a hash PBKDF2WithHmacSHA256 en el primer arranque
     * y borra inmediatamente la clave en texto plano de SharedPreferences.
     */
    fun migrateLegacyPlaintextPinIfPresent() {
        val hasPlaintextKey = prefs.contains(PREF_LEGACY_PIN_CODE)
        val legacyPin = prefs.getString(PREF_LEGACY_PIN_CODE, "")?.trim().orEmpty()
        val editor = prefs.edit()
        if (legacyPin.isNotEmpty() && !hasConfiguredPinHash()) {
            val salt = generateRandomSalt()
            val hash = hashPinWithSalt(legacyPin, salt, PBKDF2_ITERATIONS)
            editor.putString(PREF_PIN_SALT_HEX, bytesToHex(salt))
            editor.putString(PREF_PIN_HASH_HEX, bytesToHex(hash))
            editor.putInt(PREF_PIN_ITERATIONS, PBKDF2_ITERATIONS)
        }
        if (hasPlaintextKey) {
            editor.remove(PREF_LEGACY_PIN_CODE)
        }
        val lockEnabled = prefs.getBoolean("pin_lock_enabled", false)
        val willHaveHash = (legacyPin.isNotEmpty()) || hasConfiguredPinHash()
        if (lockEnabled && !willHaveHash) {
            editor.putBoolean("pin_lock_enabled", false)
        }
        editor.commit()
    }

    fun hasConfiguredPinHash(): Boolean {
        val saltHex = prefs.getString(PREF_PIN_SALT_HEX, "").orEmpty()
        val hashHex = prefs.getString(PREF_PIN_HASH_HEX, "").orEmpty()
        return saltHex.length >= PBKDF2_SALT_BYTES * 2 && hashHex.isNotEmpty()
    }

    fun getFailedPinAttempts(): Int = prefs.getInt(PREF_PIN_FAILED_ATTEMPTS, 0)

    fun getPinLockoutUntilMillis(): Long = prefs.getLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, 0L)

    fun getRemainingLockoutSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        val until = getPinLockoutUntilMillis()
        return if (until > nowMillis) {
            ((until - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
        } else {
            0L
        }
    }

    fun resetPinLockoutAndAttempts() {
        prefs.edit()
            .putInt(PREF_PIN_FAILED_ATTEMPTS, 0)
            .putLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, 0L)
            .commit()
    }

    /**
     * Verifica el PIN ingresado en tiempo constante aplicando espera exponencial a partir del 5.º fallo
     * (30 s en el 5.º, 60 s en el 6.º, 120 s en el 7.º, etc.), persistida en SharedPreferences.
     * Nunca acepta un PIN vacío ni desbloquea si no existe hash configurado.
     */
    fun verifyPinAttempt(
        enteredPin: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PinUnlockResult {
        val currentLockoutUntil = getPinLockoutUntilMillis()
        if (currentLockoutUntil > nowMillis) {
            val remainingSec = ((currentLockoutUntil - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
            return PinUnlockResult.LockedOut(
                remainingSeconds = remainingSec,
                lockoutUntilMillis = currentLockoutUntil
            )
        }

        val saltHex = prefs.getString(PREF_PIN_SALT_HEX, "").orEmpty()
        val hashHex = prefs.getString(PREF_PIN_HASH_HEX, "").orEmpty()
        val iterations = prefs.getInt(PREF_PIN_ITERATIONS, PBKDF2_ITERATIONS).coerceAtLeast(PBKDF2_ITERATIONS)

        val isMatch = if (enteredPin.isNotBlank() && saltHex.isNotEmpty() && hashHex.isNotEmpty()) {
            val saltBytes = hexToBytes(saltHex)
            val expectedHashBytes = hexToBytes(hashHex)
            verifyPinConstantTime(
                candidatePin = enteredPin,
                expectedSalt = saltBytes,
                expectedHash = expectedHashBytes,
                iterations = iterations
            )
        } else {
            false
        }

        if (isMatch) {
            resetPinLockoutAndAttempts()
            return PinUnlockResult.Success
        }

        val newFailures = getFailedPinAttempts() + 1
        return if (newFailures >= MAX_PIN_ATTEMPTS_BEFORE_LOCKOUT) {
            val lockoutDurationMs = computeLockoutDurationMillis(newFailures)
            val newLockoutUntil = nowMillis + lockoutDurationMs
            prefs.edit()
                .putInt(PREF_PIN_FAILED_ATTEMPTS, newFailures)
                .putLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, newLockoutUntil)
                .commit()
            PinUnlockResult.LockedOut(
                remainingSeconds = (lockoutDurationMs / 1000L).coerceAtLeast(1L),
                lockoutUntilMillis = newLockoutUntil
            )
        } else {
            prefs.edit()
                .putInt(PREF_PIN_FAILED_ATTEMPTS, newFailures)
                .putLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, 0L)
                .commit()
            PinUnlockResult.InvalidPin(
                failedAttempts = newFailures,
                remainingAttemptsBeforeLockout = (MAX_PIN_ATTEMPTS_BEFORE_LOCKOUT - newFailures).coerceAtLeast(0)
            )
        }
    }

    /**
     * Crea, cambia o desactiva el PIN de seguridad:
     * - Si ya existe un PIN configurado, exige verificar el PIN actual antes de cambiarlo o desactivarlo.
     * - Al crear o cambiar el PIN, exige 4 a 6 dígitos numéricos y que newPin == confirmNewPin.
     * Devuelve null si tuvo éxito o un mensaje de error en español si falló la validación.
     */
    fun configureOrUpdatePin(
        enablePin: Boolean,
        currentPin: String = "",
        newPin: String = "",
        confirmNewPin: String = newPin,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        if (hasConfiguredPinHash()) {
            when (val verifyRes = verifyPinAttempt(currentPin, nowMillis)) {
                is PinUnlockResult.LockedOut -> {
                    return "Demasiados intentos fallidos. Espera ${verifyRes.remainingSeconds} s."
                }
                is PinUnlockResult.InvalidPin -> {
                    return "El PIN actual es incorrecto. Ingrésalo para cambiar o desactivar el bloqueo."
                }
                is PinUnlockResult.Success -> {
                    // PIN actual verificado correctamente
                }
            }
        }

        if (!enablePin) {
            prefs.edit()
                .remove(PREF_PIN_SALT_HEX)
                .remove(PREF_PIN_HASH_HEX)
                .remove(PREF_PIN_ITERATIONS)
                .remove(PREF_LEGACY_PIN_CODE)
                .putInt(PREF_PIN_FAILED_ATTEMPTS, 0)
                .putLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, 0L)
                .putBoolean("pin_lock_enabled", false)
                .commit()
            updatePreferences { it.copy(pinLockEnabled = false) }
            return null
        }

        if (!isValidPinFormat(newPin)) {
            return "El PIN debe tener entre 4 y 6 dígitos numéricos."
        }
        if (newPin != confirmNewPin) {
            return "Los dos PIN no coinciden. Escríbelo dos veces igual."
        }

        val salt = generateRandomSalt()
        val hash = hashPinWithSalt(newPin, salt, PBKDF2_ITERATIONS)
        prefs.edit()
            .putString(PREF_PIN_SALT_HEX, bytesToHex(salt))
            .putString(PREF_PIN_HASH_HEX, bytesToHex(hash))
            .putInt(PREF_PIN_ITERATIONS, PBKDF2_ITERATIONS)
            .remove(PREF_LEGACY_PIN_CODE)
            .putInt(PREF_PIN_FAILED_ATTEMPTS, 0)
            .putLong(PREF_PIN_LOCKOUT_UNTIL_MILLIS, 0L)
            .putBoolean("pin_lock_enabled", true)
            .commit()
        updatePreferences { it.copy(pinLockEnabled = true) }
        return null
    }

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
            pinLockEnabled = prefs.getBoolean("pin_lock_enabled", false) && hasConfiguredPinHash(),
            dailyReminderEnabled = prefs.getBoolean("daily_reminder_enabled", true),
            dailyReminderHour = prefs.getInt("daily_reminder_hour", 20),
            darkModeOverride = darkOverride,
            lastUsedAccountId = prefs.getLong("last_used_account_id", 0L),
            isDemoLoaded = prefs.getBoolean("is_demo_loaded", false),
            lastBackupTimestamp = prefs.getLong("last_backup_timestamp", 0L),
            aiFeaturesEnabled = prefs.getBoolean("ai_features_enabled", true),
            aiPrivacyConsentAccepted = prefs.getBoolean("ai_privacy_consent_accepted", false)
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
            .remove(PREF_LEGACY_PIN_CODE)
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
            .putBoolean("ai_features_enabled", updated.aiFeaturesEnabled)
            .putBoolean("ai_privacy_consent_accepted", updated.aiPrivacyConsentAccepted)
            .apply()
        _userPrefs.value = updated
    }

    /**
     * Guarda el análisis financiero generado por Gemini junto con su fecha para mostrarlo al abrir el asistente
     * sin necesidad de regenerarlo automáticamente.
     */
    fun savePersistedAiReport(report: AiFinancialReport) {
        prefs.edit()
            .putString(PREF_PERSISTED_AI_REPORT_JSON, report.toJsonString())
            .apply()
    }

    fun loadPersistedAiReport(): AiFinancialReport? {
        val raw = prefs.getString(PREF_PERSISTED_AI_REPORT_JSON, null)
        return AiFinancialReport.fromJsonString(raw)
    }

    /**
     * Inicializa las cuentas y categorías por defecto sin borrar datos existentes.
     */
    suspend fun ensureDefaultsSeeded() = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (dao.getAccountsOnce().isEmpty()) {
                dao.insertAccounts(MiPlataDatabase.defaultAccounts())
            }
            if (dao.getCategoriesOnce().isEmpty()) {
                dao.insertCategories(MiPlataDatabase.defaultCategories())
            }
        }
    }

    /**
     * Completa el onboarding real de 3 pasos dentro de una única transacción de Room:
     * 1. Crea ÚNICAMENTE las cuentas seleccionadas por el usuario (sin tarjetas de crédito ni ahorros inventados).
     *    Si había alguna cuenta por defecto sin movimientos que el usuario no eligió, la retira.
     * 2. Configura el período de pago y el ingreso aproximado.
     * 3. Si el usuario ingresó un pago fijo opcional, lo registra en la cuenta elegida.
     */
    suspend fun completeOnboardingSetup(
        selectedAccounts: List<OnboardingAccountChoice>,
        periodMode: PeriodMode = PeriodMode.MENSUAL,
        customCutoffDay: Int = 1,
        approximateIncome: Long = 0L,
        fixedPaymentName: String = "",
        fixedPaymentAmount: Long = 0L,
        fixedPaymentDayOfMonth: Int = 5,
        fixedPaymentAccountName: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        var primaryAccountId = 0L
        db.withTransaction {
            if (dao.getCategoriesOnce().isEmpty()) {
                dao.insertCategories(MiPlataDatabase.defaultCategories())
            }

            val cleanChoices = selectedAccounts
                .mapNotNull { choice ->
                    val trimmed = choice.name.trim()
                    if (trimmed.isEmpty()) null else choice.copy(
                        name = trimmed,
                        initialBalance = choice.initialBalance.coerceAtLeast(0L)
                    )
                }
                .distinctBy { it.name.lowercase(Locale.ROOT) }
                .ifEmpty {
                    listOf(
                        OnboardingAccountChoice(
                            name = "Efectivo",
                            initialBalance = 0L,
                            colorHex = 0xFF10B981,
                            iconName = "payments"
                        )
                    )
                }

            val existingAccounts = dao.getAccountsOnce().filter { !it.isDemo }
            val chosenNamesLower = cleanChoices.map { it.name.lowercase(Locale.ROOT) }.toSet()

            // Retirar cuentas previas sin movimientos que NO fueron seleccionadas en el onboarding
            for (acc in existingAccounts) {
                val accNameLower = acc.name.trim().lowercase(Locale.ROOT)
                if (accNameLower !in chosenNamesLower && dao.countTransactionsForAccount(acc.id) == 0) {
                    dao.deleteAccount(acc)
                }
            }

            val remainingByName = dao.getAccountsOnce()
                .filter { !it.isDemo }
                .associateBy { it.name.trim().lowercase(Locale.ROOT) }

            val createdIdsByName = linkedMapOf<String, Long>()
            for (choice in cleanChoices) {
                val key = choice.name.lowercase(Locale.ROOT)
                val existing = remainingByName[key]
                val accId = if (existing != null) {
                    val updated = existing.copy(
                        name = choice.name,
                        type = AccountType.DEBITO,
                        initialBalance = choice.initialBalance,
                        colorHex = choice.colorHex,
                        iconName = choice.iconName,
                        isDefault = true,
                        includeInAvailableFilter = true,
                        isArchived = false
                    )
                    dao.updateAccount(updated)
                    existing.id
                } else {
                    dao.insertAccount(
                        AccountEntity(
                            id = 0L,
                            name = choice.name,
                            type = AccountType.DEBITO,
                            initialBalance = choice.initialBalance,
                            colorHex = choice.colorHex,
                            iconName = choice.iconName,
                            isDefault = true,
                            includeInAvailableFilter = true,
                            isDemo = false,
                            isArchived = false
                        )
                    )
                }
                createdIdsByName[key] = accId
                if (primaryAccountId == 0L) {
                    primaryAccountId = accId
                }
            }

            // Paso 3: Pago fijo opcional en la cuenta elegida
            val cleanFixedName = fixedPaymentName.trim()
            if (cleanFixedName.isNotEmpty() && fixedPaymentAmount > 0L) {
                val targetKey = fixedPaymentAccountName?.trim()?.lowercase(Locale.ROOT)
                val paymentAccountId = (if (targetKey != null) createdIdsByName[targetKey] else null)
                    ?: primaryAccountId

                val categories = dao.getCategoriesOnce()
                val fixedCategoryKey = if (cleanFixedName.contains("arriendo", ignoreCase = true)) {
                    SystemCategoryKeys.ARRIENDO
                } else {
                    SystemCategoryKeys.SERVICIOS
                }
                val fixedCategoryId = resolveCategoryId(categories, fixedCategoryKey, CategoryType.GASTO)
                val safeDay = fixedPaymentDayOfMonth.coerceIn(1, 31)
                val dueCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.DAY_OF_MONTH, 1)
                    val maxD = getActualMaximum(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH, safeDay.coerceAtMost(maxD))
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                dao.insertTransaction(
                    TransactionEntity(
                        id = 0L,
                        type = TransactionType.PAGO_PROGRAMADO,
                        amount = fixedPaymentAmount,
                        timestamp = dueCal.timeInMillis,
                        dueTimestamp = dueCal.timeInMillis,
                        recurrenceAnchorDay = safeDay,
                        accountId = paymentAccountId,
                        categoryId = fixedCategoryId,
                        note = cleanFixedName,
                        isRecurring = true,
                        status = TransactionStatus.PENDIENTE,
                        recurrence = RecurrenceFrequency.MENSUAL
                    )
                )
            }
        }

        updatePreferences { current ->
            current.copy(
                onboardingCompleted = true,
                periodMode = periodMode,
                customCutoffDay = customCutoffDay.coerceIn(1, 28),
                baseMonthlyIncome = approximateIncome.coerceAtLeast(0L),
                lastUsedAccountId = if (primaryAccountId > 0L) primaryAccountId else current.lastUsedAccountId
            )
        }
    }

    // --- OPERACIONES DE CUENTAS Y CATEGORÍAS ---
    suspend fun saveAccount(account: AccountEntity): Long = dao.insertAccount(account)
    suspend fun updateAccount(account: AccountEntity) = dao.updateAccount(account)
    suspend fun deleteAccount(account: AccountEntity) = dao.deleteAccount(account)

    suspend fun countTransactionsForAccount(accountId: Long): Int = withContext(Dispatchers.IO) {
        dao.countTransactionsForAccount(accountId)
    }

    /**
     * Si la cuenta no tiene movimientos, la elimina de la base de datos.
     * Si tiene movimientos, NO la borra: la marca como archivada (`isArchived = true`)
     * para que no aparezca en selectores ni sume en totales pero sus movimientos sigan en el historial.
     */
    suspend fun deleteOrArchiveAccount(accountId: Long): AccountRemovalOutcome =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val account = dao.getAccountById(accountId)
                    ?: return@withTransaction AccountRemovalOutcome.NOT_FOUND
                val txCount = dao.countTransactionsForAccount(accountId)
                if (txCount > 0) {
                    dao.updateAccount(account.copy(isArchived = true))
                    AccountRemovalOutcome.ARCHIVED
                } else {
                    dao.deleteAccount(account)
                    AccountRemovalOutcome.DELETED
                }
            }
        }

    /**
     * Archiva o desarchiva una cuenta existente.
     */
    suspend fun setAccountArchived(
        accountId: Long,
        archived: Boolean
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val account = dao.getAccountById(accountId) ?: return@withTransaction
            dao.updateAccount(account.copy(isArchived = archived))
        }
    }

    suspend fun countTransactionsForCategory(categoryId: Long): Int = withContext(Dispatchers.IO) {
        dao.countTransactionsForCategory(categoryId)
    }

    private suspend fun hasDuplicateCategoryName(
        candidateName: String,
        type: CategoryType,
        excludeCategoryId: Long = 0L
    ): Boolean {
        val clean = candidateName.trim()
        if (clean.isEmpty()) return false
        return dao.getCategoriesOnce().any { existing ->
            existing.id != excludeCategoryId &&
                existing.type == type &&
                existing.name.trim().equals(clean, ignoreCase = true)
        }
    }

    /**
     * Crea o edita una categoría validando que el nombre no esté vacío ni duplicado
     * dentro del mismo tipo (`GASTO` o `INGRESO`).
     * Devuelve `null` si se guardó con éxito o un mensaje de error en español.
     */
    suspend fun saveOrUpdateCategory(
        id: Long = 0L,
        name: String,
        type: CategoryType,
        iconName: String,
        colorHex: Long,
        groupName: String,
        monthlyLimit: Long = 0L
    ): String? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) {
                return@withTransaction "Escribe un nombre para la categoría."
            }
            val existing = if (id > 0L) dao.getCategoryById(id) else null
            val effectiveType = existing?.type ?: type
            if (hasDuplicateCategoryName(cleanName, effectiveType, excludeCategoryId = id)) {
                return@withTransaction "Ya existe una categoría llamada \"$cleanName\" en ${effectiveType.label}."
            }
            val defaultGroup = if (effectiveType == CategoryType.INGRESO) "Ingresos" else "Estilo de vida"
            val cleanGroup = groupName.trim().ifEmpty { existing?.groupName ?: defaultGroup }
            val cleanIcon = iconName.trim().ifEmpty { existing?.iconName ?: "category" }

            if (existing != null) {
                dao.updateCategory(
                    existing.copy(
                        name = cleanName,
                        iconName = cleanIcon,
                        colorHex = colorHex,
                        groupName = cleanGroup,
                        monthlyLimit = monthlyLimit.coerceAtLeast(0L)
                    )
                )
            } else {
                dao.insertCategory(
                    CategoryEntity(
                        id = 0L,
                        name = cleanName,
                        iconName = cleanIcon,
                        colorHex = colorHex,
                        type = effectiveType,
                        groupName = cleanGroup,
                        monthlyLimit = monthlyLimit.coerceAtLeast(0L),
                        systemKey = null
                    )
                )
            }
            null
        }
    }

    /**
     * Actualiza el límite mensual y el grupo de una categoría.
     * `categories.monthlyLimit` es la única fuente de verdad para presupuestos.
     */
    suspend fun updateCategoryLimitAndGroup(
        category: CategoryEntity,
        newLimit: Long,
        newGroupName: String
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val updated = category.copy(
                monthlyLimit = newLimit.coerceAtLeast(0L),
                groupName = newGroupName.ifBlank { category.groupName }
            )
            dao.updateCategory(updated)
        }
    }

    /**
     * Permite renombrar cualquier categoría, incluidas las categorías del sistema (con `systemKey`),
     * siempre que no exista otra categoría con el mismo nombre dentro del mismo tipo.
     */
    suspend fun renameCategory(
        category: CategoryEntity,
        newName: String
    ): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val cleanName = newName.trim()
            if (cleanName.isEmpty()) return@withTransaction false
            if (hasDuplicateCategoryName(cleanName, category.type, excludeCategoryId = category.id)) {
                return@withTransaction false
            }
            dao.updateCategory(category.copy(name = cleanName))
            true
        }
    }

    /**
     * Elimina una categoría solo si NO es una categoría del sistema (`systemKey == null`).
     * Si la categoría tiene movimientos asociados, exige indicar `reassignToCategoryId` para
     * reasignarlos dentro de la misma transacción antes de borrarla.
     */
    suspend fun deleteCategory(
        category: CategoryEntity,
        reassignToCategoryId: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        deleteCategoryWithReassignment(category, reassignToCategoryId) == null
    }

    /**
     * Elimina una categoría reasignando sus movimientos a otra categoría existente dentro de una sola
     * transacción de Room. Devuelve `null` si tuvo éxito o un mensaje de error en español.
     */
    suspend fun deleteCategoryWithReassignment(
        category: CategoryEntity,
        reassignToCategoryId: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (!category.systemKey.isNullOrBlank()) {
                return@withTransaction "Las categorías base del sistema se pueden renombrar o editar, pero no eliminar."
            }
            val txCount = dao.countTransactionsForCategory(category.id)
            if (txCount > 0) {
                if (reassignToCategoryId == null || reassignToCategoryId == category.id) {
                    return@withTransaction "Elige a qué categoría pasar los $txCount movimientos antes de eliminarla."
                }
                val targetCat = dao.getCategoryById(reassignToCategoryId)
                    ?: return@withTransaction "La categoría de destino seleccionada no existe."
                dao.reassignTransactionsCategory(
                    oldCategoryId = category.id,
                    newCategoryId = targetCat.id
                )
            }
            dao.deleteCategory(category)
            null
        }
    }

    companion object {
        const val PREF_LEGACY_PIN_CODE = "pin_code"
        const val PREF_PIN_SALT_HEX = "pin_salt_hex"
        const val PREF_PIN_HASH_HEX = "pin_hash_hex"
        const val PREF_PIN_ITERATIONS = "pin_iterations"
        const val PREF_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        const val PREF_PIN_LOCKOUT_UNTIL_MILLIS = "pin_lockout_until_millis"
        const val PREF_PERSISTED_AI_REPORT_JSON = "persisted_ai_report_json"

        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 120_000
        const val PBKDF2_SALT_BYTES = 16
        const val PBKDF2_KEY_BITS = 256

        const val MAX_PIN_ATTEMPTS_BEFORE_LOCKOUT = 5
        const val INITIAL_LOCKOUT_DURATION_MS = 30_000L
        const val BACKGROUND_RELOCK_TIMEOUT_MS = 60_000L
        const val MAX_RECEIPT_IMAGE_SIDE_PX = 1280

        /**
         * Resuelve un id de cuenta activa válido sin usar números fijos:
         * - Ignora cuentas archivadas (`isArchived == true`).
         * - Si `preferredAccountId` existe entre las cuentas activas, lo usa.
         * - Si no, usa la primera cuenta DEBITO real activa (o la primera disponible).
         */
        fun resolveValidAccountId(
            accounts: List<AccountEntity>,
            preferredAccountId: Long = 0L
        ): Long {
            val active = accounts.filter { !it.isArchived }
            if (preferredAccountId > 0L && active.any { it.id == preferredAccountId }) {
                return preferredAccountId
            }
            return active.firstOrNull { !it.isDemo && it.type == AccountType.DEBITO }?.id
                ?: active.firstOrNull { it.type == AccountType.DEBITO }?.id
                ?: active.firstOrNull { !it.isDemo }?.id
                ?: active.firstOrNull()?.id
                ?: accounts.firstOrNull()?.id
                ?: 0L
        }

        /**
         * Resuelve una categoría buscando primero por `systemKey` y, si no existe,
         * usando la primera categoría del tipo solicitado (por defecto `CategoryType.GASTO`).
         */
        fun resolveCategory(
            categories: List<CategoryEntity>,
            systemKey: String? = null,
            fallbackType: CategoryType = CategoryType.GASTO
        ): CategoryEntity? {
            if (!systemKey.isNullOrBlank()) {
                categories.firstOrNull { it.systemKey == systemKey }?.let { return it }
            }
            return categories.firstOrNull { it.type == fallbackType }
                ?: categories.firstOrNull()
        }

        fun resolveCategoryId(
            categories: List<CategoryEntity>,
            systemKey: String? = null,
            fallbackType: CategoryType = CategoryType.GASTO
        ): Long? {
            return resolveCategory(categories, systemKey, fallbackType)?.id
        }

        fun isValidPinFormat(pin: String): Boolean {
            return pin.length in 4..6 && pin.all { it.isDigit() }
        }

        fun generateRandomSalt(numBytes: Int = PBKDF2_SALT_BYTES): ByteArray {
            val salt = ByteArray(numBytes)
            SecureRandom().nextBytes(salt)
            return salt
        }

        fun hashPinWithSalt(
            pin: String,
            salt: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS
        ): ByteArray {
            val effectiveIterations = iterations.coerceAtLeast(PBKDF2_ITERATIONS)
            val spec = PBEKeySpec(pin.toCharArray(), salt, effectiveIterations, PBKDF2_KEY_BITS)
            return try {
                val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun verifyPinConstantTime(
            candidatePin: String,
            expectedSalt: ByteArray,
            expectedHash: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS
        ): Boolean {
            if (candidatePin.isBlank() || expectedSalt.isEmpty() || expectedHash.isEmpty()) {
                return false
            }
            val candidateHash = hashPinWithSalt(candidatePin, expectedSalt, iterations)
            return MessageDigest.isEqual(candidateHash, expectedHash)
        }

        fun computeLockoutDurationMillis(failedAttempts: Int): Long {
            if (failedAttempts < MAX_PIN_ATTEMPTS_BEFORE_LOCKOUT) return 0L
            val exponent = (failedAttempts - MAX_PIN_ATTEMPTS_BEFORE_LOCKOUT).coerceIn(0, 20)
            return INITIAL_LOCKOUT_DURATION_MS * (1L shl exponent)
        }

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            if (clean.length % 2 != 0) return ByteArray(0)
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Reduce en memoria una imagen de recibo para que su lado más largo sea como máximo maxSidePx (1280 px),
         * sin guardar ningún archivo temporal en almacenamiento.
         */
        fun downscaleBitmapForReceipt(
            source: Bitmap,
            maxSidePx: Int = MAX_RECEIPT_IMAGE_SIDE_PX
        ): Bitmap {
            val width = source.width
            val height = source.height
            val longestSide = maxOf(width, height)
            if (longestSide <= maxSidePx || width <= 0 || height <= 0) {
                return source
            }
            val targetWidth: Int
            val targetHeight: Int
            if (width >= height) {
                targetWidth = maxSidePx
                targetHeight = ((height.toLong() * maxSidePx) / width.toLong()).toInt().coerceAtLeast(1)
            } else {
                targetHeight = maxSidePx
                targetWidth = ((width.toLong() * maxSidePx) / height.toLong()).toInt().coerceAtLeast(1)
            }
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

        /**
         * Extrae el día del mes (1..31) en la zona horaria local.
         */
        fun extractLocalDayOfMonth(timestampMillis: Long): Int {
            return Calendar.getInstance().apply { timeInMillis = timestampMillis }.get(Calendar.DAY_OF_MONTH)
        }

        /**
         * Convierte un timestamp local al instante 00:00:00.000 UTC de ese mismo año/mes/día
         * para inicializar correctamente DatePickerState de Material 3.
         */
        fun localTimestampToUtcDateMillis(localTimestamp: Long): Long {
            val localCal = Calendar.getInstance().apply { timeInMillis = localTimestamp }
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(
                    localCal.get(Calendar.YEAR),
                    localCal.get(Calendar.MONTH),
                    localCal.get(Calendar.DAY_OF_MONTH),
                    0,
                    0,
                    0
                )
                set(Calendar.MILLISECOND, 0)
            }
            return utcCal.timeInMillis
        }

        /**
         * Convierte la fecha seleccionada en el DatePicker (que viene en UTC) a la zona horaria local
         * conservando la hora, minuto, segundo y milisegundo de referenceLocalTimestamp.
         */
        fun mergeUtcDateSelectionWithLocalTime(
            utcDateMillis: Long,
            referenceLocalTimestamp: Long
        ): Long {
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = utcDateMillis
            }
            val localCal = Calendar.getInstance().apply {
                timeInMillis = referenceLocalTimestamp
                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
            }
            return localCal.timeInMillis
        }

        /**
         * Indica si candidateLocalTimestamp corresponde a un día calendario posterior a hoy (en zona local).
         */
        fun isFutureLocalDay(
            candidateLocalTimestamp: Long,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            val endOfToday = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
            return candidateLocalTimestamp > endOfToday
        }

        /**
         * Calcula la fecha de la siguiente ocurrencia de una transacción recurrente.
         * Para recurrencia MENSUAL y ANUAL usa el día ancla original (anchorDay, ej. 31),
         * ajustándolo al último día cuando el mes sea más corto.
         */
        fun computeNextOccurrenceTimestamp(
            currentTimestamp: Long,
            recurrence: RecurrenceFrequency,
            customDays: Int = 30,
            anchorDay: Int = 0
        ): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = currentTimestamp }
            val effectiveAnchor = if (anchorDay > 0) anchorDay else cal.get(Calendar.DAY_OF_MONTH)
            when (recurrence) {
                RecurrenceFrequency.SEMANAL -> cal.add(Calendar.DAY_OF_YEAR, 7)
                RecurrenceFrequency.QUINCENAL -> cal.add(Calendar.DAY_OF_YEAR, 15)
                RecurrenceFrequency.MENSUAL -> {
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.MONTH, 1)
                    val maxDayInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(effectiveAnchor, maxDayInMonth))
                }
                RecurrenceFrequency.ANUAL -> {
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.YEAR, 1)
                    val maxDayInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(effectiveAnchor, maxDayInMonth))
                }
                RecurrenceFrequency.PERSONALIZADA -> cal.add(Calendar.DAY_OF_YEAR, customDays.coerceAtLeast(1))
                RecurrenceFrequency.NINGUNA -> {}
            }
            return cal.timeInMillis
        }
    }

    /**
     * Guarda una transacción nueva o edita una existente preservando los campos no modificados.
     * - Si es COMPRA_CUOTAS con totalInstallments > 1 y es nueva (id == 0L), genera la cuota 1
     *   como PAGADA y las cuotas 2..N como PENDIENTES mes a mes.
     * - Al editar (tx.id > 0L), carga el original desde Room y usa original.copy(...) sin perder
     *   hasGeneratedNextOccurrence, parentRecurringId, installmentNumber, totalInstallments,
     *   linkedGoalId, isDemo ni isInitialBalance.
     * - En una cuota existente de una compra a cuotas no permite cambiar el tipo ni el número de cuotas.
     * - Si es recurrente nuevo y está PAGADO, genera la próxima ocurrencia como PENDIENTE a partir de dueTimestamp.
     */
    suspend fun saveTransaction(tx: TransactionEntity): Long = withContext(Dispatchers.IO) {
        var resolvedAccountId = tx.accountId
        val insertedId = db.withTransaction {
            val allAccounts = dao.getAccountsOnce()
            val validAccountId = resolveValidAccountId(allAccounts, tx.accountId)

            val existing = if (tx.id > 0L) dao.getTransactionById(tx.id) else null
            if (existing != null) {
                val isExistingInstallment =
                    existing.type == TransactionType.COMPRA_CUOTAS && existing.totalInstallments > 1
                val effectiveType = if (isExistingInstallment) existing.type else tx.type
                val needsDest =
                    effectiveType == TransactionType.TRANSFERENCIA || effectiveType == TransactionType.PAGO_TARJETA
                val effectiveRecurrence = if (isExistingInstallment) existing.recurrence else tx.recurrence
                val effectiveIsRecurring = if (isExistingInstallment) {
                    existing.isRecurring
                } else {
                    effectiveRecurrence != RecurrenceFrequency.NINGUNA || tx.isRecurring
                }

                val wasAlreadyPaid = existing.status == TransactionStatus.PAGADO
                val shouldGenerateNext = !wasAlreadyPaid &&
                    tx.status == TransactionStatus.PAGADO &&
                    effectiveRecurrence != RecurrenceFrequency.NINGUNA &&
                    !existing.hasGeneratedNextOccurrence

                val effectiveDueTimestamp = when {
                    tx.dueTimestamp != null -> tx.dueTimestamp
                    !wasAlreadyPaid && tx.status == TransactionStatus.PENDIENTE -> tx.timestamp
                    else -> existing.dueTimestamp ?: existing.timestamp
                }

                val effectiveAnchorDay = if (
                    effectiveRecurrence == RecurrenceFrequency.MENSUAL ||
                    effectiveRecurrence == RecurrenceFrequency.ANUAL
                ) {
                    when {
                        !wasAlreadyPaid && tx.status == TransactionStatus.PENDIENTE && tx.timestamp != existing.timestamp ->
                            extractLocalDayOfMonth(tx.timestamp)
                        existing.recurrenceAnchorDay > 0 -> existing.recurrenceAnchorDay
                        tx.recurrenceAnchorDay > 0 -> tx.recurrenceAnchorDay
                        else -> extractLocalDayOfMonth(effectiveDueTimestamp)
                    }
                } else {
                    existing.recurrenceAnchorDay
                }

                // Ajustar meta vinculada si cambió el monto al editar
                if (tx.amount != existing.amount) {
                    existing.linkedGoalId?.let { goalId ->
                        dao.getSavingGoalById(goalId)?.let { goal ->
                            val newSaved = (goal.savedAmount - existing.amount + tx.amount).coerceAtLeast(0L)
                            dao.updateSavingGoal(goal.copy(savedAmount = newSaved))
                        }
                    }
                }

                val updated = existing.copy(
                    type = effectiveType,
                    amount = tx.amount,
                    timestamp = tx.timestamp,
                    accountId = validAccountId,
                    toAccountId = if (needsDest) tx.toAccountId else null,
                    categoryId = if (needsDest) null else tx.categoryId,
                    note = tx.note,
                    receiptUri = tx.receiptUri ?: existing.receiptUri,
                    isRecurring = effectiveIsRecurring,
                    status = tx.status,
                    recurrence = effectiveRecurrence,
                    customRecurrenceDays = if (isExistingInstallment) existing.customRecurrenceDays else tx.customRecurrenceDays,
                    hasGeneratedNextOccurrence = existing.hasGeneratedNextOccurrence || shouldGenerateNext,
                    dueTimestamp = effectiveDueTimestamp,
                    recurrenceAnchorDay = effectiveAnchorDay
                )
                resolvedAccountId = updated.accountId
                dao.updateTransaction(updated)

                if (shouldGenerateNext) {
                    val nextDue = computeNextOccurrenceTimestamp(
                        currentTimestamp = effectiveDueTimestamp,
                        recurrence = effectiveRecurrence,
                        customDays = updated.customRecurrenceDays,
                        anchorDay = effectiveAnchorDay
                    )
                    val nextPending = updated.copy(
                        id = 0L,
                        timestamp = nextDue,
                        dueTimestamp = nextDue,
                        recurrenceAnchorDay = effectiveAnchorDay,
                        status = TransactionStatus.PENDIENTE,
                        parentRecurringId = updated.id,
                        hasGeneratedNextOccurrence = false
                    )
                    dao.insertTransaction(nextPending)
                }
                return@withTransaction updated.id
            }

            resolvedAccountId = validAccountId
            val txWithValidAccount = tx.copy(accountId = validAccountId)

            if (txWithValidAccount.id == 0L &&
                txWithValidAccount.type == TransactionType.COMPRA_CUOTAS &&
                txWithValidAccount.totalInstallments > 1
            ) {
                val count = txWithValidAccount.totalInstallments.coerceIn(2, 48)
                val perInstallment = (txWithValidAccount.amount / count).coerceAtLeast(1L)
                val remainder = txWithValidAccount.amount - (perInstallment * count)
                val anchorDay = extractLocalDayOfMonth(txWithValidAccount.timestamp)
                var firstId = 0L
                var currentInstTime = txWithValidAccount.timestamp

                for (idx in 1..count) {
                    if (idx > 1) {
                        currentInstTime = computeNextOccurrenceTimestamp(
                            currentTimestamp = currentInstTime,
                            recurrence = RecurrenceFrequency.MENSUAL,
                            anchorDay = anchorDay
                        )
                    }
                    val instAmount = if (idx == 1) perInstallment + remainder else perInstallment
                    val instStatus = if (idx == 1) TransactionStatus.PAGADO else TransactionStatus.PENDIENTE
                    val baseNote = txWithValidAccount.note.ifBlank { "Compra a cuotas" }
                    val instTx = txWithValidAccount.copy(
                        id = 0L,
                        amount = instAmount,
                        timestamp = currentInstTime,
                        dueTimestamp = currentInstTime,
                        recurrenceAnchorDay = anchorDay,
                        status = instStatus,
                        installmentNumber = idx,
                        totalInstallments = count,
                        note = "$baseNote (Cuota $idx/$count)"
                    )
                    val inserted = dao.insertTransaction(instTx)
                    if (idx == 1) firstId = inserted
                }
                return@withTransaction firstId
            }

            val effectiveDue = txWithValidAccount.dueTimestamp ?: txWithValidAccount.timestamp
            val effectiveAnchor = if (
                txWithValidAccount.recurrence == RecurrenceFrequency.MENSUAL ||
                txWithValidAccount.recurrence == RecurrenceFrequency.ANUAL
            ) {
                if (txWithValidAccount.recurrenceAnchorDay > 0) {
                    txWithValidAccount.recurrenceAnchorDay
                } else {
                    extractLocalDayOfMonth(effectiveDue)
                }
            } else {
                txWithValidAccount.recurrenceAnchorDay
            }

            val shouldGenerateNext = txWithValidAccount.recurrence != RecurrenceFrequency.NINGUNA &&
                txWithValidAccount.status == TransactionStatus.PAGADO &&
                !txWithValidAccount.hasGeneratedNextOccurrence

            val savedTx = txWithValidAccount.copy(
                isRecurring = txWithValidAccount.recurrence != RecurrenceFrequency.NINGUNA || txWithValidAccount.isRecurring,
                hasGeneratedNextOccurrence = txWithValidAccount.hasGeneratedNextOccurrence || shouldGenerateNext,
                dueTimestamp = effectiveDue,
                recurrenceAnchorDay = effectiveAnchor
            )

            val id = dao.insertTransaction(savedTx)

            if (shouldGenerateNext) {
                val nextTime = computeNextOccurrenceTimestamp(
                    currentTimestamp = effectiveDue,
                    recurrence = txWithValidAccount.recurrence,
                    customDays = txWithValidAccount.customRecurrenceDays,
                    anchorDay = effectiveAnchor
                )
                val nextPending = savedTx.copy(
                    id = 0L,
                    timestamp = nextTime,
                    dueTimestamp = nextTime,
                    recurrenceAnchorDay = effectiveAnchor,
                    status = TransactionStatus.PENDIENTE,
                    parentRecurringId = id,
                    hasGeneratedNextOccurrence = false
                )
                dao.insertTransaction(nextPending)
            }
            id
        }
        updatePreferences { it.copy(lastUsedAccountId = resolvedAccountId) }
        insertedId
    }

    /**
     * Edita o crea un movimiento desde el formulario o ViewModel:
     * Si id > 0L, carga el movimiento original desde Room y usa original.copy(...)
     * modificando únicamente los campos suministrados por el formulario.
     */
    suspend fun saveOrEditMovementFromForm(
        id: Long = 0L,
        type: TransactionType? = null,
        amount: Long? = null,
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
    ): Long = withContext(Dispatchers.IO) {
        val existing = if (id > 0L) dao.getTransactionById(id) else null
        if (existing != null) {
            val isExistingInstallment =
                existing.type == TransactionType.COMPRA_CUOTAS && existing.totalInstallments > 1
            val finalType = if (isExistingInstallment) existing.type else (type ?: existing.type)
            val needsDest =
                finalType == TransactionType.TRANSFERENCIA || finalType == TransactionType.PAGO_TARJETA
            val finalRecurrence = if (isExistingInstallment) {
                existing.recurrence
            } else {
                recurrence ?: existing.recurrence
            }
            val finalIsRecurring = if (isExistingInstallment) {
                existing.isRecurring
            } else {
                isRecurring ?: (finalRecurrence != RecurrenceFrequency.NINGUNA || existing.isRecurring)
            }
            val merged = existing.copy(
                type = finalType,
                amount = amount ?: existing.amount,
                timestamp = timestamp ?: existing.timestamp,
                accountId = accountId ?: existing.accountId,
                toAccountId = if (needsDest) (toAccountId ?: existing.toAccountId) else null,
                categoryId = if (needsDest) null else (categoryId ?: existing.categoryId),
                note = note?.trim() ?: existing.note,
                receiptUri = receiptUri ?: existing.receiptUri,
                isRecurring = finalIsRecurring,
                status = status ?: existing.status,
                recurrence = finalRecurrence,
                customRecurrenceDays = if (isExistingInstallment) {
                    existing.customRecurrenceDays
                } else {
                    customRecurrenceDays ?: existing.customRecurrenceDays
                },
                installmentNumber = existing.installmentNumber,
                totalInstallments = existing.totalInstallments
            )
            saveTransaction(merged)
        } else {
            val finalType = type ?: TransactionType.GASTO
            val needsDest =
                finalType == TransactionType.TRANSFERENCIA || finalType == TransactionType.PAGO_TARJETA
            val finalTimestamp = timestamp ?: nowMillis
            val finalStatus = status ?: when {
                finalType == TransactionType.PAGO_PROGRAMADO -> TransactionStatus.PENDIENTE
                isFutureLocalDay(finalTimestamp, nowMillis) -> TransactionStatus.PENDIENTE
                else -> TransactionStatus.PAGADO
            }
            val finalRecurrence = recurrence ?: if (isRecurring == true) {
                RecurrenceFrequency.MENSUAL
            } else {
                RecurrenceFrequency.NINGUNA
            }
            val allAccounts = dao.getAccountsOnce()
            val resolvedAccId = resolveValidAccountId(
                accounts = allAccounts,
                preferredAccountId = accountId ?: _userPrefs.value.lastUsedAccountId
            )
            val newTx = TransactionEntity(
                id = 0L,
                type = finalType,
                amount = amount ?: 0L,
                timestamp = finalTimestamp,
                dueTimestamp = finalTimestamp,
                accountId = resolvedAccId,
                toAccountId = if (needsDest) toAccountId else null,
                categoryId = if (needsDest) null else categoryId,
                note = note?.trim().orEmpty(),
                receiptUri = receiptUri,
                isRecurring = finalRecurrence != RecurrenceFrequency.NINGUNA || (isRecurring == true),
                status = finalStatus,
                recurrence = finalRecurrence,
                customRecurrenceDays = customRecurrenceDays ?: 30,
                totalInstallments = if (finalType == TransactionType.COMPRA_CUOTAS) {
                    (totalInstallments ?: 1).coerceAtLeast(2)
                } else {
                    1
                }
            )
            saveTransaction(newTx)
        }
    }

    /**
     * Marca una transacción PENDIENTE como PAGADA:
     * - timestamp pasa a ser la fecha real de pago (nowMillis).
     * - dueTimestamp NO cambia (se conserva la fecha de vencimiento original).
     * - La siguiente ocurrencia se calcula a partir de dueTimestamp usando el día ancla original.
     */
    suspend fun markTransactionAsPaid(
        tx: TransactionEntity,
        nowMillis: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = if (tx.id > 0L) dao.getTransactionById(tx.id) ?: tx else tx
            val originalDue = current.dueTimestamp ?: current.timestamp
            val anchorDay = if (
                current.recurrence == RecurrenceFrequency.MENSUAL ||
                current.recurrence == RecurrenceFrequency.ANUAL
            ) {
                if (current.recurrenceAnchorDay > 0) {
                    current.recurrenceAnchorDay
                } else {
                    extractLocalDayOfMonth(originalDue)
                }
            } else {
                current.recurrenceAnchorDay
            }

            val shouldGenerateNext =
                current.recurrence != RecurrenceFrequency.NINGUNA && !current.hasGeneratedNextOccurrence

            val paidTx = current.copy(
                status = TransactionStatus.PAGADO,
                timestamp = nowMillis,
                dueTimestamp = originalDue,
                recurrenceAnchorDay = anchorDay,
                isRecurring = current.recurrence != RecurrenceFrequency.NINGUNA || current.isRecurring,
                hasGeneratedNextOccurrence = current.hasGeneratedNextOccurrence || shouldGenerateNext
            )
            val savedId = dao.insertTransaction(paidTx)

            if (shouldGenerateNext) {
                val nextDue = computeNextOccurrenceTimestamp(
                    currentTimestamp = originalDue,
                    recurrence = current.recurrence,
                    customDays = current.customRecurrenceDays,
                    anchorDay = anchorDay
                )
                val nextPending = paidTx.copy(
                    id = 0L,
                    timestamp = nextDue,
                    dueTimestamp = nextDue,
                    recurrenceAnchorDay = anchorDay,
                    status = TransactionStatus.PENDIENTE,
                    parentRecurringId = savedId,
                    hasGeneratedNextOccurrence = false
                )
                dao.insertTransaction(nextPending)
            }
        }
    }

    /**
     * Omite una ocurrencia pendiente: si es recurrente, genera la del siguiente ciclo (desde dueTimestamp) y elimina la actual.
     */
    suspend fun skipPendingOccurrence(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = if (tx.id > 0L) dao.getTransactionById(tx.id) ?: tx else tx
            if (current.recurrence != RecurrenceFrequency.NINGUNA && !current.hasGeneratedNextOccurrence) {
                val baseDue = current.dueTimestamp ?: current.timestamp
                val anchorDay = if (current.recurrenceAnchorDay > 0) {
                    current.recurrenceAnchorDay
                } else {
                    extractLocalDayOfMonth(baseDue)
                }
                val nextTime = computeNextOccurrenceTimestamp(
                    currentTimestamp = baseDue,
                    recurrence = current.recurrence,
                    customDays = current.customRecurrenceDays,
                    anchorDay = anchorDay
                )
                dao.insertTransaction(
                    current.copy(
                        id = 0L,
                        timestamp = nextTime,
                        dueTimestamp = nextTime,
                        recurrenceAnchorDay = anchorDay,
                        status = TransactionStatus.PENDIENTE,
                        parentRecurringId = current.id,
                        hasGeneratedNextOccurrence = false
                    )
                )
            }
            dao.deleteTransaction(current)
        }
    }

    /**
     * Borra un movimiento dentro de una transacción de Room y revierte su efecto si está ligado
     * a una meta de ahorro (resta savedAmount).
     */
    suspend fun deleteTransaction(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val target = if (tx.id > 0L) dao.getTransactionById(tx.id) ?: tx else tx
            target.linkedGoalId?.let { goalId ->
                dao.getSavingGoalById(goalId)?.let { goal ->
                    val newSaved = (goal.savedAmount - target.amount).coerceAtLeast(0L)
                    dao.updateSavingGoal(goal.copy(savedAmount = newSaved))
                }
            }
            dao.deleteTransaction(target)
        }
    }

    /**
     * Restaura ("Deshacer") un movimiento previamente borrado de forma idéntica,
     * volviendo a aplicar su efecto sobre la meta vinculada y sin generar ocurrencias nuevas.
     */
    suspend fun restoreDeletedTransaction(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            tx.linkedGoalId?.let { goalId ->
                dao.getSavingGoalById(goalId)?.let { goal ->
                    dao.updateSavingGoal(goal.copy(savedAmount = goal.savedAmount + tx.amount))
                }
            }
            dao.insertTransaction(tx)
        }
    }

    /**
     * Registra un abono a una cuenta de tipo PRESTADO (lo que me deben) o PRESTAMO (lo que debo)
     * y genera únicamente el movimiento de transferencia en la cuenta de débito correspondiente.
     *
     * Regla:
     * - `receivedOrPaidAmount` no se modifica; representa solo lo abonado antes de usar la app.
     * - El abono no puede superar el saldo pendiente actual. Si supera lo pendiente, devuelve
     *   `"El abono supera lo pendiente ($X)"` y no guarda nada.
     */
    suspend fun registerLoanOrLentPayment(
        targetAccount: AccountEntity,
        amount: Long,
        debitAccountId: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): String? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val allAccounts = dao.getAccountsOnce()
            val freshTarget = allAccounts.find { it.id == targetAccount.id } ?: targetAccount
            val resolvedDebitId = resolveValidAccountId(allAccounts, debitAccountId)
            val allTransactions = dao.getTransactionsOnce()
            val pendingBalance = AvailableToSpendCalculator.computeAccountBalance(
                account = freshTarget,
                allTransactions = allTransactions,
                includePending = false
            )

            if (amount > pendingBalance) {
                return@withTransaction "El abono supera lo pendiente (${CopFormatter.format(pendingBalance)})"
            }
            if (amount <= 0L) {
                return@withTransaction "El monto del abono debe ser mayor a $0"
            }

            if (freshTarget.type == AccountType.PRESTADO) {
                // Me pagaron algo que me debían -> entra dinero a mi cuenta de débito
                dao.insertTransaction(
                    TransactionEntity(
                        type = TransactionType.TRANSFERENCIA,
                        amount = amount,
                        timestamp = nowMillis,
                        dueTimestamp = nowMillis,
                        accountId = freshTarget.id,
                        toAccountId = resolvedDebitId,
                        note = "Abono recibido de ${freshTarget.personName.ifBlank { freshTarget.name }}",
                        status = TransactionStatus.PAGADO,
                        isDemo = freshTarget.isDemo
                    )
                )
            } else {
                // Pagué cuota de mi préstamo -> sale dinero de mi cuenta de débito hacia el préstamo
                dao.insertTransaction(
                    TransactionEntity(
                        type = TransactionType.TRANSFERENCIA,
                        amount = amount,
                        timestamp = nowMillis,
                        dueTimestamp = nowMillis,
                        accountId = resolvedDebitId,
                        toAccountId = freshTarget.id,
                        note = "Abono a préstamo: ${freshTarget.name}",
                        status = TransactionStatus.PAGADO,
                        isDemo = freshTarget.isDemo
                    )
                )
            }
            null
        }
    }

    // --- METAS DE AHORRO ---
    suspend fun saveSavingGoal(goal: SavingGoalEntity): Long = dao.insertSavingGoal(goal)
    suspend fun deleteSavingGoal(goal: SavingGoalEntity) = dao.deleteSavingGoal(goal)

    suspend fun contributeToSavingGoal(
        goal: SavingGoalEntity,
        amount: Long,
        accountId: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        var resolvedAccId = accountId
        db.withTransaction {
            val accounts = dao.getAccountsOnce()
            val categories = dao.getCategoriesOnce()
            resolvedAccId = resolveValidAccountId(accounts, accountId)
            val otrosCatId = resolveCategoryId(
                categories = categories,
                systemKey = SystemCategoryKeys.OTROS_GASTO,
                fallbackType = CategoryType.GASTO
            )

            val updatedGoal = goal.copy(savedAmount = goal.savedAmount + amount)
            dao.updateSavingGoal(updatedGoal)
            dao.insertTransaction(
                TransactionEntity(
                    type = TransactionType.GASTO,
                    amount = amount,
                    timestamp = nowMillis,
                    accountId = resolvedAccId,
                    categoryId = otrosCatId,
                    note = "Aporte a meta: ${goal.name}",
                    linkedGoalId = goal.id,
                    isDemo = goal.isDemo,
                    status = TransactionStatus.PAGADO
                )
            )
        }
        updatePreferences { it.copy(lastUsedAccountId = resolvedAccId) }
    }

    /**
     * Carga datos de ejemplo creando sus propias cuentas nuevas (id = 0 para autogenerar,
     * isDemo = true, nombre terminado en " (demo)") dentro de una sola transacción de Room.
     * Sus movimientos y metas apuntan a los ids generados y buscan las categorías por `systemKey`.
     * Nunca modifica ninguna cuenta, categoría ni preferencia existente, salvo isDemoLoaded.
     */
    suspend fun loadThreeMonthDemoData(nowMillis: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                // Limpiar cualquier demo previa antes de insertar la nueva (primero movimientos por FK)
                dao.deleteDemoTransactions()
                dao.deleteDemoGoals()
                dao.deleteDemoAccounts()

                if (dao.getCategoriesOnce().isEmpty()) {
                    dao.insertCategories(MiPlataDatabase.defaultCategories())
                }
                val categories = dao.getCategoriesOnce()
                val catComidaId = resolveCategoryId(categories, SystemCategoryKeys.COMIDA, CategoryType.GASTO)
                val catTransporteId = resolveCategoryId(categories, SystemCategoryKeys.TRANSPORTE, CategoryType.GASTO)
                val catArriendoId = resolveCategoryId(categories, SystemCategoryKeys.ARRIENDO, CategoryType.GASTO)
                val catServiciosId = resolveCategoryId(categories, SystemCategoryKeys.SERVICIOS, CategoryType.GASTO)
                val catMercadoId = resolveCategoryId(categories, SystemCategoryKeys.MERCADO, CategoryType.GASTO)
                val catOcioId = resolveCategoryId(categories, SystemCategoryKeys.OCIO, CategoryType.GASTO)
                val catRopaId = resolveCategoryId(categories, SystemCategoryKeys.ROPA, CategoryType.GASTO)
                val catGasolinaId = resolveCategoryId(categories, SystemCategoryKeys.GASOLINA, CategoryType.GASTO)
                val catSueldoId = resolveCategoryId(categories, SystemCategoryKeys.SUELDO, CategoryType.INGRESO)
                val catVentasId = resolveCategoryId(categories, SystemCategoryKeys.VENTAS, CategoryType.INGRESO)
                val catOtrosIngresoId = resolveCategoryId(categories, SystemCategoryKeys.OTROS_INGRESO, CategoryType.INGRESO)

                // 1. Insertar cuentas de demostración nuevas (id = 0, isDemo = true, terminadas en " (demo)")
                val efectivoDemoId = dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Efectivo (demo)",
                        type = AccountType.DEBITO,
                        initialBalance = 220_000L,
                        colorHex = 0xFF10B981,
                        iconName = "payments",
                        isDefault = false,
                        isDemo = true
                    )
                )
                val nuDemoId = dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Nu Débito (demo)",
                        type = AccountType.DEBITO,
                        initialBalance = 1_850_000L,
                        colorHex = 0xFF8B5CF6,
                        iconName = "account_balance",
                        isDefault = false,
                        isDemo = true
                    )
                )
                val nequiDemoId = dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Nequi (demo)",
                        type = AccountType.DEBITO,
                        initialBalance = 540_000L,
                        colorHex = 0xFFEC4899,
                        iconName = "smartphone",
                        isDefault = false,
                        isDemo = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Cajita Ahorros Nu (demo)",
                        type = AccountType.AHORROS,
                        initialBalance = 3_200_000L,
                        colorHex = 0xFF38BDF8,
                        iconName = "savings",
                        isDefault = false,
                        includeInAvailableFilter = false,
                        isDemo = true
                    )
                )
                val tarjetaDemoId = dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Tarjeta Nu (demo)",
                        type = AccountType.TARJETA_CREDITO,
                        initialBalance = 0L,
                        colorHex = 0xFFA855F7,
                        iconName = "credit_card",
                        isDefault = false,
                        creditLimit = 4_000_000L,
                        billingCutoffDay = 20,
                        paymentDueDay = 5,
                        lastFourDigits = "4821",
                        includeInAvailableFilter = false,
                        isDemo = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Crédito Moto Sufí (demo)",
                        type = AccountType.PRESTAMO,
                        initialBalance = 2_700_000L,
                        colorHex = 0xFFF59E0B,
                        iconName = "credit_card",
                        originalAmount = 4_800_000L,
                        receivedOrPaidAmount = 2_100_000L,
                        monthlyInstallment = 320_000L,
                        includeInAvailableFilter = false,
                        isDemo = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        id = 0L,
                        name = "Carlos Ramírez (demo)",
                        type = AccountType.PRESTADO,
                        initialBalance = 350_000L,
                        colorHex = 0xFF14B8A6,
                        iconName = "payments",
                        personName = "Carlos Ramírez (Primo)",
                        originalAmount = 600_000L,
                        receivedOrPaidAmount = 250_000L,
                        includeInAvailableFilter = false,
                        isDemo = true
                    )
                )

                val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
                fun dayOffset(days: Int, hour: Int = 14): Long {
                    return (cal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, days)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, 30)
                    }.timeInMillis
                }

                // 2. Insertar movimientos de ejemplo apuntando a los ids generados de las cuentas demo y categorías por systemKey
                val demoTransactions = listOf(
                    TransactionEntity(
                        type = TransactionType.INGRESO,
                        amount = 3_400_000L,
                        timestamp = dayOffset(-8, 9),
                        accountId = nuDemoId,
                        categoryId = catSueldoId,
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
                        accountId = nuDemoId,
                        categoryId = catArriendoId,
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
                        accountId = nuDemoId,
                        categoryId = catMercadoId,
                        note = "Mercado Éxito y D1",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.GASTO,
                        amount = 185_000L,
                        timestamp = dayOffset(-4, 20),
                        accountId = tarjetaDemoId,
                        categoryId = catComidaId,
                        note = "Cena restaurante Crepes & Waffles",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.COMPRA_CUOTAS,
                        amount = 220_000L,
                        timestamp = dayOffset(-3, 16),
                        accountId = tarjetaDemoId,
                        categoryId = catRopaId,
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
                        accountId = tarjetaDemoId,
                        categoryId = catOtrosIngresoId,
                        note = "Cashback Tarjeta Nu",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.REEMBOLSO,
                        amount = 25_000L,
                        timestamp = dayOffset(-2, 12),
                        accountId = nequiDemoId,
                        categoryId = catComidaId,
                        note = "Reembolso pedido incompleto Rappi",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.PAGO_TARJETA,
                        amount = 150_000L,
                        timestamp = dayOffset(-1, 15),
                        accountId = nuDemoId,
                        toAccountId = tarjetaDemoId,
                        note = "Abono anticipado a Tarjeta Nu",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.GASTO,
                        amount = 48_000L,
                        timestamp = dayOffset(0, 13),
                        accountId = nequiDemoId,
                        categoryId = catTransporteId,
                        note = "DiDi y recarga transporte",
                        status = TransactionStatus.PAGADO,
                        isDemo = true
                    ),
                    TransactionEntity(
                        type = TransactionType.PAGO_PROGRAMADO,
                        amount = 135_000L,
                        timestamp = dayOffset(2, 9),
                        dueTimestamp = dayOffset(2, 9),
                        accountId = nequiDemoId,
                        categoryId = catServiciosId,
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
                        dueTimestamp = dayOffset(4, 10),
                        accountId = tarjetaDemoId,
                        categoryId = catOcioId,
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
                        dueTimestamp = dayOffset(6, 11),
                        accountId = nuDemoId,
                        categoryId = catVentasId,
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
                        dueTimestamp = dayOffset(9, 8),
                        accountId = efectivoDemoId,
                        categoryId = catGasolinaId,
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
                        dueTimestamp = dayOffset(27, 16),
                        accountId = tarjetaDemoId,
                        categoryId = catRopaId,
                        note = "Tenis deportivos (Cuota 2/3)",
                        status = TransactionStatus.PENDIENTE,
                        installmentNumber = 2,
                        totalInstallments = 3,
                        isDemo = true
                    )
                )
                dao.insertTransactions(demoTransactions)

                // 3. Metas de ejemplo marcadas con isDemo = true
                dao.insertSavingGoals(
                    listOf(
                        SavingGoalEntity(
                            name = "Viaje San Andrés (demo)",
                            targetAmount = 2_400_000L,
                            savedAmount = 600_000L,
                            deadlineTimestamp = dayOffset(180),
                            monthlyTargetContribution = 300_000L,
                            isDemo = true
                        )
                    )
                )
            }

            // No modificar ninguna preferencia existente salvo isDemoLoaded
            updatePreferences {
                it.copy(isDemoLoaded = true)
            }
        }

    /**
     * Borra únicamente las filas con isDemo = 1 en todas las tablas (incluidas las cuentas)
     * dentro de una sola transacción, respetando el orden de claves foráneas (primero movimientos, luego cuentas).
     * No toca saldos iniciales, categorías ni ingreso base.
     */
    suspend fun clearDemoDataOnly() = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.deleteDemoTransactions()
            dao.deleteDemoGoals()
            dao.deleteDemoAccounts()
        }
        updatePreferences { it.copy(isDemoLoaded = false) }
    }

    /**
     * Escapa un campo de texto para CSV:
     * - Si empieza por '=', '+', '-' o '@', antepone un apóstrofo (').
     * - Duplica las comillas dobles internas ("").
     * - Envuelve el texto entre comillas dobles.
     */
    private fun escapeCsvTextField(raw: String): String {
        val protectedFormula = if (raw.isNotEmpty() && (raw[0] == '=' || raw[0] == '+' || raw[0] == '-' || raw[0] == '@')) {
            "'$raw"
        } else {
            raw
        }
        val escapedQuotes = protectedFormula.replace("\"", "\"\"")
        return "\"$escapedQuotes\""
    }

    /**
     * Exporta todos los movimientos reales a formato CSV compatible con Excel (con BOM UTF-8).
     */
    suspend fun exportTransactionsToCsv(nowMillis: Long = System.currentTimeMillis()): String =
        withContext(Dispatchers.IO) {
            val accounts = dao.getAccountsOnce().associateBy { it.id }
            val categories = dao.getCategoriesOnce().associateBy { it.id }
            val transactions = dao.getTransactionsOnce().filter { !it.isDemo }

            val sb = StringBuilder()
            // BOM UTF-8 para que Excel en Windows/Mac muestre correctamente las tildes
            sb.append('\uFEFF')
            sb.appendLine("ID,Fecha,Hora,Tipo,Estado,Monto_COP,Cuenta,Cuenta_Destino,Categoria,Nota,Recurrencia,Cuota")
            for (tx in transactions) {
                val dateStr = escapeCsvTextField(CopFormatter.formatShortDate(tx.timestamp))
                val timeStr = escapeCsvTextField(CopFormatter.formatTime(tx.timestamp))
                val typeStr = escapeCsvTextField(tx.type.label)
                val statusStr = escapeCsvTextField(tx.status.label)
                val accName = escapeCsvTextField(accounts[tx.accountId]?.name ?: "Cuenta")
                val toAccName = escapeCsvTextField(tx.toAccountId?.let { accounts[it]?.name } ?: "")
                val catName = escapeCsvTextField(tx.categoryId?.let { categories[it]?.name } ?: tx.type.label)
                val safeNote = escapeCsvTextField(tx.note)
                val recStr = escapeCsvTextField(tx.recurrence.label)
                val cuotaStr = escapeCsvTextField(
                    if (tx.totalInstallments > 1) "${tx.installmentNumber}/${tx.totalInstallments}" else "1/1"
                )
                sb.appendLine(
                    "${tx.id},$dateStr,$timeStr,$typeStr,$statusStr,${tx.amount},$accName,$toAccName,$catName,$safeNote,$recStr,$cuotaStr"
                )
            }
            updatePreferences { it.copy(lastBackupTimestamp = nowMillis) }
            sb.toString()
        }

    /**
     * Crea una copia de seguridad completa en formato JSON (version 3) con todas las tablas
     * y todos los campos de cada entidad más las preferencias (excluyendo PIN y datos de demo).
     */
    suspend fun createBackupJson(nowMillis: Long = System.currentTimeMillis()): String =
        withContext(Dispatchers.IO) {
            val root = JSONObject()
            root.put("app", "MiPlata")
            root.put("version", 3)
            root.put("exportedAt", nowMillis)

            // 1. Cuentas (excluyendo isDemo = true)
            val accountsArr = JSONArray()
            dao.getAccountsOnce()
                .filter { !it.isDemo }
                .forEach { a ->
                    accountsArr.put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("name", a.name)
                            put("type", a.type.name)
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
                            put("includeInAvailableFilter", a.includeInAvailableFilter)
                            put("isDemo", false)
                            put("isArchived", a.isArchived)
                        }
                    )
                }
            root.put("accounts", accountsArr)

            // 2. Categorías (incluyendo monthlyLimit y systemKey)
            val categoriesArr = JSONArray()
            dao.getCategoriesOnce().forEach { c ->
                categoriesArr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("iconName", c.iconName)
                        put("colorHex", c.colorHex)
                        put("type", c.type.name)
                        put("groupName", c.groupName)
                        put("monthlyLimit", c.monthlyLimit)
                        put("systemKey", c.systemKey ?: JSONObject.NULL)
                    }
                )
            }
            root.put("categories", categoriesArr)

            // 3. Movimientos / Transacciones (excluyendo isDemo = true)
            val txArr = JSONArray()
            dao.getTransactionsOnce()
                .filter { !it.isDemo }
                .forEach { t ->
                    txArr.put(
                        JSONObject().apply {
                            put("id", t.id)
                            put("type", t.type.name)
                            put("amount", t.amount)
                            put("timestamp", t.timestamp)
                            put("accountId", t.accountId)
                            put("toAccountId", t.toAccountId ?: JSONObject.NULL)
                            put("categoryId", t.categoryId ?: JSONObject.NULL)
                            put("note", t.note)
                            put("receiptUri", t.receiptUri ?: JSONObject.NULL)
                            put("isRecurring", t.isRecurring)
                            put("isInitialBalance", t.isInitialBalance)
                            put("isDemo", false)
                            put("linkedGoalId", t.linkedGoalId ?: JSONObject.NULL)
                            put("status", t.status.name)
                            put("recurrence", t.recurrence.name)
                            put("customRecurrenceDays", t.customRecurrenceDays)
                            put("installmentNumber", t.installmentNumber)
                            put("totalInstallments", t.totalInstallments)
                            put("parentRecurringId", t.parentRecurringId ?: JSONObject.NULL)
                            put("hasGeneratedNextOccurrence", t.hasGeneratedNextOccurrence)
                            put("dueTimestamp", t.dueTimestamp ?: JSONObject.NULL)
                            put("recurrenceAnchorDay", t.recurrenceAnchorDay)
                        }
                    )
                }
            root.put("transactions", txArr)

            // 4. Metas de ahorro (excluyendo isDemo = true)
            val goalsArr = JSONArray()
            dao.getSavingGoalsOnce()
                .filter { !it.isDemo }
                .forEach { g ->
                    goalsArr.put(
                        JSONObject().apply {
                            put("id", g.id)
                            put("name", g.name)
                            put("targetAmount", g.targetAmount)
                            put("savedAmount", g.savedAmount)
                            put("deadlineTimestamp", g.deadlineTimestamp)
                            put("monthlyTargetContribution", g.monthlyTargetContribution)
                            put("colorHex", g.colorHex)
                            put("iconName", g.iconName)
                            put("isDemo", false)
                        }
                    )
                }
            root.put("savingGoals", goalsArr)

            // 5. Preferencias (sin PIN ni estado de demo)
            val currentPrefs = _userPrefs.value
            val prefsObj = JSONObject().apply {
                put("onboardingCompleted", currentPrefs.onboardingCompleted)
                put("periodMode", currentPrefs.periodMode.name)
                put("customCutoffDay", currentPrefs.customCutoffDay)
                put("baseMonthlyIncome", currentPrefs.baseMonthlyIncome)
                put("deductCreditCardDueInAvailable", currentPrefs.deductCreditCardDueInAvailable)
                put("hideBalances", currentPrefs.hideBalances)
                put("dailyReminderEnabled", currentPrefs.dailyReminderEnabled)
                put("dailyReminderHour", currentPrefs.dailyReminderHour)
                put("darkModeOverride", currentPrefs.darkModeOverride ?: JSONObject.NULL)
                put("lastUsedAccountId", currentPrefs.lastUsedAccountId)
                put("aiFeaturesEnabled", currentPrefs.aiFeaturesEnabled)
                put("aiPrivacyConsentAccepted", currentPrefs.aiPrivacyConsentAccepted)
            }
            root.put("preferences", prefsObj)

            updatePreferences { it.copy(lastBackupTimestamp = nowMillis) }
            root.toString(2)
        }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    /**
     * Restaura los datos desde un archivo JSON de copia de seguridad en 3 pasos estrictos:
     * 1) Leer y convertir el archivo completo a objetos en memoria.
     * 2) Validar: app == "MiPlata", versión soportada (2 o 3), montos >= 0, e integridad referencial
     *    de accountId, toAccountId y categoryId en los movimientos.
     * 3) Solo si todo es válido: en UNA sola transacción de Room, vaciar las tablas e insertar lo nuevo.
     */
    suspend fun restoreFromBackupJson(
        jsonContent: String,
        nowMillis: Long = System.currentTimeMillis()
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        if (jsonContent.isBlank()) {
            return@withContext BackupRestoreResult.Error("El archivo está vacío.")
        }

        val root = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return@withContext BackupRestoreResult.Error("El archivo no es un JSON válido.")
        }

        try {
            // Validación de cabecera
            val appName = root.optString("app", "")
            if (appName != "MiPlata") {
                return@withContext BackupRestoreResult.Error("El archivo no pertenece a MiPlata.")
            }

            val version = root.optInt("version", -1)
            if (version != 2 && version != 3) {
                return@withContext BackupRestoreResult.Error("Versión de copia de seguridad no soportada ($version).")
            }

            // PASO A: Convertir todo el archivo a objetos en memoria (ignorando registros de demo)
            val parsedAccounts = mutableListOf<AccountEntity>()
            root.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val accountTypeStr = o.getString("type")
                    val accountType = MiPlataTypeConverters.parseAccountTypeTolerant(accountTypeStr)
                        ?: return@withContext BackupRestoreResult.Error("Tipo de cuenta desconocido: $accountTypeStr.")
                    val account = AccountEntity(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        type = accountType,
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
                        monthlyInstallment = o.optLong("monthlyInstallment", 0L),
                        includeInAvailableFilter = o.optBoolean("includeInAvailableFilter", accountType == AccountType.DEBITO),
                        isDemo = false,
                        isArchived = o.optBoolean("isArchived", false)
                    )
                    if (account.initialBalance < 0L ||
                        account.creditLimit < 0L ||
                        account.originalAmount < 0L ||
                        account.receivedOrPaidAmount < 0L ||
                        account.monthlyInstallment < 0L
                    ) {
                        return@withContext BackupRestoreResult.Error("La cuenta '${account.name}' contiene montos negativos.")
                    }
                    parsedAccounts += account
                }
            }

            val hasCategoriesInFile = root.has("categories") && !root.isNull("categories")
            val parsedCategories = mutableListOf<CategoryEntity>()
            if (hasCategoriesInFile) {
                val arr = root.getJSONArray("categories")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catTypeStr = o.getString("type")
                    val catType = runCatching { CategoryType.valueOf(catTypeStr) }
                        .getOrElse {
                            return@withContext BackupRestoreResult.Error("Tipo de categoría inválido: $catTypeStr.")
                        }
                    val cat = CategoryEntity(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        iconName = o.optString("iconName", "category"),
                        colorHex = o.optLong("colorHex", 0xFF64748B),
                        type = catType,
                        groupName = o.optString("groupName", "Estilo de vida"),
                        monthlyLimit = o.optLong("monthlyLimit", 0L),
                        systemKey = o.optNullableString("systemKey")
                    )
                    if (cat.monthlyLimit < 0L) {
                        return@withContext BackupRestoreResult.Error("La categoría '${cat.name}' tiene un límite mensual negativo.")
                    }
                    parsedCategories += cat
                }
            }

            val parsedTransactions = mutableListOf<TransactionEntity>()
            root.optJSONArray("transactions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val txTypeStr = o.getString("type")
                    val txType = runCatching { TransactionType.valueOf(txTypeStr) }
                        .getOrElse {
                            return@withContext BackupRestoreResult.Error("Tipo de movimiento inválido: $txTypeStr.")
                        }
                    val statusStr = o.optString("status", TransactionStatus.PAGADO.name)
                    val status = runCatching { TransactionStatus.valueOf(statusStr) }
                        .getOrElse {
                            return@withContext BackupRestoreResult.Error("Estado de movimiento inválido: $statusStr.")
                        }
                    val recStr = o.optString("recurrence", RecurrenceFrequency.NINGUNA.name)
                    val recurrence = runCatching { RecurrenceFrequency.valueOf(recStr) }
                        .getOrElse {
                            return@withContext BackupRestoreResult.Error("Frecuencia de recurrencia inválida: $recStr.")
                        }
                    val amount = o.getLong("amount")
                    if (amount < 0L) {
                        return@withContext BackupRestoreResult.Error("El movimiento #${o.optLong("id", 0L)} tiene un monto negativo ($amount).")
                    }
                    if (!o.has("accountId") || o.isNull("accountId")) {
                        return@withContext BackupRestoreResult.Error("El movimiento #${o.optLong("id", 0L)} no tiene cuenta asignada.")
                    }
                    val tx = TransactionEntity(
                        id = o.optLong("id", 0L),
                        type = txType,
                        amount = amount,
                        timestamp = o.optLong("timestamp", nowMillis),
                        accountId = o.getLong("accountId"),
                        toAccountId = o.optNullableLong("toAccountId"),
                        categoryId = o.optNullableLong("categoryId"),
                        note = o.optString("note", ""),
                        receiptUri = o.optNullableString("receiptUri"),
                        isRecurring = o.optBoolean("isRecurring", recurrence != RecurrenceFrequency.NINGUNA),
                        isInitialBalance = o.optBoolean("isInitialBalance", false),
                        isDemo = false,
                        linkedGoalId = o.optNullableLong("linkedGoalId"),
                        status = status,
                        recurrence = recurrence,
                        customRecurrenceDays = o.optInt("customRecurrenceDays", 30),
                        installmentNumber = o.optInt("installmentNumber", 1),
                        totalInstallments = o.optInt("totalInstallments", 1),
                        parentRecurringId = o.optNullableLong("parentRecurringId"),
                        hasGeneratedNextOccurrence = o.optBoolean("hasGeneratedNextOccurrence", false),
                        dueTimestamp = o.optNullableLong("dueTimestamp"),
                        recurrenceAnchorDay = o.optInt("recurrenceAnchorDay", 0)
                    )
                    parsedTransactions += tx
                }
            }

            // Compatibilidad con backups antiguos que incluían arrays "budgets", "obligations" o "debts"
            val legacyBudgets = mutableListOf<Pair<Long, Long>>()
            root.optJSONArray("budgets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val limit = o.getLong("monthlyLimit")
                    if (limit < 0L) {
                        return@withContext BackupRestoreResult.Error("Un presupuesto del archivo tiene límite negativo ($limit).")
                    }
                    legacyBudgets += (o.getLong("categoryId") to limit)
                }
            }

            val parsedSavingGoals = mutableListOf<SavingGoalEntity>()
            (root.optJSONArray("savingGoals") ?: root.optJSONArray("saving_goals"))?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val targetAmount = o.getLong("targetAmount")
                    val savedAmount = o.optLong("savedAmount", 0L)
                    val monthlyContribution = o.getLong("monthlyTargetContribution")
                    if (targetAmount < 0L || savedAmount < 0L || monthlyContribution < 0L) {
                        return@withContext BackupRestoreResult.Error("Una meta de ahorro contiene montos negativos.")
                    }
                    parsedSavingGoals += SavingGoalEntity(
                        id = o.optLong("id", 0L),
                        name = o.getString("name"),
                        targetAmount = targetAmount,
                        savedAmount = savedAmount,
                        deadlineTimestamp = o.getLong("deadlineTimestamp"),
                        monthlyTargetContribution = monthlyContribution,
                        colorHex = o.optLong("colorHex", 0xFF38BDF8),
                        iconName = o.optString("iconName", "savings"),
                        isDemo = false
                    )
                }
            }

            data class LegacyObligation(
                val name: String,
                val amount: Long,
                val dayOfMonth: Int,
                val accountId: Long,
                val categoryId: Long,
                val lastPaidYearMonth: String
            )
            val legacyObligations = mutableListOf<LegacyObligation>()
            (root.optJSONArray("obligations") ?: root.optJSONArray("recurring_obligations"))?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val amount = o.getLong("amount")
                    if (amount < 0L) {
                        return@withContext BackupRestoreResult.Error("Una obligación fija contiene un monto negativo ($amount).")
                    }
                    legacyObligations += LegacyObligation(
                        name = o.getString("name"),
                        amount = amount,
                        dayOfMonth = o.getInt("dayOfMonth"),
                        accountId = o.getLong("accountId"),
                        categoryId = o.getLong("categoryId"),
                        lastPaidYearMonth = o.optString("lastPaidYearMonth", "")
                    )
                }
            }

            root.optJSONArray("debts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("isDemo", false)) continue
                    val totalAmount = o.getLong("totalAmount")
                    val monthlyInstallment = o.getLong("monthlyInstallment")
                    val remainingBalance = o.getLong("remainingBalance")
                    if (totalAmount < 0L || monthlyInstallment < 0L || remainingBalance < 0L) {
                        return@withContext BackupRestoreResult.Error("Una deuda contiene montos negativos.")
                    }
                    val creditorName = o.optString("creditorName", o.optString("name", "Préstamo"))
                    val paidPrior = max(0L, totalAmount - remainingBalance)
                    parsedAccounts += AccountEntity(
                        id = 0L,
                        name = creditorName,
                        type = AccountType.PRESTAMO,
                        initialBalance = remainingBalance,
                        colorHex = 0xFFF59E0B,
                        iconName = "credit_card",
                        isDefault = false,
                        personName = creditorName,
                        originalAmount = totalAmount,
                        receivedOrPaidAmount = paidPrior,
                        monthlyInstallment = monthlyInstallment,
                        includeInAvailableFilter = false,
                        isDemo = false
                    )
                }
            }

            val prefsJson = root.optJSONObject("preferences")
            val parsedBaseIncome = prefsJson?.optLong("baseMonthlyIncome", 0L) ?: 0L
            if (parsedBaseIncome < 0L) {
                return@withContext BackupRestoreResult.Error("El ingreso base en las preferencias es negativo.")
            }

            // PASO B: Validar referencias (accountId, toAccountId, categoryId)
            val validAccountIds = parsedAccounts.filter { it.id > 0L }.map { it.id }.toSet()
            val existingCategoriesForV2 = if (!hasCategoriesInFile) {
                val currentInDb = dao.getCategoriesOnce()
                if (currentInDb.isNotEmpty()) currentInDb else MiPlataDatabase.defaultCategories().mapIndexed { idx, cat ->
                    cat.copy(id = (idx + 1).toLong())
                }
            } else {
                emptyList()
            }
            val validCategoryIds = if (hasCategoriesInFile) {
                parsedCategories.map { it.id }.toSet()
            } else {
                existingCategoriesForV2.map { it.id }.toSet()
            }

            for (tx in parsedTransactions) {
                if (tx.accountId !in validAccountIds) {
                    return@withContext BackupRestoreResult.Error(
                        "El movimiento #${tx.id} apunta a una cuenta inexistente (${tx.accountId})."
                    )
                }
                if (tx.toAccountId != null && tx.toAccountId !in validAccountIds) {
                    return@withContext BackupRestoreResult.Error(
                        "El movimiento #${tx.id} apunta a una cuenta destino inexistente (${tx.toAccountId})."
                    )
                }
                if (tx.categoryId != null && tx.categoryId !in validCategoryIds) {
                    return@withContext BackupRestoreResult.Error(
                        "El movimiento #${tx.id} apunta a una categoría inexistente (${tx.categoryId})."
                    )
                }
            }

            for ((catId, _) in legacyBudgets) {
                if (catId !in validCategoryIds) {
                    return@withContext BackupRestoreResult.Error(
                        "Un presupuesto del archivo apunta a una categoría inexistente ($catId)."
                    )
                }
            }

            val currentYearMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(nowMillis))
            for (ob in legacyObligations) {
                if (ob.accountId !in validAccountIds) {
                    return@withContext BackupRestoreResult.Error(
                        "La obligación '${ob.name}' apunta a una cuenta inexistente (${ob.accountId})."
                    )
                }
                if (ob.categoryId !in validCategoryIds) {
                    return@withContext BackupRestoreResult.Error(
                        "La obligación '${ob.name}' apunta a una categoría inexistente (${ob.categoryId})."
                    )
                }
                val anchorDay = ob.dayOfMonth.coerceIn(1, 31)
                val dueCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.DAY_OF_MONTH, 1)
                    if (ob.lastPaidYearMonth == currentYearMonth) {
                        add(Calendar.MONTH, 1)
                    }
                    val maxD = getActualMaximum(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(maxD))
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                parsedTransactions += TransactionEntity(
                    id = 0L,
                    type = TransactionType.PAGO_PROGRAMADO,
                    amount = ob.amount,
                    timestamp = dueCal.timeInMillis,
                    dueTimestamp = dueCal.timeInMillis,
                    recurrenceAnchorDay = anchorDay,
                    accountId = ob.accountId,
                    categoryId = ob.categoryId,
                    note = ob.name,
                    isRecurring = true,
                    status = TransactionStatus.PENDIENTE,
                    recurrence = RecurrenceFrequency.MENSUAL,
                    isDemo = false
                )
            }

            // Aplicar límites de presupuestos legacy sobre las categorías si son mayores
            if (legacyBudgets.isNotEmpty() && hasCategoriesInFile) {
                val maxLimitByCat = legacyBudgets.groupBy({ it.first }, { it.second })
                    .mapValues { (_, list) -> list.maxOrNull() ?: 0L }
                for (idx in parsedCategories.indices) {
                    val cat = parsedCategories[idx]
                    val budgetLimit = maxLimitByCat[cat.id] ?: 0L
                    if (budgetLimit > cat.monthlyLimit) {
                        parsedCategories[idx] = cat.copy(monthlyLimit = budgetLimit)
                    }
                }
            }

            // PASO C: Solo si todo es válido, en UNA sola transacción vaciar e insertar todo
            // (Respetando FK: borrar movimientos antes que cuentas; insertar cuentas antes que movimientos)
            db.withTransaction {
                dao.clearAllTransactions()
                dao.clearAllSavingGoals()
                dao.clearAllAccounts()

                if (hasCategoriesInFile) {
                    dao.clearAllCategories()
                    if (parsedCategories.isNotEmpty()) {
                        dao.insertCategories(parsedCategories)
                    }
                } else {
                    if (dao.getCategoriesOnce().isEmpty()) {
                        dao.insertCategories(existingCategoriesForV2)
                    }
                }

                if (parsedAccounts.isNotEmpty()) dao.insertAccounts(parsedAccounts)
                if (parsedTransactions.isNotEmpty()) dao.insertTransactions(parsedTransactions)
                if (parsedSavingGoals.isNotEmpty()) dao.insertSavingGoals(parsedSavingGoals)
            }

            val restoredLastAccountId = resolveValidAccountId(
                accounts = parsedAccounts,
                preferredAccountId = prefsJson?.optLong("lastUsedAccountId", _userPrefs.value.lastUsedAccountId)
                    ?: _userPrefs.value.lastUsedAccountId
            )

            updatePreferences { current ->
                if (prefsJson != null) {
                    val modeStr = prefsJson.optString("periodMode", current.periodMode.name)
                    val mode = runCatching { PeriodMode.valueOf(modeStr) }.getOrDefault(current.periodMode)
                    current.copy(
                        onboardingCompleted = prefsJson.optBoolean("onboardingCompleted", current.onboardingCompleted),
                        periodMode = mode,
                        customCutoffDay = prefsJson.optInt("customCutoffDay", current.customCutoffDay).coerceIn(1, 28),
                        baseMonthlyIncome = parsedBaseIncome,
                        deductCreditCardDueInAvailable = prefsJson.optBoolean(
                            "deductCreditCardDueInAvailable",
                            current.deductCreditCardDueInAvailable
                        ),
                        hideBalances = prefsJson.optBoolean("hideBalances", current.hideBalances),
                        dailyReminderEnabled = prefsJson.optBoolean("dailyReminderEnabled", current.dailyReminderEnabled),
                        dailyReminderHour = prefsJson.optInt("dailyReminderHour", current.dailyReminderHour),
                        darkModeOverride = if (prefsJson.has("darkModeOverride")) {
                            prefsJson.optNullableBoolean("darkModeOverride")
                        } else {
                            current.darkModeOverride
                        },
                        lastUsedAccountId = restoredLastAccountId,
                        isDemoLoaded = false,
                        lastBackupTimestamp = nowMillis,
                        aiFeaturesEnabled = prefsJson.optBoolean("aiFeaturesEnabled", current.aiFeaturesEnabled),
                        aiPrivacyConsentAccepted = prefsJson.optBoolean("aiPrivacyConsentAccepted", current.aiPrivacyConsentAccepted)
                    )
                } else {
                    current.copy(
                        lastUsedAccountId = restoredLastAccountId,
                        isDemoLoaded = false,
                        lastBackupTimestamp = nowMillis
                    )
                }
            }

            BackupRestoreResult.Success(
                accountsCount = parsedAccounts.size,
                transactionsCount = parsedTransactions.size
            )
        } catch (e: Exception) {
            BackupRestoreResult.Error(
                "Error al restaurar la copia de seguridad: ${e.localizedMessage ?: "formato inválido"}."
            )
        }
    }
}
