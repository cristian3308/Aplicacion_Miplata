package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Convertidores de tipos Enum para Room (con retrocompatibilidad v1 -> v2).
 */
class MiPlataTypeConverters {
    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.normalized().name

    @TypeConverter
    fun toAccountType(value: String): AccountType =
        runCatching { AccountType.valueOf(value).normalized() }.getOrDefault(AccountType.DEBITO)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        runCatching { TransactionType.valueOf(value) }.getOrDefault(TransactionType.GASTO)

    @TypeConverter
    fun fromTransactionStatus(value: TransactionStatus): String = value.name

    @TypeConverter
    fun toTransactionStatus(value: String): TransactionStatus =
        runCatching { TransactionStatus.valueOf(value) }.getOrDefault(TransactionStatus.PAGADO)

    @TypeConverter
    fun fromRecurrenceFrequency(value: RecurrenceFrequency): String = value.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency =
        runCatching { RecurrenceFrequency.valueOf(value) }.getOrDefault(RecurrenceFrequency.NINGUNA)

    @TypeConverter
    fun fromCategoryType(value: CategoryType): String = value.name

    @TypeConverter
    fun toCategoryType(value: String): CategoryType =
        runCatching { CategoryType.valueOf(value) }.getOrDefault(CategoryType.GASTO)
}

/**
 * Base de datos local Room v2 con migración real MIGRATION_1_2 que preserva 100% los datos del usuario.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        SavingGoalEntity::class,
        RecurringObligationEntity::class,
        DebtEntity::class
    ],
    version = 2,
    exportSchema = false
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

        fun getInstance(context: Context): MiPlataDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MiPlataDatabase::class.java,
                    "mi_plata_colombia.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Cuentas por defecto solicitadas (Débito: Efectivo, Nu, Nequi; Ahorros: Cajita Nu; Tarjeta: TC Nu).
         */
        fun defaultAccounts(): List<AccountEntity> = listOf(
            AccountEntity(
                id = 1L,
                name = "Efectivo",
                type = AccountType.DEBITO,
                initialBalance = 0L,
                colorHex = 0xFF10B981,
                iconName = "payments",
                isDefault = true
            ),
            AccountEntity(
                id = 2L,
                name = "Nu Débito",
                type = AccountType.DEBITO,
                initialBalance = 0L,
                colorHex = 0xFF8B5CF6,
                iconName = "account_balance",
                isDefault = true
            ),
            AccountEntity(
                id = 3L,
                name = "Nequi",
                type = AccountType.DEBITO,
                initialBalance = 0L,
                colorHex = 0xFFEC4899,
                iconName = "smartphone",
                isDefault = true
            ),
            AccountEntity(
                id = 4L,
                name = "Cajita Ahorros Nu",
                type = AccountType.AHORROS,
                initialBalance = 0L,
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
                creditLimit = 3_500_000L,
                billingCutoffDay = 20,
                paymentDueDay = 5,
                lastFourDigits = "4821",
                includeInAvailableFilter = false
            )
        )

        /**
         * Categorías precargadas con sus grupos ("Estilo de vida", "Hogar y servicios", etc.).
         */
        fun defaultCategories(): List<CategoryEntity> = listOf(
            // Grupo: Estilo de vida
            CategoryEntity(1L, "Comida", "restaurant", 0xFFF97316, CategoryType.GASTO, "Estilo de vida", 400_000L),
            CategoryEntity(6L, "Salud", "medical_services", 0xFFEF4444, CategoryType.GASTO, "Estilo de vida", 0L),
            CategoryEntity(7L, "Ocio", "movie", 0xFFEC4899, CategoryType.GASTO, "Estilo de vida", 180_000L),
            CategoryEntity(8L, "Ropa", "checkroom", 0xFFA855F7, CategoryType.GASTO, "Estilo de vida", 0L),
            // Grupo: Hogar y servicios
            CategoryEntity(3L, "Arriendo", "home", 0xFF8B5CF6, CategoryType.GASTO, "Hogar y servicios", 1_150_000L),
            CategoryEntity(4L, "Servicios", "bolt", 0xFFEAB308, CategoryType.GASTO, "Hogar y servicios", 220_000L),
            CategoryEntity(5L, "Mercado", "shopping_cart", 0xFF10B981, CategoryType.GASTO, "Hogar y servicios", 500_000L),
            // Grupo: Transporte y movilidad
            CategoryEntity(2L, "Transporte", "directions_bus", 0xFF3B82F6, CategoryType.GASTO, "Transporte y movilidad", 180_000L),
            CategoryEntity(9L, "Gasolina", "local_gas_station", 0xFFF59E0B, CategoryType.GASTO, "Transporte y movilidad", 120_000L),
            // Grupo: Finanzas y negocio
            CategoryEntity(10L, "Deudas", "credit_card", 0xFFDC2626, CategoryType.GASTO, "Finanzas y negocio", 0L),
            CategoryEntity(11L, "Negocio", "storefront", 0xFF06B6D4, CategoryType.GASTO, "Finanzas y negocio", 0L),
            // Grupo: Sin grupo
            CategoryEntity(12L, "Otros", "category", 0xFF64748B, CategoryType.GASTO, "Sin grupo", 0L),
            // Grupo: Ingresos
            CategoryEntity(13L, "Sueldo", "work", 0xFF10B981, CategoryType.INGRESO, "Ingresos", 0L),
            CategoryEntity(14L, "Negocio", "store", 0xFF38BDF8, CategoryType.INGRESO, "Ingresos", 0L),
            CategoryEntity(15L, "Ventas", "sell", 0xFF14B8A6, CategoryType.INGRESO, "Ingresos", 0L),
            CategoryEntity(16L, "Otros", "add_card", 0xFF22C55E, CategoryType.INGRESO, "Ingresos", 0L)
        )
    }
}
