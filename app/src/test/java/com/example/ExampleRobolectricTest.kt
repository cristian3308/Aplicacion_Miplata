package com.example

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.BackupRestoreResult
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.MiPlataDatabase
import com.example.data.MiPlataRepository
import com.example.data.MiPlataTypeConverters
import com.example.data.PeriodMode
import com.example.data.RecurrenceFrequency
import com.example.data.SavingGoalEntity
import com.example.data.SystemCategoryKeys
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.data.AccountRemovalOutcome
import com.example.data.AiFinancialReport
import com.example.data.GeminiFinanceService
import com.example.data.OnboardingAccountChoice
import com.example.domain.AnalyticsCalculator
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CopFormatter
import com.example.ui.AccountWithBalance
import com.example.ui.AnalyticsRangeFilter
import com.example.ui.ChatMessageItem
import com.example.ui.NotificationHelper
import com.example.ui.ReminderScheduler
import com.example.ui.components.CopThousandSeparatorTransformation
import android.graphics.Bitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: MiPlataDatabase
    private lateinit var repository: MiPlataRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, MiPlataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MiPlataRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("MiPlata", appName)
    }

    @Test
    fun `cargar y borrar demo mantiene intactas cuentas saldos movimientos limites e ingreso base`() = runBlocking {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis
        val dao = db.dao()

        // 1. Crear 3 cuentas reales con saldo + categorías + ingreso base + límites reales
        dao.insertCategories(MiPlataDatabase.defaultCategories())
        val cashId = dao.insertAccount(
            AccountEntity(
                name = "Efectivo",
                type = AccountType.DEBITO,
                initialBalance = 350_000L,
                colorHex = 0xFF10B981,
                iconName = "payments",
                isDefault = true,
                isDemo = false
            )
        )
        val nuId = dao.insertAccount(
            AccountEntity(
                name = "Nu",
                type = AccountType.DEBITO,
                initialBalance = 1_800_000L,
                colorHex = 0xFF8B5CF6,
                iconName = "account_balance",
                isDefault = true,
                isDemo = false
            )
        )
        val nequiId = dao.insertAccount(
            AccountEntity(
                name = "Nequi",
                type = AccountType.DEBITO,
                initialBalance = 650_000L,
                colorHex = 0xFFEC4899,
                iconName = "smartphone",
                isDefault = true,
                isDemo = false
            )
        )

        repository.updatePreferences {
            it.copy(
                onboardingCompleted = true,
                periodMode = PeriodMode.MENSUAL,
                customCutoffDay = 1,
                baseMonthlyIncome = 4_200_000L
            )
        }

        val categoriesInitial = dao.getCategoriesOnce()
        val catComida = categoriesInitial.first { it.systemKey == SystemCategoryKeys.COMIDA }
        val catTransporte = categoriesInitial.first { it.systemKey == SystemCategoryKeys.TRANSPORTE }
        val catSueldo = categoriesInitial.first { it.systemKey == SystemCategoryKeys.SUELDO }

        // Configurar límite de presupuesto real del usuario en Comida ($550.000) y Transporte ($220.000)
        repository.updateCategoryLimitAndGroup(catComida, 550_000L, catComida.groupName)
        repository.updateCategoryLimitAndGroup(catTransporte, 220_000L, catTransporte.groupName)

        // Insertar 5 movimientos reales del usuario
        val realMovementsToInsert = listOf(
            TransactionEntity(
                type = TransactionType.INGRESO,
                amount = 2_100_000L,
                timestamp = nowMillis - 5 * 86_400_000L,
                accountId = nuId,
                categoryId = catSueldo.id,
                note = "Pago quincena real",
                status = TransactionStatus.PAGADO,
                isDemo = false
            ),
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 45_000L,
                timestamp = nowMillis - 4 * 86_400_000L,
                accountId = cashId,
                categoryId = catComida.id,
                note = "Almuerzo real",
                status = TransactionStatus.PAGADO,
                isDemo = false
            ),
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 18_000L,
                timestamp = nowMillis - 3 * 86_400_000L,
                accountId = nequiId,
                categoryId = catTransporte.id,
                note = "Taxi real",
                status = TransactionStatus.PAGADO,
                isDemo = false
            ),
            TransactionEntity(
                type = TransactionType.TRANSFERENCIA,
                amount = 200_000L,
                timestamp = nowMillis - 2 * 86_400_000L,
                accountId = nuId,
                toAccountId = nequiId,
                note = "Recarga Nequi real",
                status = TransactionStatus.PAGADO,
                isDemo = false
            ),
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 80_000L,
                timestamp = nowMillis - 1 * 86_400_000L,
                accountId = nuId,
                categoryId = catComida.id,
                note = "Cena real",
                status = TransactionStatus.PAGADO,
                isDemo = false
            )
        )
        realMovementsToInsert.forEach { dao.insertTransaction(it) }

        // 2. Capturar estado exacto ANTES de cargar la demo
        val accountsBefore = dao.observeAccounts().first().sortedBy { it.id }
        val transactionsBefore = dao.observeTransactions().first().sortedBy { it.id }
        val categoriesBefore = dao.observeCategories().first().sortedBy { it.id }
        val goalsBefore = dao.observeSavingGoals().first().sortedBy { it.id }
        val prefsBefore = repository.userPreferences.first()

        val balancesBefore: Map<Long, Long> = accountsBefore.associate { acc ->
            acc.id to AvailableToSpendCalculator.computeAccountBalance(acc, transactionsBefore, includePending = false)
        }

        assertEquals(3, accountsBefore.size)
        assertEquals(5, transactionsBefore.size)
        assertEquals(4_200_000L, prefsBefore.baseMonthlyIncome)
        assertFalse(prefsBefore.isDemoLoaded)

        // 3. Cargar Demo
        repository.loadThreeMonthDemoData(nowMillis = nowMillis)

        val accountsDuringDemo = dao.observeAccounts().first()
        val demoAccounts = accountsDuringDemo.filter { it.isDemo }
        val realAccountsDuringDemo = accountsDuringDemo.filter { !it.isDemo }.sortedBy { it.id }

        // Las cuentas reales siguen intactas y las cuentas demo tienen nombres terminados en " (demo)"
        assertEquals(accountsBefore, realAccountsDuringDemo)
        assertTrue(demoAccounts.isNotEmpty())
        assertTrue(demoAccounts.all { it.name.endsWith(" (demo)") && it.isDemo })

        // El ingreso base real y los límites reales de las categorías del usuario NO fueron pisados
        val prefsDuringDemo = repository.userPreferences.first()
        assertTrue(prefsDuringDemo.isDemoLoaded)
        assertEquals(4_200_000L, prefsDuringDemo.baseMonthlyIncome)

        val categoriesDuringDemo = dao.observeCategories().first().sortedBy { it.id }
        assertEquals(categoriesBefore, categoriesDuringDemo)

        // 4. Borrar Demo
        repository.clearDemoDataOnly()

        // 5. Capturar estado DESPUÉS de borrar la demo y comparar
        val accountsAfter = dao.observeAccounts().first().sortedBy { it.id }
        val transactionsAfter = dao.observeTransactions().first().sortedBy { it.id }
        val categoriesAfter = dao.observeCategories().first().sortedBy { it.id }
        val goalsAfter = dao.observeSavingGoals().first().sortedBy { it.id }
        val prefsAfter = repository.userPreferences.first()

        val balancesAfter: Map<Long, Long> = accountsAfter.associate { acc ->
            acc.id to AvailableToSpendCalculator.computeAccountBalance(acc, transactionsAfter, includePending = false)
        }

        // Criterio 1: Cuentas, saldos, movimientos, límites e ingreso base quedan exactamente iguales que antes
        assertEquals(accountsBefore, accountsAfter)
        assertEquals(balancesBefore, balancesAfter)
        assertEquals(transactionsBefore, transactionsAfter)
        assertEquals(categoriesBefore, categoriesAfter)
        assertEquals(goalsBefore, goalsAfter)
        assertEquals(prefsBefore, prefsAfter)

        // Criterio 2: Tras borrar la demo no queda ninguna fila con isDemo = 1 en ninguna tabla
        assertFalse(accountsAfter.any { it.isDemo })
        assertFalse(transactionsAfter.any { it.isDemo })
        assertFalse(goalsAfter.any { it.isDemo })
    }

    @Test
    fun `backup v3 ida y vuelta deja todas las tablas identicas excluyendo PIN y demo`() = runBlocking {
        val nowMillis = 1_763_300_000_000L
        val dao = db.dao()

        // Sembrar todas las tablas con todos los campos personalizados
        val categories = listOf(
            CategoryEntity(
                id = 1L,
                name = "Comida",
                iconName = "restaurant",
                colorHex = 0xFFF97316,
                type = CategoryType.GASTO,
                groupName = "Estilo de vida",
                monthlyLimit = 600_000L,
                systemKey = SystemCategoryKeys.COMIDA
            ),
            CategoryEntity(
                id = 2L,
                name = "Sueldo",
                iconName = "work",
                colorHex = 0xFF10B981,
                type = CategoryType.INGRESO,
                groupName = "Ingresos",
                monthlyLimit = 0L,
                systemKey = SystemCategoryKeys.SUELDO
            )
        )
        dao.insertCategories(categories)

        val accounts = listOf(
            AccountEntity(
                id = 10L,
                name = "Nu Débito",
                type = AccountType.DEBITO,
                initialBalance = 1_500_000L,
                colorHex = 0xFF8B5CF6,
                iconName = "account_balance",
                isDefault = true,
                includeInAvailableFilter = true,
                isDemo = false
            ),
            AccountEntity(
                id = 20L,
                name = "Tarjeta Nu",
                type = AccountType.TARJETA_CREDITO,
                initialBalance = 0L,
                colorHex = 0xFFA855F7,
                iconName = "credit_card",
                isDefault = false,
                creditLimit = 4_000_000L,
                billingCutoffDay = 22,
                paymentDueDay = 7,
                lastFourDigits = "9876",
                includeInAvailableFilter = false,
                isDemo = false
            ),
            AccountEntity(
                id = 30L,
                name = "Prestado a Laura",
                type = AccountType.PRESTADO,
                initialBalance = 0L,
                colorHex = 0xFF14B8A6,
                iconName = "handshake",
                personName = "Laura Gómez",
                originalAmount = 500_000L,
                receivedOrPaidAmount = 150_000L,
                monthlyInstallment = 100_000L,
                includeInAvailableFilter = false,
                isDemo = false
            )
        )
        dao.insertAccounts(accounts)
        // Añadir también una cuenta demo para verificar que NO se exporta
        dao.insertAccount(
            AccountEntity(
                id = 99L,
                name = "Efectivo (demo)",
                type = AccountType.DEBITO,
                initialBalance = 999_999L,
                colorHex = 0xFF10B981,
                iconName = "payments",
                isDemo = true
            )
        )

        val goals = listOf(
            SavingGoalEntity(
                id = 7L,
                name = "Fondo de emergencia",
                targetAmount = 5_000_000L,
                savedAmount = 1_200_000L,
                deadlineTimestamp = nowMillis + 90 * 86_400_000L,
                monthlyTargetContribution = 400_000L,
                colorHex = 0xFF38BDF8,
                iconName = "savings",
                isDemo = false
            )
        )
        dao.insertSavingGoals(goals)

        val transactions = listOf(
            TransactionEntity(
                id = 100L,
                type = TransactionType.GASTO,
                amount = 85_000L,
                timestamp = nowMillis - 86_400_000L,
                accountId = 10L,
                toAccountId = null,
                categoryId = 1L,
                note = "Mercado semanal",
                receiptUri = "content://miplata/recibo_100.jpg",
                isRecurring = true,
                isInitialBalance = false,
                isDemo = false,
                linkedGoalId = 7L,
                status = TransactionStatus.PAGADO,
                recurrence = RecurrenceFrequency.PERSONALIZADA,
                customRecurrenceDays = 14,
                installmentNumber = 1,
                totalInstallments = 1,
                parentRecurringId = 50L,
                hasGeneratedNextOccurrence = true
            ),
            TransactionEntity(
                id = 101L,
                type = TransactionType.PAGO_TARJETA,
                amount = 300_000L,
                timestamp = nowMillis,
                accountId = 10L,
                toAccountId = 20L,
                categoryId = null,
                note = "Abono tarjeta",
                status = TransactionStatus.PAGADO,
                isDemo = false
            )
        )
        dao.insertTransactions(transactions)

        repository.configureOrUpdatePin(
            enablePin = true,
            newPin = "5319",
            confirmNewPin = "5319",
            nowMillis = nowMillis
        )
        repository.updatePreferences {
            it.copy(
                onboardingCompleted = true,
                periodMode = PeriodMode.PERSONALIZADO,
                customCutoffDay = 15,
                baseMonthlyIncome = 5_400_000L,
                deductCreditCardDueInAvailable = false,
                hideBalances = true,
                pinLockEnabled = true,
                dailyReminderEnabled = false,
                dailyReminderHour = 19,
                darkModeOverride = false,
                lastUsedAccountId = 10L,
                isDemoLoaded = true
            )
        }

        // Exportar JSON v3
        val exportedJson = repository.createBackupJson(nowMillis = nowMillis)
        assertFalse("El JSON nunca debe incluir el PIN ni su hash", exportedJson.contains("5319") || exportedJson.contains("pinCode") || exportedJson.contains("pin_hash"))
        assertFalse("El JSON nunca debe incluir filas de demo", exportedJson.contains("Efectivo (demo)"))

        // 1) Cambiar un par de datos, restaurar y verificar que vuelve al estado exportado
        dao.insertAccount(
            AccountEntity(
                id = 555L,
                name = "Cuenta Temporal",
                type = AccountType.DEBITO,
                initialBalance = 10_000L,
                colorHex = 0xFF000000,
                iconName = "payments"
            )
        )
        dao.clearAllTransactions()

        val restoreAfterModify = repository.restoreFromBackupJson(exportedJson, nowMillis = nowMillis + 2_000L)
        assertTrue(restoreAfterModify is BackupRestoreResult.Success)
        assertEquals(accounts, dao.getAccountsOnce().sortedBy { it.id })
        assertEquals(transactions, dao.getTransactionsOnce().sortedBy { it.id })

        // 2) Vaciar las tablas (base vacía), restaurar y verificar que quedan idénticas
        dao.clearAllTransactions()
        dao.clearAllSavingGoals()
        dao.clearAllAccounts()
        dao.clearAllCategories()

        val restoreResult = repository.restoreFromBackupJson(exportedJson, nowMillis = nowMillis + 5_000L)
        assertTrue(restoreResult is BackupRestoreResult.Success)
        val success = restoreResult as BackupRestoreResult.Success
        assertEquals(3, success.accountsCount)
        assertEquals(2, success.transactionsCount)

        // Verificar que las tablas quedan idénticas al estado real exportado
        assertEquals(accounts, dao.getAccountsOnce().sortedBy { it.id })
        assertEquals(categories, dao.getCategoriesOnce().sortedBy { it.id })
        assertEquals(transactions, dao.getTransactionsOnce().sortedBy { it.id })
        assertEquals(goals, dao.getSavingGoalsOnce().sortedBy { it.id })

        val restoredPrefs = repository.userPreferences.first()
        assertEquals(PeriodMode.PERSONALIZADO, restoredPrefs.periodMode)
        assertEquals(15, restoredPrefs.customCutoffDay)
        assertEquals(5_400_000L, restoredPrefs.baseMonthlyIncome)
        assertEquals(false, restoredPrefs.deductCreditCardDueInAvailable)
        assertEquals(true, restoredPrefs.hideBalances)
        assertEquals(false, restoredPrefs.dailyReminderEnabled)
        assertEquals(19, restoredPrefs.dailyReminderHour)
        assertEquals(false, restoredPrefs.darkModeOverride)
        assertFalse(restoredPrefs.isDemoLoaded)

        // 3) Intentar restaurar un archivo de texto cualquiera y confirmar que no se pierde nada
        val invalidTextResult = repository.restoreFromBackupJson("Este es un archivo de texto cualquiera, no es JSON.")
        assertTrue(invalidTextResult is BackupRestoreResult.Error)
        assertEquals(accounts, dao.getAccountsOnce().sortedBy { it.id })
        assertEquals(categories, dao.getCategoriesOnce().sortedBy { it.id })
        assertEquals(transactions, dao.getTransactionsOnce().sortedBy { it.id })
        assertEquals(goals, dao.getSavingGoalsOnce().sortedBy { it.id })
    }

    @Test
    fun `restaurar archivo corrupto vacio de otra app o con cuenta inexistente no cambia ni una fila`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val firstAccId = dao.getAccountsOnce().first().id
        val firstCatId = dao.getCategoriesOnce().first().id
        val txId = dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 50_000L,
                timestamp = 1_763_300_000_000L,
                accountId = firstAccId,
                categoryId = firstCatId,
                note = "Movimiento seguro"
            )
        )
        assertTrue(txId > 0L)

        val accountsBefore = dao.getAccountsOnce()
        val categoriesBefore = dao.getCategoriesOnce()
        val transactionsBefore = dao.getTransactionsOnce()

        // 1. Archivo vacío
        val rEmpty = repository.restoreFromBackupJson("   ")
        assertTrue(rEmpty is BackupRestoreResult.Error)

        // 2. Archivo de texto cualquiera / corrupto
        val rCorrupt = repository.restoreFromBackupJson("Hola, esto es un archivo de texto cualquiera y no es JSON.")
        assertTrue(rCorrupt is BackupRestoreResult.Error)

        // 3. Archivo de otra app
        val rOtherApp = repository.restoreFromBackupJson("""{"app":"OtraApp","version":3}""")
        assertTrue(rOtherApp is BackupRestoreResult.Error)

        // 4. Archivo con movimiento que apunta a una cuenta inexistente (accountId = 999)
        val rMissingAccount = repository.restoreFromBackupJson(
            """
            {
              "app": "MiPlata",
              "version": 3,
              "accounts": [
                {"id": 1, "name": "Efectivo", "type": "DEBITO", "initialBalance": 100000}
              ],
              "categories": [
                {"id": 1, "name": "Comida", "type": "GASTO"}
              ],
              "transactions": [
                {"id": 10, "type": "GASTO", "amount": 25000, "accountId": 999, "categoryId": 1}
              ]
            }
            """.trimIndent()
        )
        assertTrue(rMissingAccount is BackupRestoreResult.Error)

        // 5. Archivo con monto negativo
        val rNegativeAmount = repository.restoreFromBackupJson(
            """
            {
              "app": "MiPlata",
              "version": 3,
              "accounts": [
                {"id": 1, "name": "Efectivo", "type": "DEBITO", "initialBalance": 100000}
              ],
              "categories": [
                {"id": 1, "name": "Comida", "type": "GASTO"}
              ],
              "transactions": [
                {"id": 10, "type": "GASTO", "amount": -25000, "accountId": 1, "categoryId": 1}
              ]
            }
            """.trimIndent()
        )
        assertTrue(rNegativeAmount is BackupRestoreResult.Error)

        // Verificar que NI UNA SOLA FILA cambió tras todos los intentos fallidos
        assertEquals(accountsBefore, dao.getAccountsOnce())
        assertEquals(categoriesBefore, dao.getCategoriesOnce())
        assertEquals(transactionsBefore, dao.getTransactionsOnce())
    }

    @Test
    fun `archivo version 2 antiguo se restaura sin error conservando categorias actuales`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val categoriesBefore = dao.getCategoriesOnce()
        val firstCatId = categoriesBefore.first().id

        val v2Json = """
            {
              "app": "MiPlata",
              "version": 2,
              "exportedAt": 1763000000000,
              "accounts": [
                {
                  "id": 1,
                  "name": "Nequi v2",
                  "type": "DEBITO",
                  "initialBalance": 420000,
                  "colorHex": 15485081,
                  "iconName": "smartphone",
                  "isDefault": true
                }
              ],
              "transactions": [
                {
                  "id": 7,
                  "type": "GASTO",
                  "status": "PAGADO",
                  "recurrence": "NINGUNA",
                  "amount": 32000,
                  "timestamp": 1763000000000,
                  "accountId": 1,
                  "categoryId": $firstCatId,
                  "note": "Almuerzo v2"
                }
              ]
            }
        """.trimIndent()

        val result = repository.restoreFromBackupJson(v2Json)
        assertTrue(result is BackupRestoreResult.Success)
        val success = result as BackupRestoreResult.Success
        assertEquals(1, success.accountsCount)
        assertEquals(1, success.transactionsCount)

        // Categorías actuales conservadas intactas
        assertEquals(categoriesBefore, dao.getCategoriesOnce())
        val restoredTx = dao.getTransactionsOnce().single()
        assertEquals("Almuerzo v2", restoredTx.note)
        assertEquals(30, restoredTx.customRecurrenceDays)
        assertEquals(1, restoredTx.installmentNumber)
        assertEquals(1, restoredTx.totalInstallments)
    }

    @Test
    fun `exportar CSV empieza con BOM UTF8 escapa comillas y protege formulas`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val firstAccId = dao.getAccountsOnce().first().id
        val firstCatId = dao.getCategoriesOnce().first().id
        dao.insertTransaction(
            TransactionEntity(
                id = 1L,
                type = TransactionType.GASTO,
                amount = 15_000L,
                timestamp = 1_763_300_000_000L,
                accountId = firstAccId,
                categoryId = firstCatId,
                note = "=SUM(A1:A2) \"especial\""
            )
        )

        val csv = repository.exportTransactionsToCsv(nowMillis = 1_763_300_000_000L)
        assertTrue("El CSV debe empezar con BOM UTF-8", csv.startsWith("\uFEFF"))
        assertTrue(
            "El CSV debe anteponer apóstrofo a '=' y duplicar comillas",
            csv.contains("\"'=SUM(A1:A2) \"\"especial\"\"\"")
        )
    }

    @Test
    fun `editar solo la nota de un movimiento de hace 10 dias conserva su fecha y hora y sus demas campos`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val accounts = dao.getAccountsOnce()
        val categories = dao.getCategoriesOnce()
        val debitAccId = accounts.first { it.type == AccountType.DEBITO }.id
        val cardAccId = accounts.firstOrNull { it.type == AccountType.TARJETA_CREDITO }?.id
            ?: dao.insertAccount(
                AccountEntity(
                    name = "Tarjeta Test",
                    type = AccountType.TARJETA_CREDITO,
                    colorHex = 0xFFA855F7,
                    iconName = "credit_card",
                    creditLimit = 3_000_000L
                )
            )
        val comidaCatId = categories.first { it.systemKey == SystemCategoryKeys.COMIDA }.id
        val ropaCatId = categories.first { it.systemKey == SystemCategoryKeys.ROPA }.id

        val tenDaysAgoCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 6, 14, 37, 22)
            set(Calendar.MILLISECOND, 450)
        }
        val tenDaysAgoMillis = tenDaysAgoCal.timeInMillis
        val nowMillis = tenDaysAgoMillis + 10 * 86_400_000L

        val txId = repository.saveOrEditMovementFromForm(
            type = TransactionType.GASTO,
            amount = 42_000L,
            accountId = debitAccId,
            categoryId = comidaCatId,
            note = "Almuerzo semana pasada",
            timestamp = tenDaysAgoMillis,
            status = TransactionStatus.PAGADO,
            nowMillis = nowMillis
        )

        // Editar únicamente la nota 10 días después sin pasar timestamp (y pasando el mismo día desde DatePicker UTC)
        repository.saveOrEditMovementFromForm(
            id = txId,
            note = "Almuerzo semana pasada con clientes",
            nowMillis = nowMillis
        )

        val edited = dao.getTransactionById(txId)!!
        assertEquals("Almuerzo semana pasada con clientes", edited.note)
        assertEquals(tenDaysAgoMillis, edited.timestamp)
        assertEquals(42_000L, edited.amount)

        // Verificar también que elegir la misma fecha en el DatePicker (UTC) conserva exactamente la hora 14:37:22.450
        val utcDateMillis = MiPlataRepository.localTimestampToUtcDateMillis(tenDaysAgoMillis)
        val mergedMillis = MiPlataRepository.mergeUtcDateSelectionWithLocalTime(utcDateMillis, tenDaysAgoMillis)
        assertEquals(tenDaysAgoMillis, mergedMillis)

        // Y que editar la cuota 3/6 de una compra a cuotas no permite cambiar tipo ni número de cuotas
        val installmentId = dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.COMPRA_CUOTAS,
                amount = 150_000L,
                timestamp = tenDaysAgoMillis,
                dueTimestamp = tenDaysAgoMillis,
                accountId = cardAccId,
                categoryId = ropaCatId,
                note = "Chaqueta (Cuota 3/6)",
                status = TransactionStatus.PENDIENTE,
                installmentNumber = 3,
                totalInstallments = 6
            )
        )
        repository.saveOrEditMovementFromForm(
            id = installmentId,
            type = TransactionType.GASTO, // intento de cambiar tipo (debe ignorarse)
            totalInstallments = 1,        // intento de cambiar totalInstallments (debe ignorarse)
            note = "Chaqueta invierno (Cuota 3/6)"
        )
        val editedInst = dao.getTransactionById(installmentId)!!
        assertEquals(TransactionType.COMPRA_CUOTAS, editedInst.type)
        assertEquals(3, editedInst.installmentNumber)
        assertEquals(6, editedInst.totalInstallments)
        assertEquals("Chaqueta invierno (Cuota 3/6)", editedInst.note)
        assertEquals(tenDaysAgoMillis, editedInst.timestamp)
    }

    @Test
    fun `editar un recurrente pagado no crea ningun movimiento nuevo`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val debitAccId = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }.id
        val ocioCatId = dao.getCategoriesOnce().first { it.systemKey == SystemCategoryKeys.OCIO }.id

        val baseTime = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Crear un movimiento recurrente PAGADO (genera su única ocurrencia pendiente siguiente -> total 2 movimientos)
        val paidRecId = repository.saveOrEditMovementFromForm(
            type = TransactionType.GASTO,
            amount = 35_000L,
            accountId = debitAccId,
            categoryId = ocioCatId,
            note = "Spotify Familiar",
            timestamp = baseTime,
            status = TransactionStatus.PAGADO,
            recurrence = RecurrenceFrequency.MENSUAL,
            isRecurring = true,
            nowMillis = baseTime
        )
        assertEquals(2, dao.getTransactionsOnce().size)

        // Editar el movimiento recurrente ya pagado (nota y monto)
        repository.saveOrEditMovementFromForm(
            id = paidRecId,
            amount = 38_000L,
            note = "Spotify Familiar HD",
            nowMillis = baseTime + 86_400_000L
        )

        val allAfterEdit = dao.getTransactionsOnce()
        assertEquals("Editar un recurrente pagado no debe crear ningún movimiento nuevo", 2, allAfterEdit.size)
        val updatedPaid = dao.getTransactionById(paidRecId)!!
        assertEquals(38_000L, updatedPaid.amount)
        assertEquals("Spotify Familiar HD", updatedPaid.note)
        assertEquals(baseTime, updatedPaid.timestamp)
        assertTrue(updatedPaid.hasGeneratedNextOccurrence)
    }

    @Test
    fun `pago mensual que vence el dia 5 y se paga el 20 genera el siguiente para el dia 5 del mes siguiente`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val debitAccId = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }.id
        val serviciosCatId = dao.getCategoriesOnce().first { it.systemKey == SystemCategoryKeys.SERVICIOS }.id

        val dueNov5 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val paidNov20 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 16, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val pendingId = repository.saveOrEditMovementFromForm(
            type = TransactionType.PAGO_PROGRAMADO,
            amount = 120_000L,
            accountId = debitAccId,
            categoryId = serviciosCatId,
            note = "Internet Hogar",
            timestamp = dueNov5,
            status = TransactionStatus.PENDIENTE,
            recurrence = RecurrenceFrequency.MENSUAL,
            isRecurring = true,
            nowMillis = dueNov5
        )

        val pendingTx = dao.getTransactionById(pendingId)!!
        assertEquals(dueNov5, pendingTx.dueTimestamp)
        assertEquals(5, pendingTx.recurrenceAnchorDay)

        // Pagar tarde el día 20
        repository.markTransactionAsPaid(pendingTx, nowMillis = paidNov20)

        val allTxs = dao.getTransactionsOnce()
        assertEquals(2, allTxs.size)

        val paidTx = dao.getTransactionById(pendingId)!!
        assertEquals(TransactionStatus.PAGADO, paidTx.status)
        assertEquals("timestamp pasa a ser la fecha real de pago (día 20)", paidNov20, paidTx.timestamp)
        assertEquals("dueTimestamp conserva el vencimiento original (día 5)", dueNov5, paidTx.dueTimestamp)

        val nextPending = allTxs.first { it.id != pendingId }
        assertEquals(TransactionStatus.PENDIENTE, nextPending.status)
        val nextCal = Calendar.getInstance().apply { timeInMillis = nextPending.timestamp }
        assertEquals(2026, nextCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, nextCal.get(Calendar.MONTH))
        assertEquals(5, nextCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(nextPending.timestamp, nextPending.dueTimestamp)
        assertEquals(5, nextPending.recurrenceAnchorDay)
    }

    @Test
    fun `mensual con dia ancla 31 pasa de enero 31 a febrero 28 o 29 y luego a marzo 31`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val debitAccId = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }.id
        val arriendoCatId = dao.getCategoriesOnce().first { it.systemKey == SystemCategoryKeys.ARRIENDO }.id

        // 1. Año normal (2026): Enero 31 -> Febrero 28 -> Marzo 31
        val jan31_2026 = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 31, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val janTxId = repository.saveOrEditMovementFromForm(
            type = TransactionType.PAGO_PROGRAMADO,
            amount = 900_000L,
            accountId = debitAccId,
            categoryId = arriendoCatId,
            note = "Arriendo fin de mes",
            timestamp = jan31_2026,
            status = TransactionStatus.PAGADO,
            recurrence = RecurrenceFrequency.MENSUAL,
            isRecurring = true,
            nowMillis = jan31_2026
        )

        val febPending = dao.getTransactionsOnce().first { it.id != janTxId }
        val febCal = Calendar.getInstance().apply { timeInMillis = febPending.timestamp }
        assertEquals(2026, febCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, febCal.get(Calendar.MONTH))
        assertEquals(28, febCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(31, febPending.recurrenceAnchorDay)

        // Marcar febrero 28 como pagado -> debe generar marzo 31
        repository.markTransactionAsPaid(febPending, nowMillis = febPending.timestamp)
        val marPending = dao.getTransactionsOnce().first { it.id != janTxId && it.id != febPending.id }
        val marCal = Calendar.getInstance().apply { timeInMillis = marPending.timestamp }
        assertEquals(2026, marCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, marCal.get(Calendar.MONTH))
        assertEquals(31, marCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(31, marPending.recurrenceAnchorDay)

        // 2. Año bisiesto (2028): Enero 31 -> Febrero 29 -> Marzo 31
        val jan31_2028 = Calendar.getInstance().apply {
            set(2028, Calendar.JANUARY, 31, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val feb29_2028 = MiPlataRepository.computeNextOccurrenceTimestamp(
            currentTimestamp = jan31_2028,
            recurrence = RecurrenceFrequency.MENSUAL,
            anchorDay = 31
        )
        val febLeapCal = Calendar.getInstance().apply { timeInMillis = feb29_2028 }
        assertEquals(Calendar.FEBRUARY, febLeapCal.get(Calendar.MONTH))
        assertEquals(29, febLeapCal.get(Calendar.DAY_OF_MONTH))

        val mar31_2028 = MiPlataRepository.computeNextOccurrenceTimestamp(
            currentTimestamp = feb29_2028,
            recurrence = RecurrenceFrequency.MENSUAL,
            anchorDay = 31
        )
        val marLeapCal = Calendar.getInstance().apply { timeInMillis = mar31_2028 }
        assertEquals(Calendar.MARCH, marLeapCal.get(Calendar.MONTH))
        assertEquals(31, marLeapCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `borrar un aporte a una meta y deshacer deja savedAmount igual que al principio`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val nowMillis = 1_763_300_000_000L
        val debitAccId = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }.id

        val goalId = repository.saveSavingGoal(
            SavingGoalEntity(
                name = "Viaje al Eje Cafetero",
                targetAmount = 2_000_000L,
                savedAmount = 500_000L,
                deadlineTimestamp = nowMillis + 60 * 86_400_000L,
                monthlyTargetContribution = 250_000L
            )
        )
        val initialGoal = dao.getSavingGoalById(goalId)!!
        repository.contributeToSavingGoal(initialGoal, amount = 200_000L, accountId = debitAccId, nowMillis = nowMillis)

        val goalAfterContribution = dao.getSavingGoalById(goalId)!!
        assertEquals(700_000L, goalAfterContribution.savedAmount)

        val contributionTx = dao.getTransactionsOnce().first { it.linkedGoalId == goalId }

        // Borrar el movimiento de aporte -> revierte savedAmount a 500.000
        repository.deleteTransaction(contributionTx)
        val goalAfterDelete = dao.getSavingGoalById(goalId)!!
        assertEquals(500_000L, goalAfterDelete.savedAmount)
        assertTrue(dao.getTransactionsOnce().none { it.id == contributionTx.id })

        // Deshacer el borrado -> vuelve a dejar savedAmount en 700.000 y restaura el movimiento idéntico
        repository.restoreDeletedTransaction(contributionTx)
        val goalAfterUndo = dao.getSavingGoalById(goalId)!!
        assertEquals(700_000L, goalAfterUndo.savedAmount)
        assertEquals(contributionTx, dao.getTransactionById(contributionTx.id))
    }

    @Test
    fun `prestamo de 4800000 con 2100000 ya pagados tras abonar 320000 deja pendiente 2380000 y baja debito 320000`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val nowMillis = 1_763_300_000_000L

        val debitAccount = AccountEntity(
            id = 1L,
            name = "Efectivo",
            type = AccountType.DEBITO,
            initialBalance = 1_000_000L,
            colorHex = 0xFF10B981,
            iconName = "payments",
            isDefault = true
        )
        dao.insertAccount(debitAccount)

        val loanId = dao.insertAccount(
            AccountEntity(
                id = 0L,
                name = "Crédito Moto",
                type = AccountType.PRESTAMO,
                initialBalance = 2_700_000L,
                colorHex = 0xFFF59E0B,
                iconName = "credit_card",
                originalAmount = 4_800_000L,
                receivedOrPaidAmount = 2_100_000L,
                monthlyInstallment = 320_000L,
                includeInAvailableFilter = false
            )
        )
        val loanAccount = dao.getAccountsOnce().first { it.id == loanId }

        val errorMsg = repository.registerLoanOrLentPayment(
            targetAccount = loanAccount,
            amount = 320_000L,
            debitAccountId = debitAccount.id,
            nowMillis = nowMillis
        )
        assertEquals(null, errorMsg)

        val updatedLoan = dao.getAccountsOnce().first { it.id == loanId }
        val updatedDebit = dao.getAccountsOnce().first { it.id == debitAccount.id }
        val allTxs = dao.getTransactionsOnce()

        assertEquals(2_100_000L, updatedLoan.receivedOrPaidAmount)

        val loanPending = AvailableToSpendCalculator.computeAccountBalance(updatedLoan, allTxs)
        assertEquals(2_380_000L, loanPending)

        val debitBalance = AvailableToSpendCalculator.computeAccountBalance(updatedDebit, allTxs)
        assertEquals(680_000L, debitBalance)

        val exceedError = repository.registerLoanOrLentPayment(
            targetAccount = updatedLoan,
            amount = 2_500_000L,
            debitAccountId = debitAccount.id,
            nowMillis = nowMillis + 1_000L
        )
        assertEquals("El abono supera lo pendiente ($2.380.000)", exceedError)
        assertEquals(1, dao.getTransactionsOnce().size)
    }

    @Test
    fun `prestado de 600000 con 250000 recibidos al recibir 100000 deja 250000 por cobrar sube cuenta 100000 y borrar revierte`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val nowMillis = 1_763_300_000_000L

        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nequi",
            type = AccountType.DEBITO,
            initialBalance = 500_000L,
            colorHex = 0xFFEC4899,
            iconName = "smartphone",
            isDefault = true
        )
        dao.insertAccount(debitAccount)

        val lentId = dao.insertAccount(
            AccountEntity(
                id = 0L,
                name = "Carlos Ramírez",
                type = AccountType.PRESTADO,
                initialBalance = 350_000L,
                colorHex = 0xFF14B8A6,
                iconName = "payments",
                personName = "Carlos Ramírez",
                originalAmount = 600_000L,
                receivedOrPaidAmount = 250_000L,
                includeInAvailableFilter = false
            )
        )
        val lentAccount = dao.getAccountsOnce().first { it.id == lentId }

        val err = repository.registerLoanOrLentPayment(
            targetAccount = lentAccount,
            amount = 100_000L,
            debitAccountId = debitAccount.id,
            nowMillis = nowMillis
        )
        assertEquals(null, err)

        val txsAfterAbono = dao.getTransactionsOnce()
        val updatedLent = dao.getAccountsOnce().first { it.id == lentId }
        val updatedDebit = dao.getAccountsOnce().first { it.id == debitAccount.id }

        assertEquals(250_000L, updatedLent.receivedOrPaidAmount)
        assertEquals(250_000L, AvailableToSpendCalculator.computeAccountBalance(updatedLent, txsAfterAbono))
        assertEquals(600_000L, AvailableToSpendCalculator.computeAccountBalance(updatedDebit, txsAfterAbono))

        val abonoTx = txsAfterAbono.single()
        repository.deleteTransaction(abonoTx)

        val txsAfterDelete = dao.getTransactionsOnce()
        assertEquals(350_000L, AvailableToSpendCalculator.computeAccountBalance(updatedLent, txsAfterDelete))
        assertEquals(500_000L, AvailableToSpendCalculator.computeAccountBalance(updatedDebit, txsAfterDelete))
    }

    @Test
    fun `migracion 4 a 5 corrige base con abono contado dos veces en PRESTAMO y PRESTADO sin bajar de cero`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val debitAccId = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }.id
        val nowMillis = 1_763_300_000_000L

        val loanId = dao.insertAccount(
            AccountEntity(
                id = 0L,
                name = "Crédito Vehículo",
                type = AccountType.PRESTAMO,
                initialBalance = 2_700_000L,
                colorHex = 0xFFF59E0B,
                iconName = "credit_card",
                originalAmount = 4_800_000L,
                receivedOrPaidAmount = 2_420_000L,
                monthlyInstallment = 320_000L,
                includeInAvailableFilter = false
            )
        )
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.TRANSFERENCIA,
                amount = 320_000L,
                timestamp = nowMillis,
                dueTimestamp = nowMillis,
                accountId = debitAccId,
                toAccountId = loanId,
                note = "Abono a préstamo: Crédito Vehículo",
                status = TransactionStatus.PAGADO
            )
        )

        val lentId = dao.insertAccount(
            AccountEntity(
                id = 0L,
                name = "Préstamo a Ana",
                type = AccountType.PRESTADO,
                initialBalance = 350_000L,
                colorHex = 0xFF14B8A6,
                iconName = "payments",
                personName = "Ana",
                originalAmount = 600_000L,
                receivedOrPaidAmount = 350_000L,
                includeInAvailableFilter = false
            )
        )
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.TRANSFERENCIA,
                amount = 100_000L,
                timestamp = nowMillis,
                dueTimestamp = nowMillis,
                accountId = lentId,
                toAccountId = debitAccId,
                note = "Abono recibido de Ana",
                status = TransactionStatus.PAGADO
            )
        )

        val zeroClampLoanId = dao.insertAccount(
            AccountEntity(
                id = 0L,
                name = "Préstamo Pequeño",
                type = AccountType.PRESTAMO,
                initialBalance = 500_000L,
                colorHex = 0xFFF59E0B,
                iconName = "credit_card",
                originalAmount = 500_000L,
                receivedOrPaidAmount = 50_000L,
                includeInAvailableFilter = false
            )
        )
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.TRANSFERENCIA,
                amount = 120_000L,
                timestamp = nowMillis,
                dueTimestamp = nowMillis,
                accountId = debitAccId,
                toAccountId = zeroClampLoanId,
                note = "Abono a préstamo: Préstamo Pequeño",
                status = TransactionStatus.PAGADO
            )
        )

        val allTxs = dao.getTransactionsOnce()
        val loanBefore = dao.getAccountsOnce().first { it.id == loanId }
        assertEquals(2_060_000L, AvailableToSpendCalculator.computeAccountBalance(loanBefore, allTxs))

        MiPlataDatabase.MIGRATION_4_5.migrate(db.openHelper.writableDatabase)

        val accountsAfter = dao.getAccountsOnce()
        val loanAfter = accountsAfter.first { it.id == loanId }
        val lentAfter = accountsAfter.first { it.id == lentId }
        val zeroClampAfter = accountsAfter.first { it.id == zeroClampLoanId }

        assertEquals(2_100_000L, loanAfter.receivedOrPaidAmount)
        assertEquals(2_380_000L, AvailableToSpendCalculator.computeAccountBalance(loanAfter, allTxs))

        assertEquals(250_000L, lentAfter.receivedOrPaidAmount)
        assertEquals(250_000L, AvailableToSpendCalculator.computeAccountBalance(lentAfter, allTxs))

        assertEquals("receivedOrPaidAmount nunca debe bajar de 0", 0L, zeroClampAfter.receivedOrPaidAmount)
    }

    @Test
    fun `el valor del widget coincide con el por dia de Inicio y respeta ocultar saldos`() = runBlocking {
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 16, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nequi",
            type = AccountType.DEBITO,
            initialBalance = 1_500_000L,
            colorHex = 0xFFEC4899,
            iconName = "smartphone"
        )
        val window = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = nowMillis
        )
        val availableResult = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(debitAccount),
            allTransactions = emptyList(),
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )

        com.example.ui.widget.MiPlataWidgetProvider.pushWidgetUpdate(
            context = context,
            availablePerDay = availableResult.availablePerDay,
            availableForPeriod = availableResult.availableForPeriod,
            periodLabel = availableResult.periodLabel,
            hideBalances = false
        )
        val cachedVisible = com.example.ui.widget.MiPlataWidgetProvider.readCachedWidgetDisplayData(context)
        assertEquals("Hoy puedes gastar", cachedVisible.title)
        assertEquals(availableResult.availablePerDay, cachedVisible.availablePerDay)
        assertEquals(CopFormatter.format(availableResult.availablePerDay), cachedVisible.mainAmountText)
        assertEquals(
            "Te quedan ${CopFormatter.format(availableResult.availableForPeriod)} para ${availableResult.periodLabel}",
            cachedVisible.periodSubtitleText
        )

        com.example.ui.widget.MiPlataWidgetProvider.pushWidgetUpdate(
            context = context,
            availablePerDay = availableResult.availablePerDay,
            availableForPeriod = availableResult.availableForPeriod,
            periodLabel = availableResult.periodLabel,
            hideBalances = true
        )
        val cachedHidden = com.example.ui.widget.MiPlataWidgetProvider.readCachedWidgetDisplayData(context)
        assertEquals("Hoy puedes gastar", cachedHidden.title)
        assertEquals("$ ••••••", cachedHidden.mainAmountText)
        assertEquals("Te quedan $ •••••• para ${availableResult.periodLabel}", cachedHidden.periodSubtitleText)
    }

    @Test
    fun `hash del PIN PBKDF2 verifica PIN correcto rechaza incorrecto o vacio migra desde texto plano y nunca guarda PIN legible en SharedPreferences`() = runBlocking {
        val prefs = context.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val emptyBeforeConfig = repository.verifyPinAttempt("", nowMillis = 1_000_000L)
        assertFalse("Un PIN vacío nunca debe desbloquear la app", emptyBeforeConfig is com.example.data.PinUnlockResult.Success)

        prefs.edit()
            .clear()
            .putBoolean("pin_lock_enabled", true)
            .putString("pin_code", "4829")
            .commit()

        val migratedRepo = MiPlataRepository(context, db)
        assertFalse("La clave en texto plano 'pin_code' debe haberse eliminado tras la migración", prefs.contains("pin_code"))
        assertTrue("Debe existir el hash PBKDF2 tras la migración", migratedRepo.hasConfiguredPinHash())

        val allPrefsDump = prefs.all.entries.joinToString(" | ") { "${it.key}=${it.value}" }
        assertFalse("En las preferencias guardadas no debe aparecer el PIN legible", allPrefsDump.contains("4829"))

        val wrongAttempt = migratedRepo.verifyPinAttempt("0000", nowMillis = 2_000_000L)
        assertTrue(wrongAttempt is com.example.data.PinUnlockResult.InvalidPin)

        val blankAttempt = migratedRepo.verifyPinAttempt("", nowMillis = 2_001_000L)
        assertTrue(blankAttempt is com.example.data.PinUnlockResult.InvalidPin)

        val rightAttempt = migratedRepo.verifyPinAttempt("4829", nowMillis = 2_002_000L)
        assertEquals(com.example.data.PinUnlockResult.Success, rightAttempt)
        assertEquals(0, migratedRepo.getFailedPinAttempts())
    }

    @Test
    fun `contador de intentos fallidos aplica bloqueo de 30s tras 5 fallos que se duplica y sobrevive a cerrar la app`() = runBlocking {
        val prefs = context.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val t0 = 10_000_000L
        val configErr = repository.configureOrUpdatePin(
            enablePin = true,
            newPin = "5931",
            confirmNewPin = "5931",
            nowMillis = t0
        )
        assertEquals(null, configErr)

        for (i in 1..4) {
            val res = repository.verifyPinAttempt("1111", nowMillis = t0 + i * 1_000L)
            assertTrue(res is com.example.data.PinUnlockResult.InvalidPin)
            assertEquals(i, (res as com.example.data.PinUnlockResult.InvalidPin).failedAttempts)
            assertEquals(0L, repository.getRemainingLockoutSeconds(t0 + i * 1_000L))
        }

        val t5 = t0 + 5_000L
        val res5 = repository.verifyPinAttempt("1111", nowMillis = t5)
        assertTrue(res5 is com.example.data.PinUnlockResult.LockedOut)
        assertEquals(30L, (res5 as com.example.data.PinUnlockResult.LockedOut).remainingSeconds)

        val restartedRepo = MiPlataRepository(context, db)
        assertEquals(20L, restartedRepo.getRemainingLockoutSeconds(t5 + 10_000L))

        val blockedEvenWithRightPin = restartedRepo.verifyPinAttempt("5931", nowMillis = t5 + 10_000L)
        assertTrue(blockedEvenWithRightPin is com.example.data.PinUnlockResult.LockedOut)

        val t6 = t5 + 31_000L
        val res6 = restartedRepo.verifyPinAttempt("2222", nowMillis = t6)
        assertTrue(res6 is com.example.data.PinUnlockResult.LockedOut)
        assertEquals(60L, (res6 as com.example.data.PinUnlockResult.LockedOut).remainingSeconds)

        val t7 = t6 + 61_000L
        val res7 = restartedRepo.verifyPinAttempt("3333", nowMillis = t7)
        assertTrue(res7 is com.example.data.PinUnlockResult.LockedOut)
        assertEquals(120L, (res7 as com.example.data.PinUnlockResult.LockedOut).remainingSeconds)

        val t8 = t7 + 121_000L
        val resSuccess = restartedRepo.verifyPinAttempt("5931", nowMillis = t8)
        assertEquals(com.example.data.PinUnlockResult.Success, resSuccess)
        assertEquals(0, restartedRepo.getFailedPinAttempts())
        assertEquals(0L, restartedRepo.getRemainingLockoutSeconds(t8))
    }

    @Test
    fun `crear cambiar o desactivar PIN exige doble escritura y PIN actual y recibo se reduce a maximo 1280px`() = runBlocking {
        val prefs = context.getSharedPreferences("mi_plata_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val now = 5_000_000L

        assertNotNull(repository.configureOrUpdatePin(enablePin = true, newPin = "123", confirmNewPin = "123", nowMillis = now))
        assertNotNull(repository.configureOrUpdatePin(enablePin = true, newPin = "7391", confirmNewPin = "7392", nowMillis = now))
        assertEquals(null, repository.configureOrUpdatePin(enablePin = true, newPin = "7391", confirmNewPin = "7391", nowMillis = now))

        assertNotNull(repository.configureOrUpdatePin(enablePin = true, currentPin = "0000", newPin = "846205", confirmNewPin = "846205", nowMillis = now))
        assertNotNull(repository.configureOrUpdatePin(enablePin = false, currentPin = "0000", nowMillis = now))

        assertEquals(null, repository.configureOrUpdatePin(enablePin = true, currentPin = "7391", newPin = "846205", confirmNewPin = "846205", nowMillis = now))
        assertEquals(com.example.data.PinUnlockResult.Success, repository.verifyPinAttempt("846205", nowMillis = now))

        assertEquals(null, repository.configureOrUpdatePin(enablePin = false, currentPin = "846205", nowMillis = now))
        assertFalse(repository.hasConfiguredPinHash())

        val bigBitmap = android.graphics.Bitmap.createBitmap(2560, 1920, android.graphics.Bitmap.Config.ARGB_8888)
        val scaled = MiPlataRepository.downscaleBitmapForReceipt(bigBitmap)
        assertEquals(1280, scaled.width)
        assertEquals(960, scaled.height)
    }

    @Test
    fun `migraciones de Room 1 a 2, 2 a 3, 3 a 4, 4 a 5 y 5 a 6 conservan 100 por ciento de presupuestos obligaciones y deudas`() = runBlocking {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 16, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // base en memoria para probar todos los saltos de migración 1->2->3->4->5->6
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Esquema original de la versión 1 de MiPlata
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `accounts` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `initialBalance` INTEGER NOT NULL,
                            `colorHex` INTEGER NOT NULL,
                            `iconName` TEXT NOT NULL,
                            `isDefault` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `categories` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `iconName` TEXT NOT NULL,
                            `colorHex` INTEGER NOT NULL,
                            `type` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `type` TEXT NOT NULL,
                            `amount` INTEGER NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `accountId` INTEGER NOT NULL,
                            `toAccountId` INTEGER,
                            `categoryId` INTEGER,
                            `note` TEXT NOT NULL,
                            `receiptUri` TEXT,
                            `isRecurring` INTEGER NOT NULL,
                            `isInitialBalance` INTEGER NOT NULL,
                            `isDemo` INTEGER NOT NULL,
                            `linkedGoalId` INTEGER,
                            `linkedObligationId` INTEGER,
                            `linkedDebtId` INTEGER
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `budgets` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `categoryId` INTEGER NOT NULL,
                            `monthlyLimit` INTEGER NOT NULL,
                            `isDemo` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `saving_goals` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `targetAmount` INTEGER NOT NULL,
                            `savedAmount` INTEGER NOT NULL,
                            `deadlineTimestamp` INTEGER NOT NULL,
                            `monthlyTargetContribution` INTEGER NOT NULL,
                            `colorHex` INTEGER NOT NULL,
                            `iconName` TEXT NOT NULL,
                            `isDemo` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `recurring_obligations` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `amount` INTEGER NOT NULL,
                            `dayOfMonth` INTEGER NOT NULL,
                            `accountId` INTEGER NOT NULL,
                            `categoryId` INTEGER NOT NULL,
                            `lastPaidYearMonth` TEXT NOT NULL,
                            `isDemo` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `debts` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `creditorName` TEXT NOT NULL,
                            `totalAmount` INTEGER NOT NULL,
                            `monthlyInstallment` INTEGER NOT NULL,
                            `remainingBalance` INTEGER NOT NULL,
                            `dueDayOfMonth` INTEGER NOT NULL,
                            `isDemo` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqliteDb = helper.writableDatabase

        // 1. Insertar datos en esquema v1 (incluyendo alias antiguos EFECTIVO, BANCO, BILLETERA)
        sqliteDb.execSQL("INSERT INTO accounts (id, name, type, initialBalance, colorHex, iconName, isDefault) VALUES (1, 'Efectivo', 'EFECTIVO', 300000, 1096081, 'payments', 1)")
        sqliteDb.execSQL("INSERT INTO accounts (id, name, type, initialBalance, colorHex, iconName, isDefault) VALUES (2, 'Nu Débito', 'BANCO', 1200000, 9133302, 'account_balance', 1)")
        sqliteDb.execSQL("INSERT INTO accounts (id, name, type, initialBalance, colorHex, iconName, isDefault) VALUES (3, 'Nequi', 'BILLETERA', 500000, 15485081, 'smartphone', 1)")

        sqliteDb.execSQL("INSERT INTO categories (id, name, iconName, colorHex, type) VALUES (1, 'Comida', 'restaurant', 16347926, 'GASTO')")
        sqliteDb.execSQL("INSERT INTO categories (id, name, iconName, colorHex, type) VALUES (2, 'Transporte', 'directions_bus', 3718648, 'GASTO')")
        sqliteDb.execSQL("INSERT INTO categories (id, name, iconName, colorHex, type) VALUES (3, 'Arriendo', 'home', 9133302, 'GASTO')")
        sqliteDb.execSQL("INSERT INTO categories (id, name, iconName, colorHex, type) VALUES (4, 'Servicios', 'bolt', 16170507, 'GASTO')")

        // Insertar presupuestos en tabla budgets:
        // Comida = $450.000, Transporte = $220.000, Arriendo = $1.100.000 (más un demo de $999.000 que NO debe pisar al real)
        sqliteDb.execSQL("INSERT INTO budgets (id, categoryId, monthlyLimit, isDemo) VALUES (1, 1, 450000, 0)")
        sqliteDb.execSQL("INSERT INTO budgets (id, categoryId, monthlyLimit, isDemo) VALUES (2, 2, 220000, 0)")
        sqliteDb.execSQL("INSERT INTO budgets (id, categoryId, monthlyLimit, isDemo) VALUES (3, 3, 1100000, 0)")
        sqliteDb.execSQL("INSERT INTO budgets (id, categoryId, monthlyLimit, isDemo) VALUES (4, 1, 999000, 1)")

        // Insertar 2 obligaciones en recurring_obligations:
        // 1) "Arriendo Apto" por $1.100.000 día 5, aún NO pagada en 2026-11 (lastPaidYearMonth = '2026-10') -> debe quedar en nov 2026
        // 2) "Plan Fibra" por $135.000 día 18, YA pagada en 2026-11 (lastPaidYearMonth = '2026-11') -> próximo vencimiento en dic 2026
        sqliteDb.execSQL(
            "INSERT INTO recurring_obligations (id, name, amount, dayOfMonth, accountId, categoryId, lastPaidYearMonth, isDemo) VALUES (10, 'Arriendo Apto', 1100000, 5, 2, 3, '2026-10', 0)"
        )
        sqliteDb.execSQL(
            "INSERT INTO recurring_obligations (id, name, amount, dayOfMonth, accountId, categoryId, lastPaidYearMonth, isDemo) VALUES (11, 'Plan Fibra', 135000, 18, 3, 4, '2026-11', 0)"
        )

        // Insertar 2 deudas en debts:
        // 1) "Crédito Bancolombia" total $5.000.000, cuota $400.000, pendiente $3.200.000 (ya pagado = $1.800.000)
        // 2) "Préstamo Cooperativa" total $2.000.000, cuota $200.000, pendiente $1.500.000 (ya pagado = $500.000)
        sqliteDb.execSQL(
            "INSERT INTO debts (id, creditorName, totalAmount, monthlyInstallment, remainingBalance, dueDayOfMonth, isDemo) VALUES (20, 'Crédito Bancolombia', 5000000, 400000, 3200000, 10, 0)"
        )
        sqliteDb.execSQL(
            "INSERT INTO debts (id, creditorName, totalAmount, monthlyInstallment, remainingBalance, dueDayOfMonth, isDemo) VALUES (21, 'Préstamo Cooperativa', 2000000, 200000, 1500000, 25, 0)"
        )

        // Salto 1 -> 2
        MiPlataDatabase.MIGRATION_1_2.migrate(sqliteDb)
        // En v2 existe categories.monthlyLimit; pongamos $250.000 en Servicios (cat 4) que no estaba en budgets
        sqliteDb.execSQL("UPDATE categories SET monthlyLimit = 250000 WHERE id = 4")

        // Salto 2 -> 3
        MiPlataDatabase.MIGRATION_2_3.migrate(sqliteDb)

        // Salto 3 -> 4
        MiPlataDatabase.MIGRATION_3_4.migrate(sqliteDb)

        // Salto 4 -> 5
        MiPlataDatabase.MIGRATION_4_5.migrate(sqliteDb)

        // Calcular totales ANTES de la migración 5 -> 6:
        // Presupuestos reales esperados: Comida (450.000) + Transporte (220.000) + Arriendo (1.100.000) + Servicios (250.000) = $2.020.000
        val expectedBudgetTotal = 450_000L + 220_000L + 1_100_000L + 250_000L
        // Obligaciones reales esperadas: $1.100.000 + $135.000 = $1.235.000
        val expectedPendingScheduledPaymentsTotal = 1_100_000L + 135_000L
        // Deudas reales esperadas (saldo pendiente): $3.200.000 + $1.500.000 = $4.700.000
        val expectedDebtsRemainingTotal = 3_200_000L + 1_500_000L

        // Salto 5 -> 6
        MiPlataDatabase.migrate5To6(sqliteDb, nowMillis = nowMillis)

        // Verificar que las tablas eliminadas ya no existen
        sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('budgets', 'recurring_obligations', 'debts')").use { c ->
            assertFalse("Las tablas budgets, recurring_obligations y debts deben haberse eliminado en v6", c.moveToFirst())
        }

        // Verificar 1) Total de presupuestos en categories.monthlyLimit después de migrar
        var migratedBudgetTotal = 0L
        val limitsByKey = mutableMapOf<String, Long>()
        sqliteDb.query("SELECT name, monthlyLimit, systemKey FROM categories").use { c ->
            while (c.moveToNext()) {
                val limit = c.getLong(1)
                val sysKey = c.getString(2)
                migratedBudgetTotal += limit
                if (sysKey != null) {
                    limitsByKey[sysKey] = limit
                }
            }
        }
        assertEquals(expectedBudgetTotal, migratedBudgetTotal)
        assertEquals(450_000L, limitsByKey[SystemCategoryKeys.COMIDA])
        assertEquals(220_000L, limitsByKey[SystemCategoryKeys.TRANSPORTE])
        assertEquals(1_100_000L, limitsByKey[SystemCategoryKeys.ARRIENDO])
        assertEquals(250_000L, limitsByKey[SystemCategoryKeys.SERVICIOS])

        // Verificar 2) Pagos programados pendientes generados desde recurring_obligations
        var migratedPendingScheduledTotal = 0L
        val migratedScheduledNotes = mutableMapOf<String, Long>()
        sqliteDb.query(
            "SELECT note, amount, timestamp, dueTimestamp, recurrenceAnchorDay, recurrence, status FROM transactions WHERE type = 'PAGO_PROGRAMADO'"
        ).use { c ->
            while (c.moveToNext()) {
                val note = c.getString(0)
                val amt = c.getLong(1)
                val ts = c.getLong(2)
                val dueTs = c.getLong(3)
                val anchor = c.getInt(4)
                val rec = c.getString(5)
                val status = c.getString(6)
                assertEquals("MENSUAL", rec)
                assertEquals("PENDIENTE", status)
                assertEquals(ts, dueTs)
                migratedPendingScheduledTotal += amt
                migratedScheduledNotes[note] = ts
                if (note == "Arriendo Apto") assertEquals(5, anchor)
                if (note == "Plan Fibra") assertEquals(18, anchor)
            }
        }
        assertEquals(expectedPendingScheduledPaymentsTotal, migratedPendingScheduledTotal)

        // "Arriendo Apto" (no pagada en 2026-11) debe vencer el 5 de noviembre de 2026
        val arriendoDueCal = Calendar.getInstance().apply { timeInMillis = migratedScheduledNotes["Arriendo Apto"]!! }
        assertEquals(Calendar.NOVEMBER, arriendoDueCal.get(Calendar.MONTH))
        assertEquals(5, arriendoDueCal.get(Calendar.DAY_OF_MONTH))

        // "Plan Fibra" (ya pagada en 2026-11) debe tener su primer vencimiento el 18 de diciembre de 2026
        val fibraDueCal = Calendar.getInstance().apply { timeInMillis = migratedScheduledNotes["Plan Fibra"]!! }
        assertEquals(Calendar.DECEMBER, fibraDueCal.get(Calendar.MONTH))
        assertEquals(18, fibraDueCal.get(Calendar.DAY_OF_MONTH))

        // Verificar 3) Deudas convertidas en cuentas de tipo PRESTAMO sin perder ni un peso
        var migratedDebtsRemainingTotal = 0L
        var migratedLoansOriginalTotal = 0L
        var migratedLoansPaidPriorTotal = 0L
        sqliteDb.query(
            "SELECT name, type, originalAmount, receivedOrPaidAmount, monthlyInstallment FROM accounts WHERE type = 'PRESTAMO'"
        ).use { c ->
            while (c.moveToNext()) {
                val orig = c.getLong(2)
                val paidPrior = c.getLong(3)
                migratedLoansOriginalTotal += orig
                migratedLoansPaidPriorTotal += paidPrior
                migratedDebtsRemainingTotal += (orig - paidPrior)
            }
        }
        assertEquals(7_000_000L, migratedLoansOriginalTotal)
        assertEquals(2_300_000L, migratedLoansPaidPriorTotal)
        assertEquals(expectedDebtsRemainingTotal, migratedDebtsRemainingTotal)

        // Verificar que los alias antiguos EFECTIVO, BANCO y BILLETERA quedaron normalizados a DEBITO
        sqliteDb.query("SELECT COUNT(*) FROM accounts WHERE type IN ('EFECTIVO', 'BANCO', 'BILLETERA')").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // Salto 6 -> 7 (añade isArchived = 0 por defecto en accounts)
        MiPlataDatabase.MIGRATION_6_7.migrate(sqliteDb)
        sqliteDb.query("SELECT COUNT(*) FROM accounts WHERE isArchived != 0").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        helper.close()
    }

    @Test
    fun `categorias con systemKey se pueden renombrar pero no borrar y clave foranea impide borrar cuenta con movimientos`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()

        val arriendoCat = dao.getCategoryBySystemKey(SystemCategoryKeys.ARRIENDO)
        assertNotNull("Debe existir la categoría con systemKey ARRIENDO", arriendoCat)

        // 1. Renombrar categoría del sistema funciona y conserva su systemKey
        repository.renameCategory(arriendoCat!!, "Vivienda y Administración")
        val renamedCat = dao.getCategoryBySystemKey(SystemCategoryKeys.ARRIENDO)
        assertNotNull(renamedCat)
        assertEquals("Vivienda y Administración", renamedCat!!.name)
        assertEquals(arriendoCat.id, MiPlataRepository.resolveCategoryId(dao.getCategoriesOnce(), SystemCategoryKeys.ARRIENDO))

        // 2. Intentar borrar una categoría con systemKey devuelve false y no la borra
        val deletedSystem = repository.deleteCategory(renamedCat)
        assertFalse("Las categorías con systemKey no se pueden borrar", deletedSystem)
        assertNotNull(dao.getCategoryBySystemKey(SystemCategoryKeys.ARRIENDO))

        // 3. Crear una categoría personalizada (systemKey == null) sí permite borrarla
        val customCatId = dao.insertCategory(
            CategoryEntity(
                name = "Mascotas",
                iconName = "pets",
                colorHex = 0xFF10B981,
                type = CategoryType.GASTO,
                groupName = "Hogar",
                systemKey = null
            )
        )
        val customCat = dao.getCategoriesOnce().first { it.id == customCatId }
        assertTrue(repository.deleteCategory(customCat))
        assertTrue(dao.getCategoriesOnce().none { it.id == customCatId })

        // 4. Clave foránea RESTRICT en transactions(accountId) -> accounts(id)
        val account = dao.getAccountsOnce().first { it.type == AccountType.DEBITO }
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 20_000L,
                timestamp = 1_763_300_000_000L,
                accountId = account.id,
                categoryId = renamedCat.id,
                note = "Café"
            )
        )
        try {
            dao.deleteAccount(account)
            fail("Borrar una cuenta que tiene movimientos asociados debe lanzar SQLiteConstraintException por ForeignKey.RESTRICT")
        } catch (_: SQLiteConstraintException) {
            // Esperado por ForeignKey.RESTRICT
        }

        // 5. MiPlataTypeConverters sigue aceptando EFECTIVO, BANCO y BILLETERA al leer datos antiguos
        val converters = MiPlataTypeConverters()
        assertEquals(AccountType.DEBITO, converters.toAccountType("EFECTIVO"))
        assertEquals(AccountType.DEBITO, converters.toAccountType("BANCO"))
        assertEquals(AccountType.DEBITO, converters.toAccountType("BILLETERA"))
    }

    @Test
    fun `usuario nuevo que elige solo Efectivo y Bancolombia en onboarding termina con exactamente esas dos cuentas`() = runBlocking {
        val dao = db.dao()
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Sembrar valores iniciales por defecto (solo "Efectivo" 0L + categorías)
        repository.ensureDefaultsSeeded()
        val initialAccounts = dao.getAccountsOnce()
        assertEquals("defaultAccounts() solo debe crear 'Efectivo'", listOf("Efectivo"), initialAccounts.map { it.name })
        assertTrue("No debe crearse ninguna tarjeta de crédito ni cuenta de ahorros sola", initialAccounts.all { it.type == AccountType.DEBITO })

        // Completar onboarding eligiendo únicamente Efectivo ($120.000) y Bancolombia ($1.450.000)
        // y un pago fijo opcional que sale de Bancolombia
        repository.completeOnboardingSetup(
            selectedAccounts = listOf(
                OnboardingAccountChoice(
                    name = "Efectivo",
                    initialBalance = 120_000L,
                    colorHex = 0xFF10B981,
                    iconName = "payments"
                ),
                OnboardingAccountChoice(
                    name = "Bancolombia",
                    initialBalance = 1_450_000L,
                    colorHex = 0xFFF59E0B,
                    iconName = "account_balance"
                )
            ),
            periodMode = PeriodMode.QUINCENAL,
            customCutoffDay = 15,
            approximateIncome = 3_200_000L,
            fixedPaymentName = "Arriendo",
            fixedPaymentAmount = 950_000L,
            fixedPaymentDayOfMonth = 5,
            fixedPaymentAccountName = "Bancolombia",
            nowMillis = nowMillis
        )

        val finalAccounts = dao.getAccountsOnce().sortedBy { it.name }
        assertEquals(2, finalAccounts.size)
        assertEquals(listOf("Bancolombia", "Efectivo"), finalAccounts.map { it.name })
        assertTrue(finalAccounts.all { it.type == AccountType.DEBITO && !it.isArchived })

        val bancolombia = finalAccounts.first { it.name == "Bancolombia" }
        val efectivo = finalAccounts.first { it.name == "Efectivo" }
        assertEquals(1_450_000L, bancolombia.initialBalance)
        assertEquals(120_000L, efectivo.initialBalance)

        // El pago fijo programado debe apuntar a Bancolombia
        val scheduledTx = dao.getTransactionsOnce().single()
        assertEquals(TransactionType.PAGO_PROGRAMADO, scheduledTx.type)
        assertEquals(950_000L, scheduledTx.amount)
        assertEquals(bancolombia.id, scheduledTx.accountId)
    }

    @Test
    fun `archivar una cuenta con movimientos no cambia el historial la quita de Disponible para gastar y desarchivar la restaura`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val efectivo = dao.getAccountsOnce().first { it.name == "Efectivo" }
        dao.updateAccount(efectivo.copy(initialBalance = 500_000L))

        val nequiId = dao.insertAccount(
            AccountEntity(
                name = "Nequi",
                type = AccountType.DEBITO,
                initialBalance = 800_000L,
                colorHex = 0xFFEC4899,
                iconName = "smartphone"
            )
        )
        val emptyAccId = dao.insertAccount(
            AccountEntity(
                name = "Daviplata Vacía",
                type = AccountType.DEBITO,
                initialBalance = 0L,
                colorHex = 0xFFEF4444,
                iconName = "smartphone"
            )
        )

        val comidaCat = dao.getCategoryBySystemKey(SystemCategoryKeys.COMIDA)!!
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 50_000L,
                timestamp = nowMillis - 3_600_000L,
                accountId = nequiId,
                categoryId = comidaCat.id,
                note = "Almuerzo con Nequi",
                status = TransactionStatus.PAGADO
            )
        )

        // 1) Cuenta sin movimientos se elimina definitivamente
        val emptyAcc = dao.getAccountById(emptyAccId)!!
        assertEquals(0, repository.countTransactionsForAccount(emptyAccId))
        val emptyOutcome = repository.deleteOrArchiveAccount(emptyAcc.id)
        assertEquals(AccountRemovalOutcome.DELETED, emptyOutcome)
        assertEquals(null, dao.getAccountById(emptyAccId))

        // 2) Calcular Disponible para gastar y el historial ANTES de archivar Nequi
        // Efectivo = $500.000, Nequi = $800.000 - $50.000 = $750.000 -> Total Débito = $1.250.000
        val window = AvailableToSpendCalculator.resolvePeriodWindow(PeriodMode.MENSUAL, nowMillis = nowMillis)
        val availBefore = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = dao.getAccountsOnce(),
            allTransactions = dao.getTransactionsOnce(),
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )
        assertEquals(1_250_000L, availBefore.currentDebitBalance)
        assertEquals(1_250_000L, availBefore.availableForPeriod)
        val transactionsBeforeArchive = dao.getTransactionsOnce()

        // 3) Intentar eliminar Nequi (que tiene movimientos) -> se archiva en vez de borrarse
        val nequiAcc = dao.getAccountById(nequiId)!!
        assertEquals(1, repository.countTransactionsForAccount(nequiId))
        val nequiOutcome = repository.deleteOrArchiveAccount(nequiAcc.id)
        assertEquals(AccountRemovalOutcome.ARCHIVED, nequiOutcome)

        val archivedNequi = dao.getAccountById(nequiId)!!
        assertTrue(archivedNequi.isArchived)

        // 4) El historial queda intacto
        assertEquals(transactionsBeforeArchive, dao.getTransactionsOnce())

        // 5) Disponible para gastar y selectores excluyen la cuenta archivada (queda solo Efectivo = $500.000)
        val availAfterArchive = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = dao.getAccountsOnce(),
            allTransactions = dao.getTransactionsOnce(),
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )
        assertEquals(500_000L, availAfterArchive.currentDebitBalance)
        assertEquals(500_000L, availAfterArchive.availableForPeriod)
        assertEquals(
            efectivo.id,
            MiPlataRepository.resolveValidAccountId(dao.getAccountsOnce(), preferredAccountId = nequiId)
        )

        // 6) Desarchivar desde Ajustes restaura la cuenta en Disponible para gastar
        repository.setAccountArchived(nequiId, archived = false)
        assertFalse(dao.getAccountById(nequiId)!!.isArchived)
        val availAfterUnarchive = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = dao.getAccountsOnce(),
            allTransactions = dao.getTransactionsOnce(),
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )
        assertEquals(1_250_000L, availAfterUnarchive.currentDebitBalance)
        assertEquals(1_250_000L, availAfterUnarchive.availableForPeriod)
    }

    @Test
    fun `borrar una categoria reasignando sus movimientos mantiene el total gastado del mes e impide duplicados por tipo`() = runBlocking {
        val dao = db.dao()
        repository.ensureDefaultsSeeded()
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val efectivoId = dao.getAccountsOnce().first().id
        val comidaCat = dao.getCategoryBySystemKey(SystemCategoryKeys.COMIDA)!!

        // 1. Crear categoría personalizada "Cafeterías"
        val createErr = repository.saveOrUpdateCategory(
            id = 0L,
            name = "Cafeterías",
            iconName = "local_cafe",
            colorHex = 0xFFF97316,
            type = CategoryType.GASTO,
            groupName = "Estilo de vida",
            monthlyLimit = 150_000L
        )
        assertEquals(null, createErr)
        val cafeteriasCat = dao.getCategoriesOnce().first { it.name == "Cafeterías" }

        // 2. Intentar crear otra categoría de GASTO con el mismo nombre (incluso con mayúsculas/espacios) falla
        val dupErr = repository.saveOrUpdateCategory(
            id = 0L,
            name = "  cafeterías ",
            iconName = "restaurant",
            colorHex = 0xFF10B981,
            type = CategoryType.GASTO,
            groupName = "Otros"
        )
        assertNotNull("No debe permitir dos categorías con el mismo nombre en el mismo tipo", dupErr)

        // 3. Registrar movimientos en Comida ($80.000) y en Cafeterías ($25.000 y $18.000)
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 80_000L,
                timestamp = nowMillis - 7_200_000L,
                accountId = efectivoId,
                categoryId = comidaCat.id,
                note = "Mercado",
                status = TransactionStatus.PAGADO
            )
        )
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 25_000L,
                timestamp = nowMillis - 3_600_000L,
                accountId = efectivoId,
                categoryId = cafeteriasCat.id,
                note = "Café Juan Valdez",
                status = TransactionStatus.PAGADO
            )
        )
        dao.insertTransaction(
            TransactionEntity(
                type = TransactionType.GASTO,
                amount = 18_000L,
                timestamp = nowMillis - 1_800_000L,
                accountId = efectivoId,
                categoryId = cafeteriasCat.id,
                note = "Postre y capuchino",
                status = TransactionStatus.PAGADO
            )
        )

        val totalSpentBefore = dao.getTransactionsOnce()
            .filter { it.type == TransactionType.GASTO && it.status == TransactionStatus.PAGADO }
            .sumOf { it.amount }
        assertEquals(123_000L, totalSpentBefore)
        assertEquals(2, repository.countTransactionsForCategory(cafeteriasCat.id))

        // 4. Intentar borrar "Cafeterías" sin elegir categoría de reasignación devuelve error y no borra nada
        val errWithoutReassign = repository.deleteCategoryWithReassignment(cafeteriasCat, reassignToCategoryId = null)
        assertNotNull(errWithoutReassign)
        assertNotNull(dao.getCategoryById(cafeteriasCat.id))

        // 5. Borrar "Cafeterías" reasignando sus movimientos a "Comida" en una transacción
        val deleteErr = repository.deleteCategoryWithReassignment(cafeteriasCat, reassignToCategoryId = comidaCat.id)
        assertEquals(null, deleteErr)
        assertEquals(null, dao.getCategoryById(cafeteriasCat.id))

        val txsAfter = dao.getTransactionsOnce()
        val totalSpentAfter = txsAfter
            .filter { it.type == TransactionType.GASTO && it.status == TransactionStatus.PAGADO }
            .sumOf { it.amount }

        // El total gastado del mes se mantiene intacto ($123.000) y los 3 movimientos apuntan a "Comida"
        assertEquals(totalSpentBefore, totalSpentAfter)
        assertEquals(3, txsAfter.count { it.categoryId == comidaCat.id })

        // 6. Verificar formateo en vivo con separador de miles
        val transformed = CopThousandSeparatorTransformation("$").filter(AnnotatedString("1250000"))
        assertEquals("$1.250.000", transformed.text.text)
    }

    @Test
    fun `proyeccion de presupuesto 300000 gastados al dia 10 de un mes de 30 dias con limite 600000 proyecta 900000 y marca willExceedAtCurrentPace`() {
        val catComida = CategoryEntity(
            id = 1L,
            name = "Comida",
            iconName = "restaurant",
            colorHex = 0xFFEF4444,
            type = CategoryType.GASTO,
            monthlyLimit = 600_000L
        )

        val item = AnalyticsCalculator.computeBudgetProgressItem(
            category = catComida,
            spentThisMonth = 300_000L,
            daysElapsedInMonth = 10,
            daysInMonth = 30
        )

        assertEquals(50, item.percentage)
        assertEquals(900_000L, item.projectedEndOfMonth)
        assertTrue(
            "Con $300.000 al día 10 de 30 y límite $600.000, proyecta $900.000 y debe activar willExceedAtCurrentPace",
            item.willExceedAtCurrentPace
        )

        // Si en cambio lleva $150.000 al día 10 de 30, proyecta $450.000 (<= $600.000) -> false
        val itemUnderPace = AnalyticsCalculator.computeBudgetProgressItem(
            category = catComida,
            spentThisMonth = 150_000L,
            daysElapsedInMonth = 10,
            daysInMonth = 30
        )
        assertEquals(450_000L, itemUnderPace.projectedEndOfMonth)
        assertFalse(itemUnderPace.willExceedAtCurrentPace)
    }

    @Test
    fun `calculos de Analisis con datos fijos incluyen mes sin movimientos reembolso mayor al gasto de su categoria y agrupacion en Otras menor al 3 por ciento`() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val accDebito = AccountEntity(
            id = 1L,
            name = "Cuenta Nómina",
            type = AccountType.DEBITO,
            initialBalance = 2_000_000L,
            colorHex = 0xFF10B981,
            iconName = "account_balance"
        )
        val accTarjeta = AccountEntity(
            id = 2L,
            name = "Tarjeta Crédito",
            type = AccountType.TARJETA_CREDITO,
            initialBalance = 0L,
            creditLimit = 3_000_000L,
            billingCutoffDay = 20,
            paymentDueDay = 5,
            colorHex = 0xFF8B5CF6,
            iconName = "credit_card"
        )
        val accounts = listOf(accDebito, accTarjeta)

        val catArriendo = CategoryEntity(id = 1L, name = "Arriendo", iconName = "home", colorHex = 0xFF3B82F6, type = CategoryType.GASTO, monthlyLimit = 1_200_000L)
        val catMercado = CategoryEntity(id = 2L, name = "Mercado", iconName = "shopping_cart", colorHex = 0xFF10B981, type = CategoryType.GASTO, monthlyLimit = 500_000L)
        val catSnacks = CategoryEntity(id = 3L, name = "Snacks", iconName = "fastfood", colorHex = 0xFFF59E0B, type = CategoryType.GASTO)
        val catPropina = CategoryEntity(id = 4L, name = "Propinas", iconName = "redeem", colorHex = 0xFFEC4899, type = CategoryType.GASTO)
        val catRopa = CategoryEntity(id = 5L, name = "Ropa", iconName = "checkroom", colorHex = 0xFF6366F1, type = CategoryType.GASTO)
        val catSalario = CategoryEntity(id = 6L, name = "Salario", iconName = "payments", colorHex = 0xFF10B981, type = CategoryType.INGRESO)
        val categories = listOf(catArriendo, catMercado, catSnacks, catPropina, catRopa, catSalario)

        // Mes sin movimientos (Septiembre 2026):
        val sepMillis = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val emptyMonthSummary = AnalyticsCalculator.computeDailyExpensesForMonth(
            transactions = emptyList(),
            nowMillis = sepMillis
        )
        assertEquals(0L, emptyMonthSummary.totalMonthExpense)
        assertEquals(0L, emptyMonthSummary.dailyAverageExpense)
        assertTrue(emptyMonthSummary.dailyPoints.isEmpty())

        // Movimientos en Noviembre 2026:
        // - Ingreso Salario: $3.000.000
        // - Arriendo: $700.000 (70%)
        // - Mercado: $270.000 (27%)
        // - Snacks: $18.000 (1,8% -> < 3%, va a "Otras")
        // - Propinas: $12.000 (1,2% -> < 3%, va a "Otras")
        // - Ropa: Gasto $50.000 y Reembolso $80.000 (reembolso mayor al gasto de su categoría -> neto $0)
        // - Transferencia ($200.000) y Pago de tarjeta ($300.000) -> NO cuentan como gasto ni como ingreso
        val day5Millis = (nowCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 5) }.timeInMillis
        val day10Millis = (nowCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 10) }.timeInMillis

        // Movimiento en Octubre 2026 (mes anterior) para comparación por categoría y barras de 6 meses:
        val oct10Millis = (nowCal.clone() as Calendar).apply {
            set(2026, Calendar.OCTOBER, 10, 10, 0, 0)
        }.timeInMillis

        val txs = listOf(
            TransactionEntity(id = 1L, type = TransactionType.INGRESO, amount = 3_000_000L, timestamp = day5Millis, accountId = 1L, categoryId = 6L, note = "Nómina", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 2L, type = TransactionType.GASTO, amount = 700_000L, timestamp = day5Millis, accountId = 1L, categoryId = 1L, note = "Arriendo nov", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 3L, type = TransactionType.GASTO, amount = 270_000L, timestamp = day10Millis, accountId = 1L, categoryId = 2L, note = "Mercado nov", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 4L, type = TransactionType.GASTO, amount = 18_000L, timestamp = day10Millis, accountId = 1L, categoryId = 3L, note = "Snacks", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 5L, type = TransactionType.GASTO, amount = 12_000L, timestamp = day10Millis, accountId = 1L, categoryId = 4L, note = "Propina", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 6L, type = TransactionType.GASTO, amount = 50_000L, timestamp = day10Millis, accountId = 1L, categoryId = 5L, note = "Camisa", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 7L, type = TransactionType.REEMBOLSO, amount = 80_000L, timestamp = day10Millis, accountId = 1L, categoryId = 5L, note = "Devolución tienda", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 8L, type = TransactionType.TRANSFERENCIA, amount = 200_000L, timestamp = day10Millis, accountId = 1L, toAccountId = 1L, note = "Traspaso", status = TransactionStatus.PAGADO),
            TransactionEntity(id = 9L, type = TransactionType.PAGO_TARJETA, amount = 300_000L, timestamp = day10Millis, accountId = 1L, toAccountId = 2L, note = "Abono TC", status = TransactionStatus.PAGADO),
            // Mes anterior (Octubre): Mercado $200.000 ( Septiembre queda sin movimientos )
            TransactionEntity(id = 10L, type = TransactionType.GASTO, amount = 200_000L, timestamp = oct10Millis, accountId = 1L, categoryId = 2L, note = "Mercado oct", status = TransactionStatus.PAGADO)
        )

        val state = AnalyticsCalculator.computeAnalyticsState(
            filter = AnalyticsRangeFilter.MES,
            customDays = 30,
            accounts = accounts,
            categories = categories,
            transactions = txs,
            nowMillis = nowMillis
        )

        // 1. Dona: Total gasto neto = 700.000 + 270.000 + 18.000 + 12.000 + 0 (Ropa con reembolso > gasto) = 1.000.000
        // Arriendo (70%), Mercado (27%) y "Otras" (Snacks + Propinas = $30.000 = 3%)
        assertEquals(1_000_000L, state.donutSlices.sumOf { it.amount })
        assertEquals(3, state.donutSlices.size)
        assertEquals("Arriendo", state.donutSlices[0].category.name)
        assertEquals(700_000L, state.donutSlices[0].amount)
        assertEquals("Mercado", state.donutSlices[1].category.name)
        assertEquals(270_000L, state.donutSlices[1].amount)
        assertEquals("Otras", state.donutSlices[2].category.name)
        assertEquals(30_000L, state.donutSlices[2].amount)
        assertEquals(2, state.donutSlices[2].transactionsCount)
        assertFalse("Ropa con reembolso mayor a su gasto no debe aparecer en la dona", state.donutSlices.any { it.category.name == "Ropa" })

        // 2. Ingresos vs gastos últimos 6 meses: incluye meses sin movimientos (ej. Septiembre = 0 / 0)
        assertEquals(6, state.sixMonthBars.size)
        val novBar = state.sixMonthBars.last()
        assertEquals(3_000_000L, novBar.income)
        assertEquals(1_000_000L, novBar.expense)
        val octBar = state.sixMonthBars[state.sixMonthBars.size - 2]
        assertEquals(0L, octBar.income)
        assertEquals(200_000L, octBar.expense)
        val sepBar = state.sixMonthBars[state.sixMonthBars.size - 3]
        assertEquals(0L, sepBar.income)
        assertEquals(0L, sepBar.expense)

        // 3. Evolución del saldo en cuentas de débito activas
        assertTrue(state.balanceEvolution.isNotEmpty())
        assertTrue(state.balanceEvolution.last().totalBalance > 0L)

        // 4. Gasto por día del mes y promedio diario (1.000.000 / 15 días transcurridos = 66.666)
        assertEquals(1_000_000L, state.dailyExpensesCurrentMonth.sumOf { it.amount })
        assertEquals(66_666L, state.dailyAverageExpense)

        // 5. Comparación por categoría contra el mes anterior: Mercado pasó de $200.000 a $270.000 (+35%)
        val compMercado = state.categoryComparisons.first { it.category.name == "Mercado" }
        assertEquals(200_000L, compMercado.previousMonthAmount)
        assertEquals(270_000L, compMercado.currentMonthAmount)
        assertEquals(35, compMercado.deltaPercentage)

        // 6. Alertas de Inicio: máximo 3 y ordenadas por prioridad (1: vencido, 2: vence <=3 días sin saldo, 3: presupuesto al ritmo, 4: tarjeta >80%)
        val overdueTx = TransactionEntity(
            id = 50L,
            type = TransactionType.PAGO_PROGRAMADO,
            amount = 120_000L,
            timestamp = nowMillis - 2 * 86_400_000L,
            accountId = 1L,
            note = "Luz vencida",
            status = TransactionStatus.PENDIENTE
        )
        val dueSoonInsufficientTx = TransactionEntity(
            id = 51L,
            type = TransactionType.PAGO_PROGRAMADO,
            amount = 9_500_000L,
            timestamp = nowMillis + 86_400_000L,
            accountId = 1L,
            note = "Cuota apartamento",
            status = TransactionStatus.PENDIENTE
        )
        val cardPurchaseOver80 = TransactionEntity(
            id = 52L,
            type = TransactionType.GASTO,
            amount = 2_600_000L, // > 80% de 3.000.000
            timestamp = day5Millis,
            accountId = 2L,
            categoryId = 2L,
            note = "Compra grande TC",
            status = TransactionStatus.PAGADO
        )
        val allAlertTxs = txs + listOf(overdueTx, dueSoonInsufficientTx, cardPurchaseOver80)
        val cardSummaries = listOf(AvailableToSpendCalculator.computeCreditCardSummary(accTarjeta, allAlertTxs, nowMillis))
        val budgetProgressExceeding = listOf(
            AnalyticsCalculator.computeBudgetProgressItem(
                category = catArriendo,
                spentThisMonth = 700_000L,
                daysElapsedInMonth = 10,
                daysInMonth = 30
            ) // Proyecta 2.100.000 > 1.200.000
        )

        val alerts = AnalyticsCalculator.computeHomeSmartAlerts(
            accounts = accounts,
            transactions = allAlertTxs,
            budgetItems = budgetProgressExceeding,
            creditCardSummaries = cardSummaries,
            hideBalances = false,
            nowMillis = nowMillis
        )
        assertEquals("Debe devolver máximo 3 alertas aunque las 4 condiciones se cumplan", 3, alerts.size)
        assertTrue("Prioridad 1: Pago vencido", alerts[0].title.contains("Pago vencido"))
        assertTrue("Prioridad 2: Pago próximo sin saldo suficiente", alerts[1].title.contains("Saldo insuficiente"))
        assertTrue("Prioridad 3: Proyección de exceso al ritmo actual", alerts[2].title.contains("Proyección de exceso"))
    }

    @Test
    fun `cambiar la hora del recordatorio cancela el trabajo anterior de WorkManager y programa el nuevo respetando ocultar saldos`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 8, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 1. Programar recordatorio diario a las 20:00
        val firstWorkId = ReminderScheduler.scheduleOrCancelDailyReminder(
            context = context,
            enabled = true,
            hourOfDay = 20,
            nowMillis = nowMillis
        )
        assertNotNull(firstWorkId)

        val firstInfo = workManager.getWorkInfoById(firstWorkId!!).get()
        assertNotNull(firstInfo)
        assertEquals(WorkInfo.State.ENQUEUED, firstInfo!!.state)
        assertTrue(firstInfo.tags.contains("daily_reminder_hour_20"))

        // 2. Cambiar la hora del recordatorio a las 09:00
        val secondWorkId = ReminderScheduler.scheduleOrCancelDailyReminder(
            context = context,
            enabled = true,
            hourOfDay = 9,
            nowMillis = nowMillis
        )
        assertNotNull(secondWorkId)

        // El trabajo anterior debe haber quedado CANCELADO y el nuevo ENQUEUED con la hora 9
        val oldWorkInfo = workManager.getWorkInfoById(firstWorkId).get()
        assertNotNull(oldWorkInfo)
        assertEquals(
            "El trabajo anterior de WorkManager debe cancelarse al cambiar la hora",
            WorkInfo.State.CANCELLED,
            oldWorkInfo!!.state
        )

        val newWorkInfo = workManager.getWorkInfoById(secondWorkId!!).get()
        assertNotNull("Debe existir un nuevo trabajo ENQUEUED para la nueva hora", newWorkInfo)
        assertEquals(WorkInfo.State.ENQUEUED, newWorkInfo!!.state)
        assertTrue(newWorkInfo.tags.contains("daily_reminder_hour_9"))

        // 3. Desactivar el interruptor cancela el trabajo activo
        ReminderScheduler.scheduleOrCancelDailyReminder(
            context = context,
            enabled = false,
            hourOfDay = 9,
            nowMillis = nowMillis
        )
        val infosAfterDisable = workManager.getWorkInfosByTag(ReminderScheduler.TAG_DAILY_REMINDER).get()
        assertTrue(infosAfterDisable.all { it.state == WorkInfo.State.CANCELLED })

        // 4. Verificar que los textos de notificación respetan "ocultar saldos" (sin montos)
        val msgVisible = NotificationHelper.formatDailyReminderMessage(availableTodayCop = 85_000L, hideBalances = false)
        assertTrue(msgVisible.contains("Hoy puedes gastar"))
        assertTrue(msgVisible.contains("85.000"))

        val msgHidden = NotificationHelper.formatDailyReminderMessage(availableTodayCop = 85_000L, hideBalances = true)
        assertFalse("Con ocultar saldos activo no debe mostrar el monto en la notificación", msgHidden.contains("85.000"))

        val dueTomorrowVisible = NotificationHelper.formatUpcomingObligationMessage(
            obligationName = "Internet Hogar",
            daysUntilDue = 1,
            amountCop = 115_000L,
            hideBalances = false
        )
        assertEquals("Mañana vence Internet Hogar: $115.000", dueTomorrowVisible)

        val dueTomorrowHidden = NotificationHelper.formatUpcomingObligationMessage(
            obligationName = "Internet Hogar",
            daysUntilDue = 1,
            amountCop = 115_000L,
            hideBalances = true
        )
        assertEquals("Mañana vence Internet Hogar", dueTomorrowHidden)
    }

    @Test
    fun `chat con historial conserva los ultimos 10 mensajes, separa el resumen financiero de la pregunta y mantiene contexto al preguntar y el mes pasado`() {
        assertEquals("gemini-3.8-flash", GeminiFinanceService.GEMINI_MODEL_NAME)

        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val currentOctNovTxMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val prevMonthTxMillis = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 12, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val accNequi = AccountEntity(
            id = 1L,
            name = "Nequi",
            type = AccountType.DEBITO,
            initialBalance = 2_000_000L,
            colorHex = 0xFFEC4899,
            iconName = "smartphone"
        )
        val catComida = CategoryEntity(
            id = 10L,
            name = "Comida y Mercado",
            type = CategoryType.GASTO,
            iconName = "restaurant",
            colorHex = 0xFFF59E0B,
            monthlyLimit = 600_000L
        )
        val txs = listOf(
            TransactionEntity(
                id = 1L,
                type = TransactionType.GASTO,
                amount = 350_000L,
                timestamp = currentOctNovTxMillis,
                accountId = 1L,
                categoryId = 10L,
                note = "Mercado noviembre",
                status = TransactionStatus.PAGADO
            ),
            TransactionEntity(
                id = 2L,
                type = TransactionType.GASTO,
                amount = 480_000L,
                timestamp = prevMonthTxMillis,
                accountId = 1L,
                categoryId = 10L,
                note = "Mercado octubre",
                status = TransactionStatus.PAGADO
            )
        )
        val accsWithBal = listOf(
            AccountWithBalance(
                account = accNequi,
                currentBalance = AvailableToSpendCalculator.computeAccountBalance(accNequi, txs, includePending = false),
                projectedBalance = AvailableToSpendCalculator.computeAccountBalance(accNequi, txs, includePending = true),
                periodIncome = 0L,
                periodExpense = 350_000L
            )
        )
        val window = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = nowMillis
        )
        val avail = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(accNequi),
            allTransactions = txs,
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )
        val summary = GeminiFinanceService.buildPrivacySafeFinancialSummary(
            availableToSpend = avail,
            accountsWithBalance = accsWithBal,
            creditCardSummaries = emptyList(),
            allTransactions = txs,
            categories = listOf(catComida),
            savingGoals = emptyList(),
            nowMillis = nowMillis
        )

        // Turno 1: "¿cuánto gasté en comida?" -> Respuesta de Gemini -> Turno 2: "¿y el mes pasado?"
        val priorConversation = listOf(
            ChatMessageItem(id = 1L, isUser = true, text = "¿cuánto gasté en comida?"),
            ChatMessageItem(id = 2L, isUser = false, text = "Este mes llevas $350.000 en Comida y Mercado.")
        )
        val preparedTurn2 = GeminiFinanceService.prepareChatTurnRequest(
            question = "¿y el mes pasado?",
            aggregatedContext = summary,
            previousMessages = priorConversation
        )

        assertEquals(2, preparedTurn2.historyMessages.size)
        assertEquals("¿cuánto gasté en comida?", preparedTurn2.historyMessages[0].text)
        assertEquals("Este mes llevas $350.000 en Comida y Mercado.", preparedTurn2.historyMessages[1].text)

        // El turno actual separa claramente los datos del resumen y la pregunta del usuario
        assertTrue(preparedTurn2.formattedTurnMessage.contains("<DATOS_FINANCIEROS_ACTUALIZADOS>"))
        assertTrue(preparedTurn2.formattedTurnMessage.contains("<PREGUNTA_DEL_USUARIO>\n¿y el mes pasado?\n</PREGUNTA_DEL_USUARIO>"))
        // Y el resumen contiene tanto el gasto de este mes ($350.000) como el del mes pasado ($480.000) en Comida y Mercado
        assertTrue(preparedTurn2.formattedTurnMessage.contains("Comida y Mercado: este mes $350.000, mes pasado $480.000"))

        // Si hay más de 10 mensajes previos, solo se conservan los últimos 10
        val longHistory = (1..16).map { idx ->
            ChatMessageItem(id = idx.toLong(), isUser = idx % 2 == 1, text = "Mensaje #$idx")
        }
        val preparedWithCap = GeminiFinanceService.prepareChatTurnRequest(
            question = "Pregunta actual",
            aggregatedContext = summary,
            previousMessages = longHistory
        )
        assertEquals(10, preparedWithCap.historyMessages.size)
        assertEquals("Mensaje #7", preparedWithCap.historyMessages.first().text)
        assertEquals("Mensaje #16", preparedWithCap.historyMessages.last().text)
    }

    @Test
    fun `validacion local de borrador de movimiento filtra monto 0, fecha imposible, categoria inexistente y JSON incompleto`() {
        val nowMillis = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Bogota")).apply {
            set(2026, Calendar.NOVEMBER, 17, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val accounts = listOf(
            AccountEntity(id = 1L, name = "Nequi", type = AccountType.DEBITO, initialBalance = 500_000L, colorHex = 0xFFEC4899, iconName = "smartphone"),
            AccountEntity(id = 2L, name = "Bancolombia", type = AccountType.DEBITO, initialBalance = 900_000L, colorHex = 0xFFEAB308, iconName = "account_balance")
        )
        val categories = listOf(
            CategoryEntity(id = 10L, name = "Comida y Mercado", type = CategoryType.GASTO, iconName = "restaurant", colorHex = 0xFFF59E0B),
            CategoryEntity(id = 11L, name = "Transporte", type = CategoryType.GASTO, iconName = "directions_car", colorHex = 0xFF38BDF8)
        )

        // 1. Monto 0 -> monto queda en 0L y activa aviso "Revisa los datos antes de guardar"
        val zeroAmountDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """
                {
                  "tipo": "GASTO",
                  "montoCop": 0,
                  "fecha": "2026-11-17",
                  "categoria": "Comida y Mercado",
                  "cuenta": "Nequi",
                  "nota": "Almuerzo",
                  "confianza": 0.95
                }
            """.trimIndent(),
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(0L, zeroAmountDraft.amount)
        assertTrue(zeroAmountDraft.needsReviewWarning)
        assertEquals(GeminiFinanceService.REVIEW_DRAFT_WARNING_MESSAGE, zeroAmountDraft.reviewWarningMessage)

        // 2. Fecha imposible ("2026-02-30") y fecha a más de 1 año ("2020-05-01") -> usa nowMillis y activa aviso
        val impossibleDateDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """
                {
                  "tipo": "GASTO",
                  "montoCop": 28000,
                  "fecha": "2026-02-30",
                  "categoria": "Comida y Mercado",
                  "cuenta": "Nequi",
                  "nota": "Cena",
                  "confianza": 0.90
                }
            """.trimIndent(),
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(28_000L, impossibleDateDraft.amount)
        assertEquals(nowMillis, impossibleDateDraft.timestampMillis)
        assertTrue(impossibleDateDraft.needsReviewWarning)
        assertEquals("Revisa los datos antes de guardar", impossibleDateDraft.reviewWarningMessage)

        val tooFarDateDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """
                {
                  "tipo": "GASTO",
                  "montoCop": 28000,
                  "fecha": "2020-05-01",
                  "categoria": "Comida y Mercado",
                  "cuenta": "Nequi",
                  "nota": "Cena antigua",
                  "confianza": 0.90
                }
            """.trimIndent(),
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(nowMillis, tooFarDateDraft.timestampMillis)
        assertTrue(tooFarDateDraft.needsReviewWarning)

        // 3. Categoría inexistente y cuenta inexistente -> se dejan sin seleccionar (null / "") y activan aviso
        val unknownCategoryAndAccountDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """
                {
                  "tipo": "GASTO",
                  "montoCop": 45000,
                  "fecha": "2026-11-16",
                  "categoria": "Categoría Inventada",
                  "cuenta": "Banco Inexistente",
                  "nota": "Compra varia",
                  "confianza": 0.88
                }
            """.trimIndent(),
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(45_000L, unknownCategoryAndAccountDraft.amount)
        assertNull("Categoría inexistente debe quedar sin seleccionar (null)", unknownCategoryAndAccountDraft.matchedCategoryId)
        assertEquals("", unknownCategoryAndAccountDraft.categoryName)
        assertNull("Cuenta inexistente debe quedar sin seleccionar (null)", unknownCategoryAndAccountDraft.matchedAccountId)
        assertEquals("", unknownCategoryAndAccountDraft.accountName)
        assertTrue(unknownCategoryAndAccountDraft.needsReviewWarning)
        assertEquals("Revisa los datos antes de guardar", unknownCategoryAndAccountDraft.reviewWarningMessage)

        // 4. JSON incompleto o truncado -> no lanza excepción y devuelve borrador con aviso de revisión
        val incompleteJsonDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """{"tipo": "COMPRA_CUOTAS", "montoCop": 120000""",
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(TransactionType.COMPRA_CUOTAS, incompleteJsonDraft.type)
        assertEquals(120_000L, incompleteJsonDraft.amount)
        assertTrue(incompleteJsonDraft.needsReviewWarning)
        assertEquals("Revisa los datos antes de guardar", incompleteJsonDraft.reviewWarningMessage)

        // 5. JSON válido con confianza >= 0.6 y todos los campos existentes -> no muestra aviso de error
        val validDraft = GeminiFinanceService.validateAndBuildMovementDraft(
            rawJson = """
                {
                  "tipo": "TRANSFERENCIA",
                  "montoCop": 150000,
                  "fecha": "2026-11-16",
                  "categoria": "",
                  "cuenta": "Bancolombia",
                  "cuentaDestino": "Nequi",
                  "numeroCuotas": 1,
                  "nota": "Paso a Nequi",
                  "confianza": 0.92
                }
            """.trimIndent(),
            availableCategories = categories,
            availableAccounts = accounts,
            nowMillis = nowMillis
        ).getOrThrow()
        assertEquals(TransactionType.TRANSFERENCIA, validDraft.type)
        assertEquals(150_000L, validDraft.amount)
        assertEquals(2L, validDraft.matchedAccountId)
        assertEquals(1L, validDraft.matchedToAccountId)
        assertFalse(validDraft.needsReviewWarning)
        assertNull(validDraft.reviewWarningMessage)
    }

    @Test
    fun `resumen financiero no contiene digitos de tarjetas ni nombres de personas en cuentas Prestado`() {
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val accDebito = AccountEntity(
            id = 1L,
            name = "Bancolombia",
            type = AccountType.DEBITO,
            initialBalance = 3_200_000L,
            colorHex = 0xFFEAB308,
            iconName = "account_balance"
        )
        val accTarjeta = AccountEntity(
            id = 2L,
            name = "Tarjeta Nu ••••4821",
            type = AccountType.TARJETA_CREDITO,
            initialBalance = 0L,
            colorHex = 0xFF8B5CF6,
            iconName = "credit_card",
            creditLimit = 4_000_000L,
            billingCutoffDay = 15,
            paymentDueDay = 5,
            lastFourDigits = "4821"
        )
        val accPrestado = AccountEntity(
            id = 3L,
            name = "Préstamo a Carlos Ramírez",
            type = AccountType.PRESTADO,
            initialBalance = 0L,
            colorHex = 0xFF10B981,
            iconName = "handshake",
            personName = "Carlos Ramírez",
            originalAmount = 600_000L,
            receivedOrPaidAmount = 100_000L
        )
        val catComida = CategoryEntity(
            id = 10L,
            name = "Comida",
            type = CategoryType.GASTO,
            iconName = "restaurant",
            colorHex = 0xFFF59E0B,
            monthlyLimit = 800_000L
        )
        val goal = SavingGoalEntity(
            id = 1L,
            name = "Fondo de emergencia",
            targetAmount = 5_000_000L,
            savedAmount = 1_500_000L,
            deadlineTimestamp = nowMillis + 180L * 86_400_000L,
            monthlyTargetContribution = 300_000L
        )

        val accounts = listOf(accDebito, accTarjeta, accPrestado)
        val txs = (1..12).map { idx ->
            TransactionEntity(
                id = idx.toLong(),
                type = TransactionType.PAGO_PROGRAMADO,
                amount = 50_000L * idx,
                timestamp = nowMillis + idx * 86_400_000L,
                accountId = 1L,
                categoryId = 10L,
                note = "Pago pendiente #$idx",
                status = TransactionStatus.PENDIENTE
            )
        }

        val accsWithBal = accounts.map { acc ->
            AccountWithBalance(
                account = acc,
                currentBalance = AvailableToSpendCalculator.computeAccountBalance(acc, txs, includePending = false),
                projectedBalance = AvailableToSpendCalculator.computeAccountBalance(acc, txs, includePending = true),
                periodIncome = 0L,
                periodExpense = 0L
            )
        }
        val cardSummary = AvailableToSpendCalculator.computeCreditCardSummary(accTarjeta, txs, nowMillis)
        val window = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = nowMillis
        )
        val avail = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = txs,
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )

        val summary = GeminiFinanceService.buildPrivacySafeFinancialSummary(
            availableToSpend = avail,
            accountsWithBalance = accsWithBal,
            creditCardSummaries = listOf(cardSummary),
            allTransactions = txs,
            categories = listOf(catComida),
            savingGoals = listOf(goal),
            nowMillis = nowMillis
        )

        // No debe aparecer ningún dígito final de la tarjeta ("4821") ni el nombre de la persona ("Carlos Ramírez")
        assertFalse("El resumen no debe incluir los últimos 4 dígitos de la tarjeta", summary.contains("4821"))
        assertFalse("El resumen no debe incluir el nombre de la persona de una cuenta Prestado", summary.contains("Carlos Ramírez", ignoreCase = true))
        assertTrue("La cuenta Prestado debe anonimizarse como Cuenta por cobrar", summary.contains("Cuenta por cobrar #1"))

        // Debe incluir máximo los próximos 10 pagos pendientes con fecha y las metas de ahorro
        assertTrue(summary.contains("Pago pendiente #1"))
        assertTrue(summary.contains("Pago pendiente #10"))
        assertFalse("Solo debe incluir los próximos 10 pagos pendientes", summary.contains("Pago pendiente #11"))
        assertTrue("Debe incluir las metas de ahorro con su avance", summary.contains("Fondo de emergencia"))
    }

    @Test
    fun `con IA desactivada en Ajustes se hacen 0 llamadas de red a Gemini y AutoCat reutiliza notas anteriores`() = runBlocking {
        // 1. Verificar que con IA desactivada se hacen 0 llamadas de red en todos los puntos de entrada
        val disabledGeminiService = GeminiFinanceService(isAiEnabledProvider = { false })
        assertEquals(0, disabledGeminiService.networkCallsCount)

        val reportResult = disabledGeminiService.generateStructuredReport("Resumen de prueba")
        assertTrue(reportResult.isFailure)
        assertEquals(0, disabledGeminiService.networkCallsCount)

        val parseTextResult = disabledGeminiService.parseNaturalLanguageMovement(
            userText = "Gasté 20 mil en panadería con Nequi",
            availableCategories = emptyList(),
            availableAccounts = emptyList()
        )
        assertTrue(parseTextResult.isFailure)
        assertEquals(0, disabledGeminiService.networkCallsCount)

        val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val parseReceiptResult = disabledGeminiService.parseReceiptPhoto(
            bitmap = dummyBitmap,
            availableCategories = emptyList(),
            availableAccounts = emptyList()
        )
        assertTrue(parseReceiptResult.isFailure)
        assertEquals(0, disabledGeminiService.networkCallsCount)

        val catResult = disabledGeminiService.suggestCategoryFromNote(
            note = "Compra completamente nueva sin historial",
            availableCategories = listOf(
                CategoryEntity(id = 1L, name = "Comida", type = CategoryType.GASTO, iconName = "restaurant", colorHex = 0xFFF59E0B)
            ),
            previousTransactions = emptyList()
        )
        assertTrue(catResult.isFailure)
        assertEquals(0, disabledGeminiService.networkCallsCount)

        val streamedChunks = disabledGeminiService.streamFinancialChatAnswer(
            question = "¿Cuánto puedo gastar hoy?",
            aggregatedContext = "Resumen",
            previousMessages = emptyList()
        ).toList()
        assertTrue(streamedChunks.isEmpty())
        assertEquals("Con IA desactivada debe haber 0 llamadas de red a Gemini", 0, disabledGeminiService.networkCallsCount)

        // 2. Verificar que Auto-Cat encuentra primero una nota igual o muy similar en movimientos anteriores sin llamar a la red
        val catTransporte = CategoryEntity(
            id = 20L,
            name = "Transporte",
            type = CategoryType.GASTO,
            iconName = "directions_car",
            colorHex = 0xFF38BDF8
        )
        val previousTxs = listOf(
            TransactionEntity(
                id = 99L,
                type = TransactionType.GASTO,
                amount = 18_000L,
                timestamp = 1_700_000_000_000L,
                accountId = 1L,
                categoryId = 20L,
                note = "Uber al aeropuerto",
                status = TransactionStatus.PAGADO
            )
        )
        val localMatch = disabledGeminiService.suggestCategoryFromNote(
            note = "uber al aeropuerto!",
            availableCategories = listOf(catTransporte),
            previousTransactions = previousTxs
        )
        assertTrue("Debe reutilizar la categoría del movimiento anterior sin llamar a Gemini", localMatch.isSuccess)
        assertEquals(20L, localMatch.getOrNull()?.id)
        assertEquals(0, disabledGeminiService.networkCallsCount)

        // 3. Verificar que el análisis generado se persiste con su fecha en el repositorio
        assertNull(repository.loadPersistedAiReport())
        val savedReport = AiFinancialReport(
            periodTitle = "Análisis Noviembre 2026",
            whatImproved = "Bajaste gastos de restaurante un 15%.",
            whatWorsened = "Subió transporte por viajes extras.",
            recommendations = listOf("Ahorrar $100.000", "Pagar tarjeta antes del 5", "Topar domicilios en $150.000"),
            shortDailyTip = "Mantén los gastos diarios dentro de tu disponible.",
            generatedAtTimestamp = 1_763_380_000_000L
        )
        repository.savePersistedAiReport(savedReport)
        val loadedReport = repository.loadPersistedAiReport()
        assertNotNull(loadedReport)
        assertEquals("Análisis Noviembre 2026", loadedReport!!.periodTitle)
        assertEquals(1_763_380_000_000L, loadedReport.generatedAtTimestamp)
        assertEquals(3, loadedReport.recommendations.size)
    }
}


