package com.example.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Calendar
import java.util.Locale

/**
 * Convertidores de tipos Enum para Room:
 * - Tolerante a los antiguos alias EFECTIVO, BANCO y BILLETERA en AccountType.
 * - Registra en el log cualquier valor desconocido en la base de datos en lugar de convertirlo en silencio.
 */
class MiPlataTypeConverters {
    companion object {
        private const val TAG = "MiPlataTypeConverters"

        fun parseAccountTypeTolerant(value: String): AccountType {
            return when (value.trim().uppercase(Locale.ROOT)) {
                "EFECTIVO", "BANCO", "BILLETERA", "DEBITO" -> AccountType.DEBITO
                "AHORROS" -> AccountType.AHORROS
                "TARJETA_CREDITO" -> AccountType.TARJETA_CREDITO
                "PRESTAMO" -> AccountType.PRESTAMO
                "PRESTADO" -> AccountType.PRESTADO
                else -> {
                    Log.e(TAG, "Valor desconocido de AccountType en la base de datos: '$value'. Se usará DEBITO.")
                    AccountType.DEBITO
                }
            }
        }
    }

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType = parseAccountTypeTolerant(value)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Valor desconocido de TransactionType en la base de datos: '$value'.", e)
            TransactionType.GASTO
        }
    }

    @TypeConverter
    fun fromTransactionStatus(value: TransactionStatus): String = value.name

    @TypeConverter
    fun toTransactionStatus(value: String): TransactionStatus {
        return try {
            TransactionStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Valor desconocido de TransactionStatus en la base de datos: '$value'.", e)
            TransactionStatus.PAGADO
        }
    }

    @TypeConverter
    fun fromRecurrenceFrequency(value: RecurrenceFrequency): String = value.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency {
        return try {
            RecurrenceFrequency.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Valor desconocido de RecurrenceFrequency en la base de datos: '$value'.", e)
            RecurrenceFrequency.NINGUNA
        }
    }

    @TypeConverter
    fun fromCategoryType(value: CategoryType): String = value.name

    @TypeConverter
    fun toCategoryType(value: String): CategoryType {
        return try {
            CategoryType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Valor desconocido de CategoryType en la base de datos: '$value'.", e)
            CategoryType.GASTO
        }
    }
}

