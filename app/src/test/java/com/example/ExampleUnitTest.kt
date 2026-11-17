package com.example

import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.PeriodMode
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CopFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Pruebas unitarias exhaustivas para:
 * 1. Disponible para gastar (excluyendo Ahorros, sumando ingresos pendientes y restando pagos pendientes + pago de tarjeta).
 * 2. Saldo proyectado por día en el Calendario (pagadas vs pendientes).
 * 3. Saldo de tarjeta de crédito con compras a cuotas, gastos y pagos de tarjeta (sin duplicar gasto ni bajar efectivo al comprar con tarjeta).
 */
class ExampleUnitTest {

    @Test
    fun `disponible para gastar excluye ahorros y resta pago de tarjeta de credito`() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val window = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = nowCal.timeInMillis
        )

        val accounts = listOf(
            // Cuenta Débito (entra en Balance actual): $2.000.000 inicial
            AccountEntity(
                id = 1L,
                name = "Nequi",
                type = AccountType.DEBITO,
                initialBalance = 2_000_000L,
                colorHex = 0xFFEC4899,
                iconName = "smartphone"
            ),
            // Cuenta Ahorros (NUNCA cuenta como disponible): $5.000.000
            AccountEntity(
                id = 2L,
                name = "Cajita Ahorros Nu",
                type = AccountType.AHORROS,
                initialBalance = 5_000_000L,
                colorHex = 0xFF38BDF8,
                iconName = "savings"
            ),
            // Tarjeta de Crédito con pago el día 20 (vence dentro del período)
            AccountEntity(
                id = 3L,
                name = "Tarjeta Nu",
                type = AccountType.TARJETA_CREDITO,
                initialBalance = 0L,
                creditLimit = 3_000_000L,
                paymentDueDay = 20,
                colorHex = 0xFFA855F7,
                iconName = "credit_card"
            )
        )

        val transactions = listOf(
            // Gasto ya PAGADO desde Nequi: -$300.000 -> Balance actual débito = $1.700.000
            TransactionEntity(
                id = 1L,
                type = TransactionType.GASTO,
                amount = 300_000L,
                timestamp = nowCal.timeInMillis - 86_400_000L,
                accountId = 1L,
                status = TransactionStatus.PAGADO
            ),
            // Ingreso PENDIENTE en Nequi dentro del período: +$800.000 -> Balance proyectado = $2.500.000
            TransactionEntity(
                id = 2L,
                type = TransactionType.INGRESO,
                amount = 800_000L,
                timestamp = nowCal.timeInMillis + 2 * 86_400_000L,
                accountId = 1L,
                status = TransactionStatus.PENDIENTE
            ),
            // Pago programado PENDIENTE (Servicios) en Nequi: $200.000
            TransactionEntity(
                id = 3L,
                type = TransactionType.PAGO_PROGRAMADO,
                amount = 200_000L,
                timestamp = nowCal.timeInMillis + 3 * 86_400_000L,
                accountId = 1L,
                status = TransactionStatus.PENDIENTE
            ),
            // Compra con Tarjeta de Crédito por $450.000 (sube deuda de tarjeta, NO baja saldo actual de Nequi)
            TransactionEntity(
                id = 4L,
                type = TransactionType.GASTO,
                amount = 450_000L,
                timestamp = nowCal.timeInMillis - 43_200_000L,
                accountId = 3L,
                status = TransactionStatus.PAGADO
            )
        )

        // Con ajuste de restar pago de tarjeta ACTIVADO (por defecto):
        val withCardDeduction = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = transactions,
            periodWindow = window,
            deductCreditCardPayment = true
        )

        // Balance actual = 2.000.000 - 300.000 = 1.700.000 (los 5M de Ahorros quedan excluidos, y la compra con TC no bajó Nequi)
        assertEquals(1_700_000L, withCardDeduction.currentDebitBalance)
        // Balance proyectado = 1.700.000 + 800.000 = 2.500.000
        assertEquals(2_500_000L, withCardDeduction.projectedBalance)
        // Pago de tarjeta que vence en el período = $450.000
        assertEquals(450_000L, withCardDeduction.creditCardPaymentDueInPeriod)
        // Gasto proyectado = 200.000 (pago pendiente) + 450.000 (pago de tarjeta) = 650.000
        assertEquals(650_000L, withCardDeduction.projectedExpense)
        // Restante = 2.500.000 - 650.000 = 1.850.000
        assertEquals(1_850_000L, withCardDeduction.availableForPeriod)
        assertFalse(withCardDeduction.isOverBudget)

        // Con ajuste de restar pago de tarjeta DESACTIVADO:
        val withoutCardDeduction = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = accounts,
            allTransactions = transactions,
            periodWindow = window,
            deductCreditCardPayment = false
        )
        assertEquals(200_000L, withoutCardDeduction.projectedExpense)
        assertEquals(2_300_000L, withoutCardDeduction.availableForPeriod)
    }

    @Test
    fun `saldo proyectado por dia en calendario refleja transacciones pendientes dia a dia`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nu Débito",
            type = AccountType.DEBITO,
            initialBalance = 1_000_000L,
            colorHex = 0xFF8B5CF6,
            iconName = "account_balance"
        )

        fun dayMillis(day: Int): Long {
            return (cal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 14)
            }.timeInMillis
        }

        val txs = listOf(
            // Día 3: Gasto pagado de $200.000 -> cierra día 3 en $800.000
            TransactionEntity(
                id = 1L,
                type = TransactionType.GASTO,
                amount = 200_000L,
                timestamp = dayMillis(3),
                accountId = 1L,
                status = TransactionStatus.PAGADO
            ),
            // Día 10: Pago programado pendiente de $300.000 -> cierra día 10 en $500.000
            TransactionEntity(
                id = 2L,
                type = TransactionType.PAGO_PROGRAMADO,
                amount = 300_000L,
                timestamp = dayMillis(10),
                accountId = 1L,
                status = TransactionStatus.PENDIENTE
            ),
            // Día 15: Ingreso pendiente (Quincena) de $1.500.000 -> cierra día 15 en $2.000.000
            TransactionEntity(
                id = 3L,
                type = TransactionType.INGRESO,
                amount = 1_500_000L,
                timestamp = dayMillis(15),
                accountId = 1L,
                status = TransactionStatus.PENDIENTE
            )
        )

        // Las pendientes NO afectan el balance actual de la cuenta
        val currentBalance = AvailableToSpendCalculator.computeAccountBalance(
            account = debitAccount,
            allTransactions = txs,
            includePending = false
        )
        assertEquals(800_000L, currentBalance)

        // El calendario proyecta los saldos al cierre de cada día
        val dailyProjections = AvailableToSpendCalculator.computeDailyProjectedBalancesForMonth(
            year = 2026,
            monthZeroBased = Calendar.NOVEMBER,
            accounts = listOf(debitAccount),
            allTransactions = txs,
            forCreditCards = false,
            nowMillis = cal.timeInMillis
        )

        val day3 = dailyProjections.first { it.dayOfMonth == 3 }
        val day10 = dailyProjections.first { it.dayOfMonth == 10 }
        val day15 = dailyProjections.first { it.dayOfMonth == 15 }
        val day30 = dailyProjections.first { it.dayOfMonth == 30 }

        assertEquals(800_000L, day3.projectedClosingBalance)
        assertEquals(500_000L, day10.projectedClosingBalance)
        assertEquals(2_000_000L, day15.projectedClosingBalance)
        assertEquals(2_000_000L, day30.projectedClosingBalance)
    }

    @Test
    fun `saldo de tarjeta de credito con compras a cuotas y pago de tarjeta sin duplicar gasto`() {
        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nequi",
            type = AccountType.DEBITO,
            initialBalance = 1_500_000L,
            colorHex = 0xFFEC4899,
            iconName = "smartphone"
        )
        val creditCard = AccountEntity(
            id = 5L,
            name = "Tarjeta Nu",
            type = AccountType.TARJETA_CREDITO,
            initialBalance = 0L,
            creditLimit = 2_000_000L,
            billingCutoffDay = 20,
            paymentDueDay = 5,
            lastFourDigits = "4821",
            colorHex = 0xFFA855F7,
            iconName = "credit_card"
        )

        // Compra a 3 cuotas de $300.000 cada una ($900.000 total) + un Pago de tarjeta de $300.000 desde Nequi
        val txs = listOf(
            TransactionEntity(
                id = 10L,
                type = TransactionType.COMPRA_CUOTAS,
                amount = 300_000L,
                accountId = 5L,
                categoryId = 8L,
                status = TransactionStatus.PAGADO,
                installmentNumber = 1,
                totalInstallments = 3
            ),
            TransactionEntity(
                id = 11L,
                type = TransactionType.COMPRA_CUOTAS,
                amount = 300_000L,
                accountId = 5L,
                categoryId = 8L,
                status = TransactionStatus.PENDIENTE,
                installmentNumber = 2,
                totalInstallments = 3
            ),
            TransactionEntity(
                id = 12L,
                type = TransactionType.COMPRA_CUOTAS,
                amount = 300_000L,
                accountId = 5L,
                categoryId = 8L,
                status = TransactionStatus.PENDIENTE,
                installmentNumber = 3,
                totalInstallments = 3
            ),
            // Pago de la primera cuota ($300.000) desde Nequi (1L) hacia Tarjeta Nu (5L)
            TransactionEntity(
                id = 13L,
                type = TransactionType.PAGO_TARJETA,
                amount = 300_000L,
                accountId = 1L,
                toAccountId = 5L,
                status = TransactionStatus.PAGADO
            )
        )

        // 1. La tarjeta tenía $900.000 en cuotas y recibió un abono de $300.000 -> deuda restante = $600.000
        val cardSummary = AvailableToSpendCalculator.computeCreditCardSummary(creditCard, txs)
        assertEquals(600_000L, cardSummary.currentDebt)
        // Cupo disponible = 2.000.000 - 600.000 = 1.400.000 (30% usado)
        assertEquals(1_400_000L, cardSummary.availableCredit)
        assertEquals(30, cardSummary.usedPercentage)

        // 2. El efectivo en Nequi SOLO bajó con el Pago de tarjeta ($300.000), no con las compras de la tarjeta
        val nequiBalance = AvailableToSpendCalculator.computeAccountBalance(debitAccount, txs, includePending = false)
        assertEquals(1_200_000L, nequiBalance)

        // 3. El Pago de tarjeta NO cuenta como gasto en los totales de categorías
        assertFalse(AvailableToSpendCalculator.isExpenseTransactionForTotals(txs.last()))
        assertTrue(AvailableToSpendCalculator.isExpenseTransactionForTotals(txs.first()))
    }

    @Test
    fun `formato moneda COP usa punto de miles y sin decimales`() {
        assertEquals("$1.250.000", CopFormatter.format(1_250_000L))
        assertEquals("-$45.000", CopFormatter.format(-45_000L))
        assertEquals("$ ••••••", CopFormatter.format(1_250_000L, hideBalance = true))
    }
}
