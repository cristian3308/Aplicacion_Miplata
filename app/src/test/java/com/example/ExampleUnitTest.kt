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
            deductCreditCardPayment = true,
            nowMillis = nowCal.timeInMillis
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
            deductCreditCardPayment = false,
            nowMillis = nowCal.timeInMillis
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

    @Test
    fun `tarjeta con corte 15 y pago dia 5 no se resta el dia 20 pero si se resta el dia 2 en Este mes`() {
        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nu Débito",
            type = AccountType.DEBITO,
            initialBalance = 2_000_000L,
            colorHex = 0xFF8B5CF6,
            iconName = "account_balance"
        )
        val creditCard = AccountEntity(
            id = 5L,
            name = "Tarjeta Nu",
            type = AccountType.TARJETA_CREDITO,
            initialBalance = 0L,
            creditLimit = 3_000_000L,
            billingCutoffDay = 15,
            paymentDueDay = 5,
            colorHex = 0xFFA855F7,
            iconName = "credit_card"
        )
        val oct10PurchaseMillis = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 10, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val txs = listOf(
            TransactionEntity(
                id = 1L,
                type = TransactionType.GASTO,
                amount = 400_000L,
                timestamp = oct10PurchaseMillis,
                accountId = 5L,
                status = TransactionStatus.PAGADO
            )
        )

        // Caso 1: Hoy día 20 de noviembre, período "Este mes" -> próximo pago es el 5 de diciembre (fuera del período), NO se resta
        val day20Millis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val windowDay20 = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = day20Millis
        )
        val resultDay20 = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(debitAccount, creditCard),
            allTransactions = txs,
            periodWindow = windowDay20,
            deductCreditCardPayment = true,
            nowMillis = day20Millis
        )
        assertEquals(0L, resultDay20.creditCardPaymentDueInPeriod)
        assertEquals(2_000_000L, resultDay20.availableForPeriod)

        // Caso 2: Hoy día 2 de noviembre, mismo caso -> próximo pago es el 5 de noviembre (dentro del período), SÍ se resta
        val day2Millis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 2, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val windowDay2 = AvailableToSpendCalculator.resolvePeriodWindow(
            mode = PeriodMode.MENSUAL,
            nowMillis = day2Millis
        )
        val resultDay2 = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(debitAccount, creditCard),
            allTransactions = txs,
            periodWindow = windowDay2,
            deductCreditCardPayment = true,
            nowMillis = day2Millis
        )
        assertEquals(400_000L, resultDay2.creditCardPaymentDueInPeriod)
        assertEquals(1_600_000L, resultDay2.availableForPeriod)
    }

    @Test
    fun `compra de 1200000 a 12 cuotas hecha este mes baja disponible como maximo una cuota y currentDebt muestra 1200000`() {
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nu Débito",
            type = AccountType.DEBITO,
            initialBalance = 3_000_000L,
            colorHex = 0xFF8B5CF6,
            iconName = "account_balance"
        )
        // Tarjeta con corte día 15 y pago día 25 (vence este mismo mes)
        val creditCard = AccountEntity(
            id = 5L,
            name = "Tarjeta Nu",
            type = AccountType.TARJETA_CREDITO,
            initialBalance = 0L,
            creditLimit = 5_000_000L,
            billingCutoffDay = 15,
            paymentDueDay = 25,
            colorHex = 0xFFA855F7,
            iconName = "credit_card"
        )

        // Generar las 12 cuotas de $100.000 ($1.200.000 total): cuota 1 este mes (PAGADO), cuotas 2..12 en meses futuros (PENDIENTE)
        val installments = (1..12).map { idx ->
            val instCal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.MONTH, idx - 1)
            }
            TransactionEntity(
                id = idx.toLong(),
                type = TransactionType.COMPRA_CUOTAS,
                amount = 100_000L,
                timestamp = instCal.timeInMillis,
                dueTimestamp = instCal.timeInMillis,
                accountId = 5L,
                status = if (idx == 1) TransactionStatus.PAGADO else TransactionStatus.PENDIENTE,
                installmentNumber = idx,
                totalInstallments = 12
            )
        }

        val summary = AvailableToSpendCalculator.computeCreditCardSummary(
            cardAccount = creditCard,
            allTransactions = installments,
            nowMillis = nowMillis
        )
        assertEquals("currentDebt debe mostrar el total de las 12 cuotas ($1.200.000)", 1_200_000L, summary.currentDebt)
        assertEquals("dueInPeriod solo incluye la cuota 1 que entró en el corte ($100.000)", 100_000L, summary.dueInPeriod)

        val window = AvailableToSpendCalculator.resolvePeriodWindow(PeriodMode.MENSUAL, nowMillis = nowMillis)
        val available = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(debitAccount, creditCard),
            allTransactions = installments,
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )
        assertEquals(100_000L, available.creditCardPaymentDueInPeriod)
        assertEquals("El disponible baja como máximo una cuota ($100.000)", 2_900_000L, available.availableForPeriod)
    }

    @Test
    fun `arriendo pendiente del mes pasado sin pagar se resta este mes y aparece como Vencido e ingreso atrasado no suma`() {
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val lastMonthRentMillis = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val debitAccount = AccountEntity(
            id = 1L,
            name = "Nu Débito",
            type = AccountType.DEBITO,
            initialBalance = 2_500_000L,
            colorHex = 0xFF8B5CF6,
            iconName = "account_balance"
        )

        val overdueRentTx = TransactionEntity(
            id = 100L,
            type = TransactionType.PAGO_PROGRAMADO,
            amount = 1_150_000L,
            timestamp = lastMonthRentMillis,
            accountId = 1L,
            note = "Arriendo octubre",
            status = TransactionStatus.PENDIENTE
        )
        val overdueIncomeTx = TransactionEntity(
            id = 101L,
            type = TransactionType.INGRESO,
            amount = 500_000L,
            timestamp = lastMonthRentMillis,
            accountId = 1L,
            note = "Cliente atrasado",
            status = TransactionStatus.PENDIENTE
        )

        val window = AvailableToSpendCalculator.resolvePeriodWindow(PeriodMode.MENSUAL, nowMillis = nowMillis)
        val result = AvailableToSpendCalculator.calculateProjectedAvailable(
            accounts = listOf(debitAccount),
            allTransactions = listOf(overdueRentTx, overdueIncomeTx),
            periodWindow = window,
            deductCreditCardPayment = true,
            nowMillis = nowMillis
        )

        // El arriendo vencido del mes pasado SÍ se resta este mes
        assertEquals(1_150_000L, result.pendingPaymentsInPeriod)
        // El ingreso pendiente vencido NO se suma al balance proyectado
        assertEquals(0L, result.projectedIncomeInPeriod)
        assertEquals(2_500_000L, result.projectedBalance)
        assertEquals(1_350_000L, result.availableForPeriod)

        // Etiquetas para la lista "Próximo"
        assertEquals("Vencido", AvailableToSpendCalculator.pendingStatusLabel(overdueRentTx, nowMillis))
        assertEquals("Ingreso atrasado", AvailableToSpendCalculator.pendingStatusLabel(overdueIncomeTx, nowMillis))

        // Además verificar que la ventana "Próximos 30 días" abarca exactamente 30 días
        val window30 = AvailableToSpendCalculator.resolvePeriodWindow(PeriodMode.PROXIMOS_30_DIAS, nowMillis = nowMillis)
        val realDaysInRange = ((window30.endTimestamp - window30.startTimestamp) / 86_400_000L).toInt() + 1
        assertEquals(30, window30.totalDays)
        assertEquals(30, window30.remainingDays)
        assertEquals(30, realDaysInRange)
    }
}
