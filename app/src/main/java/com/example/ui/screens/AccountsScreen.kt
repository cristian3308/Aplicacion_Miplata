package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.MiPlataRepository
import com.example.domain.CopFormatter
import com.example.domain.CreditCardSummary
import com.example.ui.AccountWithBalance
import com.example.ui.MiPlataUiState
import com.example.ui.components.CategoryOrAccountBadge
import kotlin.math.max
import kotlin.math.roundToInt

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import com.example.domain.AvailableToSpendCalculator
import com.example.ui.components.CopThousandSeparatorTransformation
import com.example.ui.components.IconCatalog
import com.example.ui.components.sanitizeCopDigitsInput

/**
 * Filtros de la pestaña 4 — CUENTAS:
 * Todo, Débito, Ahorros, Tarjetas de crédito, Préstamos, Prestado.
 */
private enum class AccountsFilterTab(val label: String, val accountType: AccountType?) {
    TODO("Todo", null),
    DEBITO("Débito", AccountType.DEBITO),
    AHORROS("Ahorros", AccountType.AHORROS),
    TARJETAS("Tarjetas de crédito", AccountType.TARJETA_CREDITO),
    PRESTAMOS("Préstamos", AccountType.PRESTAMO),
    PRESTADO("Prestado", AccountType.PRESTADO)
}

