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
import com.example.domain.CopFormatter
import com.example.domain.CreditCardSummary
import com.example.ui.AccountWithBalance
import com.example.ui.MiPlataUiState
import com.example.ui.components.CategoryOrAccountBadge
import kotlin.math.max
import kotlin.math.roundToInt

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
    modifier: Modifier = Modifier
) {
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = AccountsFilterTab.entries[selectedFilterIndex]
    val hideBalances = uiState.prefs.hideBalances
    var accountForAbono by remember { mutableStateOf<AccountEntity?>(null) }

    val thinBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

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
                            it.account.type.normalized() == type
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
                                    border = thinBorder
                                )
                            }
                        }
                    }
                }

                AccountsFilterTab.DEBITO -> {
                    val debitList = uiState.accountsWithBalance.filter {
                        it.account.type.normalized() == AccountType.DEBITO
                    }
                    if (debitList.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.AccountBalanceWallet,
                                title = "Sin cuentas de débito",
                                explanation = "Agrega tu efectivo, cuenta bancaria o billetera digital (Nequi, Nu, Daviplata) para calcular tu disponible para gastar.",
                                buttonText = "Agregar cuenta Débito",
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
                                border = thinBorder
                            )
                        }
                    }
                }

                AccountsFilterTab.AHORROS -> {
                    val savingsList = uiState.accountsWithBalance.filter {
                        it.account.type.normalized() == AccountType.AHORROS
                    }
                    if (savingsList.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.Savings,
                                title = "Sin cuentas de ahorros",
                                explanation = "El dinero apartado queda fuera de lo que puedes gastar con seguridad.",
                                buttonText = "Crear cuenta de Ahorros",
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
                                border = thinBorder
                            )
                        }
                    }
                }

                AccountsFilterTab.TARJETAS -> {
                    if (uiState.creditCardSummaries.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.CreditCard,
                                title = "Sin tarjetas de crédito",
                                explanation = "Controla tu cupo disponible, fecha de corte, día de pago y compras a cuotas sin mezclarlas con tu efectivo.",
                                buttonText = "Agregar Tarjeta de Crédito",
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
                                            Text("Tarjeta")
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
                                border = thinBorder
                            )
                        }
                    }
                }

                AccountsFilterTab.PRESTAMOS -> {
                    val loanAccounts = uiState.accountsWithBalance.filter {
                        it.account.type.normalized() == AccountType.PRESTAMO
                    }
                    if (loanAccounts.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.RequestQuote,
                                title = "No tienes préstamos registrados",
                                explanation = "Registra créditos bancarios o préstamos que debas pagar para seguir el saldo pendiente, la cuota y tu porcentaje de avance.",
                                buttonText = "Agregar Préstamo",
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
                                onAbonoClick = { accountForAbono = item.account }
                            )
                        }
                    }
                }

                AccountsFilterTab.PRESTADO -> {
                    val lentAccounts = uiState.accountsWithBalance.filter {
                        it.account.type.normalized() == AccountType.PRESTADO
                    }
                    if (lentAccounts.isEmpty()) {
                        item {
                            AccountTypeEmptyState(
                                icon = Icons.Default.Handshake,
                                title = "Nadie te debe dinero por ahora",
                                explanation = "Lleva el control de lo que has prestado a familiares, amigos o clientes y registra cada abono que te hagan.",
                                buttonText = "Registrar dinero prestado",
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
                                onAbonoClick = { accountForAbono = item.account }
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo para registrar abono de Prestado (me pagan) o Préstamo (yo pago)
    accountForAbono?.let { targetAcc ->
        val isLent = targetAcc.type.normalized() == AccountType.PRESTADO
        val debitAccounts = uiState.accountsWithBalance.filter {
            it.account.type.normalized() == AccountType.DEBITO
        }
        var selectedDebitId by remember {
            mutableLongStateOf(debitAccounts.firstOrNull()?.account?.id ?: 1L)
        }
        var amountStr by remember {
            mutableStateOf(
                if (targetAcc.monthlyInstallment > 0L) targetAcc.monthlyInstallment.toString() else "100000"
            )
        }

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
                        onValueChange = { amountStr = it.filter { c -> c.isDigit() } },
                        label = { Text("Monto del abono en COP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                        if (amt > 0L) {
                            onRegisterLoanOrLentAbono(targetAcc, amt, selectedDebitId)
                            accountForAbono = null
                        }
                    }
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
                Text("Agregar")
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
    border: BorderStroke
) {
    val acc = item.account
    Card(
        shape = RoundedCornerShape(22.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
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
                        text = acc.type.label,
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
    border: BorderStroke
) {
    val card = summary.account
    val barColor = when {
        summary.usedPercentage >= 85 -> MaterialTheme.colorScheme.error
        summary.usedPercentage >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

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
    onAbonoClick: () -> Unit
) {
    val acc = item.account
    val original = max(acc.originalAmount, item.currentBalance + acc.receivedOrPaidAmount)
    val paidOrReceived = (original - item.currentBalance).coerceAtLeast(0L)
    val pct = if (original > 0L) {
        ((paidOrReceived.toDouble() / original.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    } else 0

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
                            "Cuota mensual: ${CopFormatter.format(acc.monthlyInstallment, hideBalances)} • Original: ${CopFormatter.format(original, hideBalances)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CopFormatter.format(if (isLentToOthers) item.currentBalance else -item.currentBalance, hideBalances),
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
