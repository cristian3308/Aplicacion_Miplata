package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tipos de cuenta ampliados:
 * - DEBITO: incluye efectivo, cuentas bancarias de débito y billeteras digitales (Nequi, Daviplata).
 * - AHORROS: dinero apartado que NUNCA cuenta como disponible para gastar.
 * - TARJETA_CREDITO: cupo total, día de corte, día de pago, últimos 4 dígitos.
 * - PRESTAMO: lo que yo debo (saldo pendiente, cuota mensual, monto original).
 * - PRESTADO: lo que me deben a mí (persona, monto original, monto recibido).
 */
enum class AccountType(val label: String) {
    DEBITO("Débito"),
    AHORROS("Ahorros"),
    TARJETA_CREDITO("Tarjeta de crédito"),
    PRESTAMO("Préstamo"),
    PRESTADO("Prestado"),
    // Alias de retrocompatibilidad para migración transparente desde v1
    EFECTIVO("Débito"),
    BANCO("Débito"),
    BILLETERA("Débito");

    fun normalized(): AccountType = when (this) {
        EFECTIVO, BANCO, BILLETERA, DEBITO -> DEBITO
        AHORROS -> AHORROS
        TARJETA_CREDITO -> TARJETA_CREDITO
        PRESTAMO -> PRESTAMO
        PRESTADO -> PRESTADO
    }
}

/**
 * Los 8 tipos de transacción de MiPlata:
 * - GASTO: una compra o pago ya hecho.
 * - PAGO_PROGRAMADO: un pago que debo hacer (suscripciones, arriendo, servicios); queda pendiente y puede ser recurrente.
 * - INGRESO: salario, quincena, ventas u otro ingreso; puede ser recurrente.
 * - TRANSFERENCIA: movimiento entre mis cuentas; no cuenta como gasto ni como ingreso.
 * - REEMBOLSO: dinero devuelto por una compra; resta del gasto de su categoría.
 * - COMPRA_CUOTAS: compra con tarjeta de crédito en N cuotas; genera las cuotas mensuales pendientes.
 * - PAGO_TARJETA: abono a la tarjeta desde una cuenta de débito; baja el saldo de la tarjeta y no se cuenta dos veces como gasto.
 * - CASHBACK: recompensa recibida de una tarjeta.
 */
enum class TransactionType(val label: String, val shortDescription: String) {
    GASTO("Gasto", "Una compra o pago ya hecho."),
    PAGO_PROGRAMADO("Pago", "Suscripciones, arriendo o servicios; queda pendiente y puede ser recurrente."),
    INGRESO("Ingreso", "Salario, quincena, ventas u otro ingreso; puede ser recurrente."),
    TRANSFERENCIA("Transferencia", "Movimiento entre mis cuentas; no cuenta como gasto ni ingreso."),
    REEMBOLSO("Reembolso", "Dinero devuelto por una compra; resta del gasto de su categoría."),
    COMPRA_CUOTAS("Compra a cuotas", "Compra con tarjeta de crédito en N cuotas mensuales."),
    PAGO_TARJETA("Pago de tarjeta", "Abono a tarjeta desde cuenta débito; baja la deuda sin duplicar gasto."),
    CASHBACK("Cashback", "Recompensa recibida de una tarjeta.")
}

/**
 * Estado de cada transacción:
 * - PAGADO: afecta el balance actual de las cuentas.
 * - PENDIENTE: solo afecta los saldos proyectados y aparece en "Próximo" y Calendario.
 */
enum class TransactionStatus(val label: String) {
    PAGADO("Pagado"),
    PENDIENTE("Pendiente")
}

/**
 * Frecuencia de recurrencia de una transacción.
 */
enum class RecurrenceFrequency(val label: String, val daysApproximate: Int) {
    NINGUNA("No recurrente", 0),
    SEMANAL("Semanal", 7),
    QUINCENAL("Quincenal", 15),
    MENSUAL("Mensual", 30),
    ANUAL("Anual", 365),
    PERSONALIZADA("Personalizada", 30)
}

/**
 * Tipo de categoría (Ingreso o Gasto).
 */