@Composable
fun AccountsScreen(
    uiState: MiPlataUiState,
    onOpenAddAccountWithType: (AccountType) -> Unit,
    onRegisterLoanOrLentAbono: (AccountEntity, Long, Long) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit = {},
    onDeleteOrArchiveAccount: (AccountEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = AccountsFilterTab.entries[selectedFilterIndex]
    val hideBalances = uiState.prefs.hideBalances
    var accountForAbono by remember { mutableStateOf<AccountEntity?>(null) }
    var selectedAccountForDetail by remember { mutableStateOf<AccountEntity?>(null) }
    var accountBeingEdited by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingBalanceChangeConfirmation by remember {
        mutableStateOf<Triple<AccountEntity, Long, Long>?>(null)
    }

    val thinBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    val copVisualTransformation = remember { CopThousandSeparatorTransformation("$") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("accounts_screen")
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Chips de filtro: Todo, Débito, Ahorros, Tarjetas de crédito, Préstamos, Prestado
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AccountsFilterTab.entries.forEachIndexed { idx, tab ->
                FilterChip(
                    selected = selectedFilterIndex == idx,
                    onClick = { selectedFilterIndex = idx },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag("accounts_chip_${tab.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                AccountsFilterTab.TODO -> {
                    // 1. Flujo de efectivo del mes (neto, entradas y salidas)
                    item {
                        CashFlowMonthBannerCard(
                            netFlow = uiState.thisMonthCarousel.cashFlowNet,
                            inflows = uiState.thisMonthCarousel.cashFlowIncomes,
                            outflows = uiState.thisMonthCarousel.cashFlowOutflows,
                            hideBalances = hideBalances,
                            border = thinBorder,
                            onAddAccountClick = { onOpenAddAccountWithType(AccountType.DEBITO) }
                        )
                    }

                    if (uiState.accountsWithBalance.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.AccountBalanceWallet,
                                title = "Aún no tienes cuentas activas",
                                explanation = "Añade tu efectivo, cuenta bancaria o billetera digital para empezar a llevar tus cuentas claras.",
                                buttonText = "Añadir cuenta",
                                onAddClick = { onOpenAddAccountWithType(AccountType.DEBITO) }
                            )
                        }
                    } else {
                        // 2. Cuentas agrupadas por tipo con su subtotal (deudas en negativo, prestado en positivo)
                        val orderedTypes = listOf(
                            AccountType.DEBITO to "Cuentas Débito y Efectivo",
                            AccountType.AHORROS to "Ahorros apartados",
                            AccountType.TARJETA_CREDITO to "Tarjetas de crédito",
                            AccountType.PRESTAMO to "Préstamos (Lo que debo)",
                            AccountType.PRESTADO to "Prestado (Lo que me deben)"
                        )

                        orderedTypes.forEach { (type, groupTitle) ->
                            val groupAccounts = uiState.accountsWithBalance.filter {
                                it.account.type == type
                            }
                            if (groupAccounts.isNotEmpty()) {
                                val isDebtType = type == AccountType.TARJETA_CREDITO || type == AccountType.PRESTAMO
                                val rawSubtotal = if (type == AccountType.TARJETA_CREDITO) {
                                    uiState.creditCardSummaries.sumOf { it.currentDebt }
                                } else {
                                    groupAccounts.sumOf { it.currentBalance }
                                }
                                val signedSubtotal = if (isDebtType && rawSubtotal > 0L) -rawSubtotal else rawSubtotal

                                item(key = "group_header_${type.name}") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = groupTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = CopFormatter.format(
                                                signedSubtotal,
                                                hideBalance = hideBalances,
                                                showPlusSign = type == AccountType.PRESTADO && signedSubtotal > 0L
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                isDebtType && rawSubtotal > 0L -> MaterialTheme.colorScheme.tertiary
                                                type == AccountType.PRESTADO -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }

                                items(groupAccounts, key = { "all_${it.account.id}" }) { item ->
                                    val signedBalance = if (isDebtType && item.currentBalance > 0L) {
                                        -item.currentBalance
                                    } else {
                                        item.currentBalance
                                    }
                                    StandardAccountRowCard(
                                        item = item,
                                        displaySignedBalance = signedBalance,
                                        isDebt = isDebtType,
                                        hideBalances = hideBalances,
                                        border = thinBorder,
                                        onClick = { selectedAccountForDetail = item.account }
                                    )
                                }
                            }
                        }
                    }
                }

                AccountsFilterTab.DEBITO -> {
                    val debitList = uiState.accountsWithBalance.filter {
                        it.account.type == AccountType.DEBITO
                    }
                    if (debitList.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.AccountBalanceWallet,
                                title = "Aún no tienes cuentas de débito",
                                explanation = "Agrega tu efectivo, cuenta bancaria o billetera digital (Nequi, Nu, Daviplata) para calcular tu disponible para gastar.",
                                buttonText = "Añadir cuenta",
                                onAddClick = { onOpenAddAccountWithType(AccountType.DEBITO) }
                            )
                        }
                    } else {
                        item {
                            AccountCategoryTotalBanner(
                                label = "Total en cuentas Débito",
                                amount = uiState.totalDebitBalance,
                                subtitle = "Incluido en tu Disponible para gastar",
                                isDebt = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAddClick = { onOpenAddAccountWithType(AccountType.DEBITO) }
                            )
                        }
                        items(debitList, key = { it.account.id }) { item ->
                            StandardAccountRowCard(
                                item = item,
                                displaySignedBalance = item.currentBalance,
                                isDebt = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onClick = { selectedAccountForDetail = item.account }
                            )
                        }
                    }
                }

                AccountsFilterTab.AHORROS -> {
                    val savingsList = uiState.accountsWithBalance.filter {
                        it.account.type == AccountType.AHORROS
                    }
                    if (savingsList.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.Savings,
                                title = "Aún no tienes cuentas de ahorros",
                                explanation = "El dinero apartado queda fuera de lo que puedes gastar con seguridad.",
                                buttonText = "Añadir cuenta de ahorros",
                                onAddClick = { onOpenAddAccountWithType(AccountType.AHORROS) }
                            )
                        }
                    } else {
                        item {
                            AccountCategoryTotalBanner(
                                label = "Total apartado en Ahorros",
                                amount = uiState.totalSavingsBalance,
                                subtitle = "El dinero apartado queda fuera de lo que puedes gastar con seguridad",
                                isDebt = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAddClick = { onOpenAddAccountWithType(AccountType.AHORROS) }
                            )
                        }
                        items(savingsList, key = { it.account.id }) { item ->
                            StandardAccountRowCard(
                                item = item,
                                displaySignedBalance = item.currentBalance,
                                isDebt = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onClick = { selectedAccountForDetail = item.account }
                            )
                        }
                    }
                }

                AccountsFilterTab.TARJETAS -> {
                    if (uiState.creditCardSummaries.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.CreditCard,
                                title = "Aún no tienes tarjetas de crédito",
                                explanation = "Controla tu cupo disponible, fecha de corte, día de pago y compras a cuotas sin mezclarlas con tu efectivo.",
                                buttonText = "Añadir tarjeta",
                                onAddClick = { onOpenAddAccountWithType(AccountType.TARJETA_CREDITO) }
                            )
                        }
                    } else {
                        val nearestDue = uiState.creditCardSummaries.minByOrNull { it.daysUntilPaymentDue }
                        item {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                border = thinBorder,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Saldo total tarjetas de crédito",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = CopFormatter.format(-uiState.totalCreditCardDebt, hideBalances),
                                                style = MaterialTheme.typography.displaySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { onOpenAddAccountWithType(AccountType.TARJETA_CREDITO) }
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Añadir tarjeta")
                                        }
                                    }
                                    if (nearestDue != null) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Schedule,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Próximo vencimiento: ${nearestDue.account.name} en ${nearestDue.daysUntilPaymentDue} días (día ${nearestDue.account.paymentDueDay})",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        items(uiState.creditCardSummaries, key = { it.account.id }) { cardSummary ->
                            CreditCardDetailCard(
                                summary = cardSummary,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onClick = { selectedAccountForDetail = cardSummary.account }
                            )
                        }
                    }
                }

                AccountsFilterTab.PRESTAMOS -> {
                    val loanAccounts = uiState.accountsWithBalance.filter {
                        it.account.type == AccountType.PRESTAMO
                    }
                    if (loanAccounts.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.RequestQuote,
                                title = "Aún no tienes préstamos registrados",
                                explanation = "Registra créditos bancarios o préstamos que debas pagar para seguir el saldo pendiente, la cuota y tu porcentaje de avance.",
                                buttonText = "Añadir préstamo",
                                onAddClick = { onOpenAddAccountWithType(AccountType.PRESTAMO) }
                            )
                        }
                    } else {
                        item {
                            AccountCategoryTotalBanner(
                                label = "Total adeudado en Préstamos",
                                amount = -uiState.totalLoansDebt,
                                subtitle = "Registra tus abonos para reducir el saldo pendiente",
                                isDebt = true,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAddClick = { onOpenAddAccountWithType(AccountType.PRESTAMO) }
                            )
                        }
                        items(loanAccounts, key = { it.account.id }) { item ->
                            LoanOrLentProgressCard(
                                item = item,
                                isLentToOthers = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAbonoClick = { accountForAbono = item.account },
                                onCardClick = { selectedAccountForDetail = item.account }
                            )
                        }
                    }
                }

                AccountsFilterTab.PRESTADO -> {
                    val lentAccounts = uiState.accountsWithBalance.filter {
                        it.account.type == AccountType.PRESTADO
                    }
                    if (lentAccounts.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.Handshake,
                                title = "Nadie te debe plata por ahora",
                                explanation = "Lleva el control de lo que has prestado a familiares, amigos o clientes y registra cada abono que te hagan.",
                                buttonText = "Registrar préstamo",
                                onAddClick = { onOpenAddAccountWithType(AccountType.PRESTADO) }
                            )
                        }
                    } else {
                        item {
                            AccountCategoryTotalBanner(
                                label = "Te deben en total",
                                amount = uiState.totalLentReceivable,
                                subtitle = "Dinero pendiente por cobrar",
                                isDebt = false,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAddClick = { onOpenAddAccountWithType(AccountType.PRESTADO) }
                            )
                        }
                        items(lentAccounts, key = { it.account.id }) { item ->
                            LoanOrLentProgressCard(
                                item = item,
                                isLentToOthers = true,
                                hideBalances = hideBalances,
                                border = thinBorder,
                                onAbonoClick = { accountForAbono = item.account },
                                onCardClick = { selectedAccountForDetail = item.account }
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo de detalle al tocar una cuenta: "Editar" y "Eliminar" (o "Archivar" si tiene movimientos)
    selectedAccountForDetail?.let { detailAcc ->
        val freshAcc = uiState.accountsWithBalance.find { it.account.id == detailAcc.id }?.account ?: detailAcc
        val currentBal = uiState.accountsWithBalance.find { it.account.id == freshAcc.id }?.currentBalance
            ?: AvailableToSpendCalculator.computeAccountBalance(freshAcc, uiState.allTransactions, includePending = false)
        val hasMovements = freshAcc.id in uiState.accountIdsWithMovements

        AlertDialog(
            onDismissRequest = { selectedAccountForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryOrAccountBadge(
                        iconName = freshAcc.iconName,
                        colorHex = freshAcc.colorHex,
                        size = 40.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (freshAcc.lastFourDigits.isNotBlank()) {
                                "${freshAcc.name} •••• ${freshAcc.lastFourDigits}"
                            } else {
                                freshAcc.name
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = freshAcc.type.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when (freshAcc.type) {
                                    AccountType.TARJETA_CREDITO -> "Deuda actual"
                                    AccountType.PRESTAMO -> "Saldo pendiente por pagar"
                                    AccountType.PRESTADO -> "Saldo pendiente por cobrar"
                                    else -> "Saldo actual"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = CopFormatter.format(currentBal, hideBalances),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (freshAcc.type == AccountType.TARJETA_CREDITO) {
                                Text(
                                    text = "Cupo: ${CopFormatter.format(freshAcc.creditLimit, hideBalances)} • Corte día ${freshAcc.billingCutoffDay} • Pago día ${freshAcc.paymentDueDay}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (freshAcc.type == AccountType.PRESTAMO || freshAcc.type == AccountType.PRESTADO) {
                                Text(
                                    text = "Monto original: ${CopFormatter.format(freshAcc.originalAmount, hideBalances)} • Abono previo: ${CopFormatter.format(freshAcc.receivedOrPaidAmount, hideBalances)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Saldo inicial base: ${CopFormatter.format(freshAcc.initialBalance, hideBalances)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (hasMovements) {
                        Text(
                            text = "Esta cuenta tiene movimientos en el historial. Si ya no la usas, puedes archivarla para que no aparezca en selectores ni sume en los totales, conservando su historial. Podrás desarchivarla cuando quieras desde Ajustes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                accountBeingEdited = freshAcc
                                selectedAccountForDetail = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("account_detail_edit_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Editar")
                        }
                        FilledTonalButton(
                            onClick = {
                                onDeleteOrArchiveAccount(freshAcc)
                                selectedAccountForDetail = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(if (hasMovements) "account_detail_archive_button" else "account_detail_delete_button")
                        ) {
                            Icon(
                                imageVector = if (hasMovements) Icons.Default.Archive else Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hasMovements) "Archivar" else "Eliminar")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedAccountForDetail = null }) {
                    Text("Cerrar")
                }
            }
        )
    }

    // Diálogo para editar todos los campos de una cuenta
    accountBeingEdited?.let { editTarget ->
        val hasMovements = editTarget.id in uiState.accountIdsWithMovements
        var name by remember(editTarget.id) { mutableStateOf(editTarget.name) }
        var selectedIcon by remember(editTarget.id) { mutableStateOf(editTarget.iconName) }
        var selectedColor by remember(editTarget.id) { mutableLongStateOf(editTarget.colorHex) }
        var initialBalStr by remember(editTarget.id) { mutableStateOf(editTarget.initialBalance.toString()) }
        var creditLimitStr by remember(editTarget.id) { mutableStateOf(editTarget.creditLimit.toString()) }
        var cutoffDayStr by remember(editTarget.id) { mutableStateOf(editTarget.billingCutoffDay.toString()) }
        var dueDayStr by remember(editTarget.id) { mutableStateOf(editTarget.paymentDueDay.toString()) }
        var lastFourDigits by remember(editTarget.id) { mutableStateOf(editTarget.lastFourDigits) }
        var personName by remember(editTarget.id) { mutableStateOf(editTarget.personName) }
        var originalAmountStr by remember(editTarget.id) { mutableStateOf(editTarget.originalAmount.toString()) }
        var receivedOrPaidStr by remember(editTarget.id) { mutableStateOf(editTarget.receivedOrPaidAmount.toString()) }
        var monthlyInstallmentStr by remember(editTarget.id) { mutableStateOf(editTarget.monthlyInstallment.toString()) }

        AlertDialog(
            onDismissRequest = { accountBeingEdited = null },
            title = { Text("Editar ${editTarget.name}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre de la cuenta") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_account_name_input")
                    )

                    Text("Ícono:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconCatalog.availableIcons.take(8).forEach { (iconKey, iconLabel) ->
                            FilterChip(
                                selected = selectedIcon == iconKey,
                                onClick = { selectedIcon = iconKey },
                                label = { Text(iconLabel) }
                            )
                        }
                    }

                    Text("Color:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconCatalog.availableColors.forEach { hex ->
                            val isSelected = selectedColor == hex
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(hex))
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { selectedColor = hex }
                            )
                        }
                    }

                    when (editTarget.type) {
                        AccountType.DEBITO, AccountType.AHORROS -> {
                            OutlinedTextField(
                                value = initialBalStr,
                                onValueChange = { initialBalStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Saldo inicial en COP") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edit_account_initial_balance_input")
                            )
                        }

                        AccountType.TARJETA_CREDITO -> {
                            OutlinedTextField(
                                value = creditLimitStr,
                                onValueChange = { creditLimitStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Cupo total en COP") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = initialBalStr,
                                onValueChange = { initialBalStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Deuda inicial ya usada en COP") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edit_account_initial_balance_input")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = cutoffDayStr,
                                    onValueChange = { cutoffDayStr = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Día corte") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = dueDayStr,
                                    onValueChange = { dueDayStr = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Día pago") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = lastFourDigits,
                                onValueChange = { lastFourDigits = it.filter(Char::isDigit).take(4) },
                                label = { Text("Últimos 4 dígitos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        AccountType.PRESTAMO, AccountType.PRESTADO -> {
                            OutlinedTextField(
                                value = personName,
                                onValueChange = { personName = it },
                                label = {
                                    Text(if (editTarget.type == AccountType.PRESTADO) "Persona que te debe" else "Entidad o acreedor")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = originalAmountStr,
                                onValueChange = { originalAmountStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Monto original en COP") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = receivedOrPaidStr,
                                onValueChange = { receivedOrPaidStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Abonado antes de usar la app (COP)") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (editTarget.type == AccountType.PRESTAMO) {
                                OutlinedTextField(
                                    value = monthlyInstallmentStr,
                                    onValueChange = { monthlyInstallmentStr = sanitizeCopDigitsInput(it) },
                                    label = { Text("Cuota mensual en COP") },
                                    visualTransformation = copVisualTransformation,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        val newInit = initialBalStr.toLongOrNull() ?: 0L
                        val newOrig = originalAmountStr.toLongOrNull() ?: 0L
                        val newPaidPrior = receivedOrPaidStr.toLongOrNull() ?: 0L
                        val effectiveInit = if (editTarget.type == AccountType.PRESTAMO || editTarget.type == AccountType.PRESTADO) {
                            (newOrig - newPaidPrior).coerceAtLeast(0L)
                        } else {
                            newInit
                        }
                        val updatedAcc = editTarget.copy(
                            name = name.trim(),
                            iconName = selectedIcon,
                            colorHex = selectedColor,
                            initialBalance = effectiveInit,
                            creditLimit = creditLimitStr.toLongOrNull() ?: 0L,
                            billingCutoffDay = (cutoffDayStr.toIntOrNull() ?: 15).coerceIn(1, 28),
                            paymentDueDay = (dueDayStr.toIntOrNull() ?: 5).coerceIn(1, 28),
                            lastFourDigits = lastFourDigits.take(4),
                            personName = personName.trim(),
                            originalAmount = newOrig,
                            receivedOrPaidAmount = newPaidPrior,
                            monthlyInstallment = monthlyInstallmentStr.toLongOrNull() ?: 0L
                        )

                        val initialChanged = when (editTarget.type) {
                            AccountType.PRESTAMO, AccountType.PRESTADO ->
                                editTarget.originalAmount != updatedAcc.originalAmount ||
                                    editTarget.receivedOrPaidAmount != updatedAcc.receivedOrPaidAmount
                            else -> editTarget.initialBalance != updatedAcc.initialBalance
                        }

                        if (hasMovements && initialChanged) {
                            val beforeBal = AvailableToSpendCalculator.computeAccountBalance(
                                account = editTarget,
                                allTransactions = uiState.allTransactions,
                                includePending = false
                            )
                            val afterBal = AvailableToSpendCalculator.computeAccountBalance(
                                account = updatedAcc,
                                allTransactions = uiState.allTransactions,
                                includePending = false
                            )
                            pendingBalanceChangeConfirmation = Triple(updatedAcc, beforeBal, afterBal)
                            accountBeingEdited = null
                        } else {
                            onUpdateAccount(updatedAcc)
                            accountBeingEdited = null
                        }
                    },
                    modifier = Modifier.testTag("save_edited_account_button")
                ) {
                    Text("Guardar cambios")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountBeingEdited = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Confirmación al cambiar el saldo inicial de una cuenta con movimientos (mostrando saldo antes y después)
    pendingBalanceChangeConfirmation?.let { (updatedAcc, beforeBal, afterBal) ->
        AlertDialog(
            onDismissRequest = { pendingBalanceChangeConfirmation = null },
            title = { Text("Confirmar cambio de saldo inicial") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "La cuenta \"${updatedAcc.name}\" ya tiene movimientos registrados. Cambiar su saldo inicial modificará el saldo actual calculado:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Saldo actual antes: ${CopFormatter.format(beforeBal)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Saldo actual después: ${CopFormatter.format(afterBal)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateAccount(updatedAcc)
                        pendingBalanceChangeConfirmation = null
                    },
                    modifier = Modifier.testTag("confirm_initial_balance_change_button")
                ) {
                    Text("Confirmar cambio")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBalanceChangeConfirmation = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para registrar abono de Prestado (me pagan) o Préstamo (yo pago)
    accountForAbono?.let { targetAcc ->
        val isLent = targetAcc.type == AccountType.PRESTADO
        val targetItem = uiState.accountsWithBalance.find { it.account.id == targetAcc.id }
        val pendingBalance = (targetItem?.currentBalance
            ?: (targetAcc.originalAmount - targetAcc.receivedOrPaidAmount)).coerceAtLeast(0L)
        val debitAccounts = uiState.accountsWithBalance.filter {
            it.account.type == AccountType.DEBITO
        }
        val initialDebitId = MiPlataRepository.resolveValidAccountId(
            accounts = uiState.accountsWithBalance.map { it.account },
            preferredAccountId = uiState.prefs.lastUsedAccountId
        )
        var selectedDebitId by remember(targetAcc.id) {
            mutableLongStateOf(debitAccounts.firstOrNull { it.account.id == initialDebitId }?.account?.id ?: initialDebitId)
        }
        var amountStr by remember(targetAcc.id) {
            val suggested = if (targetAcc.monthlyInstallment > 0L) {
                targetAcc.monthlyInstallment.coerceAtMost(pendingBalance)
            } else {
                100_000L.coerceAtMost(pendingBalance)
            }
            mutableStateOf(if (suggested > 0L) suggested.toString() else "0")
        }
        val typedAmount = amountStr.toLongOrNull() ?: 0L
        val exceedsPending = typedAmount > pendingBalance
        val errorMessage = if (exceedsPending) {
            "El abono supera lo pendiente (${CopFormatter.format(pendingBalance)})"
        } else null

        AlertDialog(
            onDismissRequest = { accountForAbono = null },
            title = {
                Text(
                    if (isLent) "Registrar abono recibido de ${targetAcc.personName.ifBlank { targetAcc.name }}"
                    else "Registrar pago a ${targetAcc.name}"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = sanitizeCopDigitsInput(it) },
                        label = { Text("Monto del abono en COP") },
                        visualTransformation = copVisualTransformation,
                        isError = exceedsPending,
                        supportingText = if (errorMessage != null) {
                            {
                                Text(
                                    text = errorMessage,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("abono_exceeds_pending_error")
                                )
                            }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("abono_amount_input")
                    )
                    Text(
                        text = if (isLent) "Cuenta donde recibes el dinero:" else "Cuenta desde donde pagas:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        debitAccounts.forEach { dAcc ->
                            FilterChip(
                                selected = selectedDebitId == dAcc.account.id,
                                onClick = { selectedDebitId = dAcc.account.id },
                                label = { Text(dAcc.account.name) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountStr.toLongOrNull() ?: 0L
                        if (amt > 0L && amt <= pendingBalance) {
                            onRegisterLoanOrLentAbono(targetAcc, amt, selectedDebitId)
                            accountForAbono = null
                        }
                    },
                    modifier = Modifier.testTag("save_abono_button")
                ) {
                    Text("Guardar abono")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountForAbono = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CashFlowMonthBannerCard(
    netFlow: Long,
    inflows: Long,
    outflows: Long,
    hideBalances: Boolean,
    border: BorderStroke,
    onAddAccountClick: () -> Unit
) {
    val isPositive = netFlow >= 0L
    Card(
        shape = RoundedCornerShape(24.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Flujo de efectivo del mes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPositive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = if (isPositive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = CopFormatter.format(netFlow, hideBalances, showPlusSign = isPositive),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPositive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onAddAccountClick,
                    modifier = Modifier.testTag("accounts_add_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nueva")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Entradas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = CopFormatter.format(inflows, hideBalances, showPlusSign = true),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Salidas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = CopFormatter.format(-outflows, hideBalances),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCategoryTotalBanner(
    label: String,
    amount: Long,
    subtitle: String,
    isDebt: Boolean,
    hideBalances: Boolean,
    border: BorderStroke,
    onAddClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = CopFormatter.format(amount, hideBalances),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isDebt) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Añadir")
            }
        }
    }
}

@Composable
private fun StandardAccountRowCard(
    item: AccountWithBalance,
    displaySignedBalance: Long,
    isDebt: Boolean,
    hideBalances: Boolean,
    border: BorderStroke,
    onClick: () -> Unit = {}
) {
    val acc = item.account
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("account_row_${acc.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                CategoryOrAccountBadge(
                    iconName = acc.iconName,
                    colorHex = acc.colorHex,
                    size = 42.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (acc.lastFourDigits.isNotBlank()) "${acc.name} •••• ${acc.lastFourDigits}" else acc.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${acc.type.label} • Toca para editar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = CopFormatter.format(displaySignedBalance, hideBalances),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    isDebt && displaySignedBalance < 0L -> MaterialTheme.colorScheme.tertiary
                    displaySignedBalance < 0L -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun CreditCardDetailCard(
    summary: CreditCardSummary,
    hideBalances: Boolean,
    border: BorderStroke,
    onClick: () -> Unit = {}
) {
    val card = summary.account
    val barColor = when {
        summary.usedPercentage >= 85 -> MaterialTheme.colorScheme.error
        summary.usedPercentage >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("credit_card_row_${card.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryOrAccountBadge(
                        iconName = card.iconName,
                        colorHex = card.colorHex,
                        size = 42.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${card.name} ${if (card.lastFourDigits.isNotBlank()) "•••• ${card.lastFourDigits}" else ""}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Corte día ${card.billingCutoffDay} • Pago día ${card.paymentDueDay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CopFormatter.format(-summary.currentDebt, hideBalances),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.currentDebt > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "${summary.usedPercentage}% usado",
                        style = MaterialTheme.typography.labelSmall,
                        color = barColor
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (summary.usedPercentage / 100f).coerceIn(0f, 1f) },
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(6.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Disponible sobre cupo: ${CopFormatter.format(summary.availableCredit, hideBalances)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Cupo: ${CopFormatter.format(card.creditLimit, hideBalances)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Advertencia "Tienes $X por pagar en N días"
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (summary.currentDebt > 0L) {
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = if (summary.currentDebt > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hideBalances && summary.currentDebt > 0L) {
                            "Tienes $ •••••• por pagar en ${summary.daysUntilPaymentDue} días"
                        } else {
                            summary.dueWarningText
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LoanOrLentProgressCard(
    item: AccountWithBalance,
    isLentToOthers: Boolean,
    hideBalances: Boolean,
    border: BorderStroke,
    onAbonoClick: () -> Unit,
    onCardClick: () -> Unit = {}
) {
    val acc = item.account
    val pending = item.currentBalance.coerceAtLeast(0L)
    val original = if (acc.originalAmount > 0L) {
        acc.originalAmount
    } else {
        max(acc.initialBalance, pending)
    }
    val paidOrReceived = (original - pending).coerceAtLeast(0L)
    val pct = if (original > 0L) {
        ((paidOrReceived.toDouble() / original.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    } else 0

    Card(
        onClick = onCardClick,
        shape = RoundedCornerShape(24.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("loan_row_${acc.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = acc.personName.ifBlank { acc.name },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLentToOthers) {
                            "Recibido: ${CopFormatter.format(paidOrReceived, hideBalances)} de ${CopFormatter.format(original, hideBalances)}"
                        } else {
                            buildString {
                                append("Pagado: ${CopFormatter.format(paidOrReceived, hideBalances)} de ${CopFormatter.format(original, hideBalances)}")
                                if (acc.monthlyInstallment > 0L) {
                                    append(" • Cuota: ${CopFormatter.format(acc.monthlyInstallment, hideBalances)}")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CopFormatter.format(if (isLentToOthers) pending else -pending, hideBalances),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isLentToOthers) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "Pendiente ($pct% avance)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                color = if (isLentToOthers) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onAbonoClick,
                    enabled = item.currentBalance > 0L
                ) {
                    Text(if (isLentToOthers) "Registrar abono recibido" else "Registrar pago de cuota")
                }
            }
        }
    }
}

@Composable
private fun AccountTypeEmptyState(
    icon: ImageVector,
    title: String,
    explanation: String,
    buttonText: String,
    onAddClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(buttonText)
            }
        }
    }
}