/**
 * Base de datos local Room v7 con exportSchema = true y migraciones reales 1->2, 2->3, 3->4, 4->5, 5->6 y 6->7.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        SavingGoalEntity::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(MiPlataTypeConverters::class)
abstract class MiPlataDatabase : RoomDatabase() {

    abstract fun dao(): MiPlataDao

    companion object {
        @Volatile
        private var INSTANCE: MiPlataDatabase? = null

        /**
         * Migración de versión 1 a versión 2 sin borrar ningún dato del usuario:
         * - Añade columnas de Tarjeta de Crédito, Préstamo y Prestado a `accounts` y normaliza EFECTIVO/BANCO/BILLETERA a DEBITO.
         * - Añade `groupName` y `monthlyLimit` a `categories` y asigna los grupos por defecto.
         * - Añade `status`, `recurrence`, `customRecurrenceDays`, `installmentNumber`, `totalInstallments`,
         *   `parentRecurringId` y `hasGeneratedNextOccurrence` a `transactions`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Ampliar tabla accounts
                db.execSQL("ALTER TABLE accounts ADD COLUMN creditLimit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN billingCutoffDay INTEGER NOT NULL DEFAULT 15")
                db.execSQL("ALTER TABLE accounts ADD COLUMN paymentDueDay INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastFourDigits TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN personName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN originalAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN receivedOrPaidAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN monthlyInstallment INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN includeInAvailableFilter INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE accounts SET type = 'DEBITO' WHERE type IN ('EFECTIVO', 'BANCO', 'BILLETERA')")

                // 2. Ampliar tabla categories con grupos y límites opcionales
                db.execSQL("ALTER TABLE categories ADD COLUMN groupName TEXT NOT NULL DEFAULT 'Estilo de vida'")
                db.execSQL("ALTER TABLE categories ADD COLUMN monthlyLimit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE categories SET groupName = 'Hogar y servicios' WHERE name IN ('Arriendo', 'Servicios', 'Mercado')")
                db.execSQL("UPDATE categories SET groupName = 'Transporte y movilidad' WHERE name IN ('Transporte', 'Gasolina')")
                db.execSQL("UPDATE categories SET groupName = 'Finanzas y negocio' WHERE name IN ('Deudas', 'Negocio')")
                db.execSQL("UPDATE categories SET groupName = 'Sin grupo' WHERE name = 'Otros' AND type = 'GASTO'")
                db.execSQL("UPDATE categories SET groupName = 'Ingresos' WHERE type = 'INGRESO'")
                // Copiar límites existentes desde la tabla budgets hacia categories.monthlyLimit
                db.execSQL(
                    """
                    UPDATE categories 
                    SET monthlyLimit = COALESCE(
                        (SELECT budgets.monthlyLimit FROM budgets WHERE budgets.categoryId = categories.id LIMIT 1),
                        0
                    )
                    """.trimIndent()
                )

                // 3. Ampliar tabla transactions con estado, recurrencia y cuotas
                db.execSQL("ALTER TABLE transactions ADD COLUMN status TEXT NOT NULL DEFAULT 'PAGADO'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrence TEXT NOT NULL DEFAULT 'NINGUNA'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN customRecurrenceDays INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE transactions ADD COLUMN installmentNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN totalInstallments INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN parentRecurringId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN hasGeneratedNextOccurrence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET recurrence = 'MENSUAL' WHERE isRecurring = 1")
            }
        }

        /**
         * Migración de versión 2 a versión 3:
         * - Añade columna `isDemo` a `accounts` con valor por defecto 0 (false) para separar cuentas de ejemplo de cuentas reales.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN isDemo INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migración de versión 3 a versión 4:
         * - Añade `dueTimestamp` (fecha de vencimiento original) y `recurrenceAnchorDay` (día ancla mensual/anual) a `transactions`.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN dueTimestamp INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceAnchorDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET dueTimestamp = timestamp WHERE dueTimestamp IS NULL")
            }
        }

        /**
         * Migración de versión 4 a versión 5:
         * - Corrige el doble descuento en cuentas PRESTAMO y PRESTADO restando de `receivedOrPaidAmount`
         *   la suma de las transferencias pagadas cuya nota empieza por "Abono" (hacia la cuenta en PRESTAMO,
         *   desde la cuenta en PRESTADO), sin bajar de 0.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE accounts
                    SET receivedOrPaidAmount = MAX(
                        0,
                        receivedOrPaidAmount - COALESCE(
                            (
                                SELECT SUM(t.amount)
                                FROM transactions t
                                WHERE t.type = 'TRANSFERENCIA'
                                  AND t.status = 'PAGADO'
                                  AND t.toAccountId = accounts.id
                                  AND t.note LIKE 'Abono%'
                            ),
                            0
                        )
                    )
                    WHERE type = 'PRESTAMO'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE accounts
                    SET receivedOrPaidAmount = MAX(
                        0,
                        receivedOrPaidAmount - COALESCE(
                            (
                                SELECT SUM(t.amount)
                                FROM transactions t
                                WHERE t.type = 'TRANSFERENCIA'
                                  AND t.status = 'PAGADO'
                                  AND t.accountId = accounts.id
                                  AND t.note LIKE 'Abono%'
                            ),
                            0
                        )
                    )
                    WHERE type = 'PRESTADO'
                    """.trimIndent()
                )
            }
        }

        /**
         * Migración de versión 5 a versión 6:
         * 1. Añade `systemKey` a `categories` y marca las categorías por defecto.
         * 2. Copia a `categories.monthlyLimit` cualquier límite que exista en `budgets` (tomando el mayor) y elimina `budgets`.
         * 3. Convierte cada fila de `debts` en una cuenta `PRESTAMO` y elimina `debts`.
         * 4. Convierte cada fila de `recurring_obligations` en un movimiento `PAGO_PROGRAMADO` pendiente con recurrencia mensual
         *    (si ya estaba pagada este mes, la primera fecha es el mes siguiente) y elimina `recurring_obligations`.
         * 5. Recrea `transactions` con clave foránea `RESTRICT` de `accountId` a `accounts(id)` e índices en
         *    `timestamp`, `accountId`, `toAccountId`, `categoryId` y `status`.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrate5To6(db, System.currentTimeMillis())
            }
        }

        fun migrate5To6(
            db: SupportSQLiteDatabase,
            nowMillis: Long = System.currentTimeMillis()
        ) {
            // 1. Añadir systemKey a categories y marcar las categorías por defecto
            db.execSQL("ALTER TABLE `categories` ADD COLUMN `systemKey` TEXT DEFAULT NULL")
            val systemKeyMappings = listOf(
                Triple("Comida", "GASTO", SystemCategoryKeys.COMIDA),
                Triple("Transporte", "GASTO", SystemCategoryKeys.TRANSPORTE),
                Triple("Arriendo", "GASTO", SystemCategoryKeys.ARRIENDO),
                Triple("Servicios", "GASTO", SystemCategoryKeys.SERVICIOS),
                Triple("Mercado", "GASTO", SystemCategoryKeys.MERCADO),
                Triple("Salud", "GASTO", SystemCategoryKeys.SALUD),
                Triple("Ocio", "GASTO", SystemCategoryKeys.OCIO),
                Triple("Ropa", "GASTO", SystemCategoryKeys.ROPA),
                Triple("Gasolina", "GASTO", SystemCategoryKeys.GASOLINA),
                Triple("Deudas", "GASTO", SystemCategoryKeys.DEUDAS),
                Triple("Negocio", "GASTO", SystemCategoryKeys.NEGOCIO_GASTO),
                Triple("Otros", "GASTO", SystemCategoryKeys.OTROS_GASTO),
                Triple("Sueldo", "INGRESO", SystemCategoryKeys.SUELDO),
                Triple("Negocio", "INGRESO", SystemCategoryKeys.NEGOCIO_INGRESO),
                Triple("Ventas", "INGRESO", SystemCategoryKeys.VENTAS),
                Triple("Otros", "INGRESO", SystemCategoryKeys.OTROS_INGRESO)
            )
            for ((catName, catType, sysKey) in systemKeyMappings) {
                db.execSQL(
                    "UPDATE `categories` SET `systemKey` = ? WHERE `name` = ? AND `type` = ? AND `systemKey` IS NULL",
                    arrayOf<Any>(sysKey, catName, catType)
                )
            }

            // Normalizar cualquier AccountType antiguo que quedara en accounts
            db.execSQL("UPDATE `accounts` SET `type` = 'DEBITO' WHERE `type` IN ('EFECTIVO', 'BANCO', 'BILLETERA')")

            // 2. Presupuestos: copiar a categories.monthlyLimit cualquier límite mayor en budgets (no demo) y eliminar budgets
            db.execSQL(
                """
                UPDATE `categories`
                SET `monthlyLimit` = MAX(
                    `categories`.`monthlyLimit`,
                    COALESCE(
                        (
                            SELECT MAX(`b`.`monthlyLimit`)
                            FROM `budgets` `b`
                            WHERE `b`.`categoryId` = `categories`.`id`
                              AND `b`.`isDemo` = 0
                        ),
                        0
                    )
                )
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS `budgets`")

            // 3. Deudas: convertir cada fila de debts en una cuenta PRESTAMO y eliminar debts
            db.execSQL(
                """
                INSERT INTO `accounts` (
                    `name`,
                    `type`,
                    `initialBalance`,
                    `colorHex`,
                    `iconName`,
                    `isDefault`,
                    `creditLimit`,
                    `billingCutoffDay`,
                    `paymentDueDay`,
                    `lastFourDigits`,
                    `personName`,
                    `originalAmount`,
                    `receivedOrPaidAmount`,
                    `monthlyInstallment`,
                    `includeInAvailableFilter`,
                    `isDemo`
                )
                SELECT
                    `creditorName`,
                    'PRESTAMO',
                    MAX(0, `remainingBalance`),
                    16096779,
                    'credit_card',
                    0,
                    0,
                    15,
                    MIN(28, MAX(1, `dueDayOfMonth`)),
                    '',
                    `creditorName`,
                    MAX(0, `totalAmount`),
                    MAX(0, `totalAmount` - `remainingBalance`),
                    MAX(0, `monthlyInstallment`),
                    0,
                    `isDemo`
                FROM `debts`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS `debts`")

            // 4. Obligaciones: convertir cada fila de recurring_obligations en un PAGO_PROGRAMADO pendiente mensual
            val existingAccountIds = mutableListOf<Long>()
            db.query("SELECT `id` FROM `accounts` ORDER BY `id` ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    existingAccountIds += cursor.getLong(0)
                }
            }

            data class LegacyObligationRow(
                val name: String,
                val amount: Long,
                val dayOfMonth: Int,
                val accountId: Long,
                val categoryId: Long,
                val lastPaidYearMonth: String,
                val isDemo: Int
            )

            val obligationRows = mutableListOf<LegacyObligationRow>()
            db.query(
                "SELECT `name`, `amount`, `dayOfMonth`, `accountId`, `categoryId`, `lastPaidYearMonth`, `isDemo` FROM `recurring_obligations`"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    obligationRows += LegacyObligationRow(
                        name = cursor.getString(0) ?: "",
                        amount = cursor.getLong(1),
                        dayOfMonth = cursor.getInt(2),
                        accountId = cursor.getLong(3),
                        categoryId = cursor.getLong(4),
                        lastPaidYearMonth = cursor.getString(5) ?: "",
                        isDemo = cursor.getInt(6)
                    )
                }
            }

            var hasTransactions = false
            db.query("SELECT 1 FROM `transactions` LIMIT 1").use { cursor ->
                hasTransactions = cursor.moveToFirst()
            }

            // Garantizar que exista al menos una cuenta si hay movimientos u obligaciones para respetar la clave foránea RESTRICT
            if (existingAccountIds.isEmpty() && (obligationRows.isNotEmpty() || hasTransactions)) {
                db.execSQL(
                    """
                    INSERT INTO `accounts` (
                        `name`, `type`, `initialBalance`, `colorHex`, `iconName`, `isDefault`,
                        `creditLimit`, `billingCutoffDay`, `paymentDueDay`, `lastFourDigits`,
                        `personName`, `originalAmount`, `receivedOrPaidAmount`, `monthlyInstallment`,
                        `includeInAvailableFilter`, `isDemo`
                    ) VALUES (
                        'Efectivo', 'DEBITO', 0, 1095809, 'payments', 1,
                        0, 15, 5, '',
                        '', 0, 0, 0,
                        1, 0
                    )
                    """.trimIndent()
                )
                db.query("SELECT `id` FROM `accounts` ORDER BY `id` ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        existingAccountIds += cursor.getLong(0)
                    }
                }
            }

            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val currentYearMonth = String.format(
                Locale.US,
                "%04d-%02d",
                nowCal.get(Calendar.YEAR),
                nowCal.get(Calendar.MONTH) + 1
            )

            for (ob in obligationRows) {
                val anchorDay = ob.dayOfMonth.coerceIn(1, 31)
                val targetCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.DAY_OF_MONTH, 1)
                    if (ob.lastPaidYearMonth.trim() == currentYearMonth) {
                        add(Calendar.MONTH, 1)
                    }
                    val maxDayInMonth = getActualMaximum(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH, minOf(anchorDay, maxDayInMonth))
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dueMillis = targetCal.timeInMillis
                val resolvedAccountId = if (ob.accountId in existingAccountIds) {
                    ob.accountId
                } else {
                    existingAccountIds.first()
                }

                db.execSQL(
                    """
                    INSERT INTO `transactions` (
                        `type`, `amount`, `timestamp`, `accountId`, `toAccountId`, `categoryId`,
                        `note`, `receiptUri`, `isRecurring`, `isInitialBalance`, `isDemo`,
                        `linkedGoalId`, `status`, `recurrence`, `customRecurrenceDays`,
                        `installmentNumber`, `totalInstallments`, `parentRecurringId`,
                        `hasGeneratedNextOccurrence`, `dueTimestamp`, `recurrenceAnchorDay`
                    ) VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, 1, 0, ?, NULL, 'PENDIENTE', 'MENSUAL', 30, 1, 1, NULL, 0, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        "PAGO_PROGRAMADO",
                        ob.amount,
                        dueMillis,
                        resolvedAccountId,
                        ob.categoryId,
                        ob.name.ifBlank { "Pago programado" },
                        ob.isDemo,
                        dueMillis,
                        anchorDay
                    )
                )
            }
            db.execSQL("DROP TABLE IF EXISTS `recurring_obligations`")

            // Si algún movimiento antiguo apuntaba a una cuenta inexistente, reasignarlo a la primera cuenta existente
            if (existingAccountIds.isNotEmpty()) {
                val fallbackAccId = existingAccountIds.first()
                db.execSQL(
                    """
                    UPDATE `transactions`
                    SET `accountId` = $fallbackAccId
                    WHERE `accountId` NOT IN (SELECT `id` FROM `accounts`)
                    """.trimIndent()
                )
            }

            // 5. Recrear transactions con clave foránea RESTRICT a accounts(id) y los 5 índices requeridos
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transactions_new` (
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
                    `status` TEXT NOT NULL,
                    `recurrence` TEXT NOT NULL,
                    `customRecurrenceDays` INTEGER NOT NULL,
                    `installmentNumber` INTEGER NOT NULL,
                    `totalInstallments` INTEGER NOT NULL,
                    `parentRecurringId` INTEGER,
                    `hasGeneratedNextOccurrence` INTEGER NOT NULL,
                    `dueTimestamp` INTEGER,
                    `recurrenceAnchorDay` INTEGER NOT NULL,
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `transactions_new` (
                    `id`, `type`, `amount`, `timestamp`, `accountId`, `toAccountId`, `categoryId`,
                    `note`, `receiptUri`, `isRecurring`, `isInitialBalance`, `isDemo`, `linkedGoalId`,
                    `status`, `recurrence`, `customRecurrenceDays`, `installmentNumber`, `totalInstallments`,
                    `parentRecurringId`, `hasGeneratedNextOccurrence`, `dueTimestamp`, `recurrenceAnchorDay`
                )
                SELECT
                    `id`, `type`, `amount`, `timestamp`, `accountId`, `toAccountId`, `categoryId`,
                    `note`, `receiptUri`, `isRecurring`, `isInitialBalance`, `isDemo`, `linkedGoalId`,
                    `status`, `recurrence`, `customRecurrenceDays`, `installmentNumber`, `totalInstallments`,
                    `parentRecurringId`, `hasGeneratedNextOccurrence`, `dueTimestamp`, `recurrenceAnchorDay`
                FROM `transactions`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `transactions`")
            db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_toAccountId` ON `transactions` (`toAccountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
        }

        /**
         * Migración de versión 6 a versión 7:
         * - Añade columna `isArchived` a `accounts` (con valor por defecto 0 / false) para permitir archivar
         *   cuentas que tienen movimientos históricos sin borrarlas ni incluirlas en selectores o totales.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): MiPlataDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MiPlataDatabase::class.java,
                    "mi_plata_colombia.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Cuenta inicial mínima por defecto cuando aún no se ha completado el onboarding.
         * No crea tarjetas de crédito, cuentas de ahorros ni bancos inventados.
         */
        fun defaultAccounts(): List<AccountEntity> = listOf(
            AccountEntity(
                name = "Efectivo",
                type = AccountType.DEBITO,
                initialBalance = 0L,
                colorHex = 0xFF10B981,
                iconName = "payments",
                isDefault = true
            )
        )

        /**
         * Categorías precargadas con su systemKey y grupo (sin ids numéricos fijos).
         */
        fun defaultCategories(): List<CategoryEntity> = listOf(
            // Grupo: Estilo de vida
            CategoryEntity(name = "Comida", iconName = "restaurant", colorHex = 0xFFF97316, type = CategoryType.GASTO, groupName = "Estilo de vida", monthlyLimit = 400_000L, systemKey = SystemCategoryKeys.COMIDA),
            CategoryEntity(name = "Salud", iconName = "medical_services", colorHex = 0xFFEF4444, type = CategoryType.GASTO, groupName = "Estilo de vida", monthlyLimit = 0L, systemKey = SystemCategoryKeys.SALUD),
            CategoryEntity(name = "Ocio", iconName = "movie", colorHex = 0xFFEC4899, type = CategoryType.GASTO, groupName = "Estilo de vida", monthlyLimit = 180_000L, systemKey = SystemCategoryKeys.OCIO),
            CategoryEntity(name = "Ropa", iconName = "checkroom", colorHex = 0xFFA855F7, type = CategoryType.GASTO, groupName = "Estilo de vida", monthlyLimit = 0L, systemKey = SystemCategoryKeys.ROPA),
            // Grupo: Hogar y servicios
            CategoryEntity(name = "Arriendo", iconName = "home", colorHex = 0xFF8B5CF6, type = CategoryType.GASTO, groupName = "Hogar y servicios", monthlyLimit = 1_150_000L, systemKey = SystemCategoryKeys.ARRIENDO),
            CategoryEntity(name = "Servicios", iconName = "bolt", colorHex = 0xFFEAB308, type = CategoryType.GASTO, groupName = "Hogar y servicios", monthlyLimit = 220_000L, systemKey = SystemCategoryKeys.SERVICIOS),
            CategoryEntity(name = "Mercado", iconName = "shopping_cart", colorHex = 0xFF10B981, type = CategoryType.GASTO, groupName = "Hogar y servicios", monthlyLimit = 500_000L, systemKey = SystemCategoryKeys.MERCADO),
            // Grupo: Transporte y movilidad
            CategoryEntity(name = "Transporte", iconName = "directions_bus", colorHex = 0xFF3B82F6, type = CategoryType.GASTO, groupName = "Transporte y movilidad", monthlyLimit = 180_000L, systemKey = SystemCategoryKeys.TRANSPORTE),
            CategoryEntity(name = "Gasolina", iconName = "local_gas_station", colorHex = 0xFFF59E0B, type = CategoryType.GASTO, groupName = "Transporte y movilidad", monthlyLimit = 120_000L, systemKey = SystemCategoryKeys.GASOLINA),
            // Grupo: Finanzas y negocio
            CategoryEntity(name = "Deudas", iconName = "credit_card", colorHex = 0xFFDC2626, type = CategoryType.GASTO, groupName = "Finanzas y negocio", monthlyLimit = 0L, systemKey = SystemCategoryKeys.DEUDAS),
            CategoryEntity(name = "Negocio", iconName = "storefront", colorHex = 0xFF06B6D4, type = CategoryType.GASTO, groupName = "Finanzas y negocio", monthlyLimit = 0L, systemKey = SystemCategoryKeys.NEGOCIO_GASTO),
            // Grupo: Sin grupo
            CategoryEntity(name = "Otros", iconName = "category", colorHex = 0xFF64748B, type = CategoryType.GASTO, groupName = "Sin grupo", monthlyLimit = 0L, systemKey = SystemCategoryKeys.OTROS_GASTO),
            // Grupo: Ingresos
            CategoryEntity(name = "Sueldo", iconName = "work", colorHex = 0xFF10B981, type = CategoryType.INGRESO, groupName = "Ingresos", monthlyLimit = 0L, systemKey = SystemCategoryKeys.SUELDO),
            CategoryEntity(name = "Negocio", iconName = "store", colorHex = 0xFF38BDF8, type = CategoryType.INGRESO, groupName = "Ingresos", monthlyLimit = 0L, systemKey = SystemCategoryKeys.NEGOCIO_INGRESO),
            CategoryEntity(name = "Ventas", iconName = "sell", colorHex = 0xFF14B8A6, type = CategoryType.INGRESO, groupName = "Ingresos", monthlyLimit = 0L, systemKey = SystemCategoryKeys.VENTAS),
            CategoryEntity(name = "Otros", iconName = "add_card", colorHex = 0xFF22C55E, type = CategoryType.INGRESO, groupName = "Ingresos", monthlyLimit = 0L, systemKey = SystemCategoryKeys.OTROS_INGRESO)
        )
    }
}