enum class CategoryType(val label: String) {
    INGRESO("Ingreso"),
    GASTO("Gasto")
}

/**
 * Período para la tarjeta "Disponible para gastar".
 */
enum class PeriodMode(val label: String) {
    MENSUAL("Este mes"),
    QUINCENAL("Esta quincena"),
    PROXIMOS_30_DIAS("Próximos 30 días"),
    PERSONALIZADO("Personalizado")
}

/**
 * Entidad Room para Cuentas (Débito, Ahorros, Tarjeta de crédito, Préstamo, Prestado).
 * El saldo siempre se deriva de las transacciones pagadas + saldo/deuda base inicial.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val initialBalance: Long = 0L,
    val colorHex: Long,
    val iconName: String,
    val isDefault: Boolean = false,
    // Campos específicos de Tarjeta de Crédito
    val creditLimit: Long = 0L,
    val billingCutoffDay: Int = 15,
    val paymentDueDay: Int = 5,
    val lastFourDigits: String = "",
    // Campos específicos de Préstamo (lo que debo) y Prestado (lo que me deben)
    val personName: String = "",
    val originalAmount: Long = 0L,
    val receivedOrPaidAmount: Long = 0L,
    val monthlyInstallment: Long = 0L,
    val includeInAvailableFilter: Boolean = true
)

/**
 * Entidad Room para Categorías agrupadas en grupos (Estilo de vida, Hogar y servicios, etc.)
 * con límite mensual opcional.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconName: String,
    val colorHex: Long,
    val type: CategoryType,
    val groupName: String = "Estilo de vida",
    val monthlyLimit: Long = 0L // 0L = "Sin límite"
)

/**
 * Entidad Room para Transacciones con soporte de estado (PAGADO / PENDIENTE),
 * recurrencia automática y cuotas de tarjeta de crédito.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amount: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val accountId: Long,
    val toAccountId: Long? = null, // Usado en TRANSFERENCIA y PAGO_TARJETA (cuenta destino / tarjeta)
    val categoryId: Long? = null,
    val note: String = "",
    val receiptUri: String? = null,
    val isRecurring: Boolean = false,
    val isInitialBalance: Boolean = false,
    val isDemo: Boolean = false,
    val linkedGoalId: Long? = null,
    val linkedObligationId: Long? = null,
    val linkedDebtId: Long? = null,
    // Nuevos campos en v2 (migrados sin pérdida de datos)
    val status: TransactionStatus = TransactionStatus.PAGADO,
    val recurrence: RecurrenceFrequency = RecurrenceFrequency.NINGUNA,
    val customRecurrenceDays: Int = 30,
    val installmentNumber: Int = 1,
    val totalInstallments: Int = 1,
    val parentRecurringId: Long? = null,
    val hasGeneratedNextOccurrence: Boolean = false
)

/**
 * Entidad Room para Presupuestos mensuales por categoría (sincronizada con CategoryEntity.monthlyLimit).
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val monthlyLimit: Long,
    val isDemo: Boolean = false
)

/**
 * Entidad Room para Metas de Ahorro (accesibles desde el menú superior).
 */
@Entity(tableName = "saving_goals")
data class SavingGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Long,
    val savedAmount: Long = 0L,
    val deadlineTimestamp: Long,
    val monthlyTargetContribution: Long,
    val colorHex: Long = 0xFF38BDF8,
    val iconName: String = "savings",
    val isDemo: Boolean = false
)

/**
 * Entidad Room para Obligaciones Fijas mensuales.
 */
@Entity(tableName = "recurring_obligations")
data class RecurringObligationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Long,
    val dayOfMonth: Int,
    val accountId: Long,
    val categoryId: Long,
    val lastPaidYearMonth: String = "",
    val isDemo: Boolean = false
)

/**
 * Entidad Room para Deudas adicionales.
 */
@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creditorName: String,
    val totalAmount: Long,
    val monthlyInstallment: Long,
    val remainingBalance: Long,
    val dueDayOfMonth: Int = 15,
    val isDemo: Boolean = false
)
