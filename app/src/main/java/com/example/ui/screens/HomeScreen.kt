package com.example.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.CategoryEntity
import com.example.data.PeriodMode
import com.example.data.RecurrenceFrequency
import com.example.data.TransactionEntity
import com.example.data.TransactionType
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.AvailableToSpendResult
import com.example.domain.CopFormatter
import com.example.ui.MiPlataUiState
import com.example.ui.SmartAlertItem
import com.example.ui.ThisMonthCarouselSummary
import com.example.ui.UpcomingPendingItem
import com.example.ui.components.CategoryOrAccountBadge

/**
 * Pestaña 1 — INICIO:
 * a) Tarjeta "Disponible para gastar" con selector de período (Este mes, Esta quincena, Próximos 30 días, personalizado),
 *    filtro de cuentas de débito, anillo grande con el Restante (verde con check si es positivo, rojo con alerta si es negativo),
 *    Balance actual, Balance proyectado, Gasto proyectado y línea aparte "Pago de tarjeta" con interruptor (activado por defecto).
 * b) Carrusel horizontal "Este mes" con 3 tarjetas: Gasto general, Tarjetas de crédito y Flujo de efectivo.
 * c) Lista "Próximo": transacciones pendientes ordenadas por fecha con saldo proyectado debajo y acciones (Pagar, Editar, Omitir).
 */
@Composable
fun HomeScreen(
    uiState: MiPlataUiState,
    onChangePeriodMode: (PeriodMode) -> Unit,
    onToggleDeductCreditCard: (Boolean) -> Unit,
    onToggleAccountInAvailableFilter: (AccountEntity) -> Unit,
    onMarkPendingAsPaid: (TransactionEntity) -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onSkipPendingTransaction: (TransactionEntity) -> Unit,
    onLoadDemo: () -> Unit,
    onClearDemo: () -> Unit,
    onOpenQuickRegister: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hideBalances = uiState.prefs.hideBalances
    val available = uiState.availableToSpend
    val thinBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

    var showAccountsFilterDialog by remember { mutableStateOf(false) }
    var selectedPendingItemForAction by remember { mutableStateOf<UpcomingPendingItem?>(null) }

    val hasRealMovements = remember(uiState.allTransactions) {
        uiState.allTransactions.any { !it.isDemo }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.prefs.isDemoLoaded) {
            DemoActiveFixedBanner(
                onClearDemo = onClearDemo,
                border = thinBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 115.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1a. TARJETA "DISPONIBLE PARA GASTAR"
            item {
                AvailableToSpendHeroCard(
                    available = available,
                    currentPeriodMode = uiState.prefs.periodMode,
                    hideBalances = hideBalances,
                    border = thinBorder,
                    onSelectPeriodMode = onChangePeriodMode,
                    onOpenAccountFilter = { showAccountsFilterDialog = true },
                    onToggleDeductCard = onToggleDeductCreditCard
                )
            }

            // Alertas en Inicio (máximo 3, de más urgente a menos urgente)
            if (uiState.smartAlerts.isNotEmpty()) {
                item {
                    HomeSmartAlertsSection(
                        alerts = uiState.smartAlerts.take(3),
                        border = thinBorder
                    )
                }
            }

            // Banner "Ver demo" solo si no hay ningún movimiento real y la demo no está cargada
            if (!uiState.prefs.isDemoLoaded && !hasRealMovements) {
                item {
                    DemoBannerCompact(
                        isDemoLoaded = false,
                        onLoadDemo = onLoadDemo,
                        onClearDemo = onClearDemo,
                        border = thinBorder
                    )
                }
            }

        // 1b. CARRUSEL HORIZONTAL "ESTE MES" (Gasto general, Tarjetas de crédito, Flujo de efectivo)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Este mes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                ThisMonthThreeCardCarousel(
                    summary = uiState.thisMonthCarousel,
                    hideBalances = hideBalances,
                    border = thinBorder
                )
            }
        }

        // 1c. LISTA "PRÓXIMO" (Transacciones pendientes con saldo proyectado debajo)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Próximo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Transacciones pendientes y saldo proyectado resultante",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = thinBorder
                ) {
                    Text(
                        text = "${uiState.upcomingPendingItems.size} pendientes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (uiState.upcomingPendingItems.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = thinBorder,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No tienes pagos ni ingresos pendientes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Usa el botón '+' y elige 'Pago' o marca cualquier movimiento como recurrente para proyectar tus saldos futuros.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = onOpenQuickRegister) {
                            Text("Programar pago o ingreso")
                        }
                    }
                }
            }
        } else {
            items(
                items = uiState.upcomingPendingItems,
                key = { "pending_${it.transaction.id}" }
            ) { pendingItem ->
                UpcomingPendingRowCard(
                    item = pendingItem,
                    hideBalances = hideBalances,
                    border = thinBorder,
                    onClick = { selectedPendingItemForAction = pendingItem }
                )
            }
        }
        }
    }

    // Diálogo de filtro de cuentas para "Disponible para gastar"
    if (showAccountsFilterDialog) {
        val debitAccounts = uiState.accountsWithBalance.filter {
            it.account.type == AccountType.DEBITO
        }
        AlertDialog(
            onDismissRequest = { showAccountsFilterDialog = false },
            title = { Text("Filtro de cuentas para Disponible") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Selecciona las cuentas de Débito que componen tu Balance actual. Las cuentas de Ahorros nunca se incluyen por seguridad.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    debitAccounts.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAccountInAvailableFilter(item.account) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.account.includeInAvailableFilter,
                                    onCheckedChange = { onToggleAccountInAvailableFilter(item.account) }
                                )
                                Text(item.account.name, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(
                                text = CopFormatter.format(item.currentBalance, hideBalances),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAccountsFilterDialog = false }) {
                    Text("Listo")
                }
            }
        )
    }

    // Diálogo al tocar una transacción pendiente en "Próximo": Marcar como pagada, Editar u Omitir
    selectedPendingItemForAction?.let { item ->
        val tx = item.transaction
        AlertDialog(
            onDismissRequest = { selectedPendingItemForAction = null },
            title = { Text(tx.note.ifBlank { item.category?.name ?: tx.type.label }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${CopFormatter.format(tx.amount)} • ${item.accountName} • ${CopFormatter.formatDayHeader(tx.timestamp)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            onMarkPendingAsPaid(tx)
                            selectedPendingItemForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Marcar como pagada ahora")
                    }
                    FilledTonalButton(
                        onClick = {
                            val toEdit = tx
                            selectedPendingItemForAction = null
                            onEditTransaction(toEdit)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Editar transacción")
                    }
                    OutlinedButton(
                        onClick = {
                            onSkipPendingTransaction(tx)
                            selectedPendingItemForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Omitir esta ocurrencia")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedPendingItemForAction = null }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
private fun AvailableToSpendHeroCard(
    available: AvailableToSpendResult,
    currentPeriodMode: PeriodMode,
    hideBalances: Boolean,
    border: BorderStroke,
    onSelectPeriodMode: (PeriodMode) -> Unit,
    onOpenAccountFilter: () -> Unit,
    onToggleDeductCard: (Boolean) -> Unit
) {
    val isNegative = available.isOverBudget
    val ringColor = if (isNegative) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.secondary // Verde para positivo con check
    }
    var showFormulaBreakdownDialog by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = (1f - available.spentProgressFraction).coerceIn(0.08f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "available_ring"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_available_today_card"),
        shape = RoundedCornerShape(28.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Encabezado + Filtro de cuentas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Disponible para gastar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    onClick = onOpenAccountFilter,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = border,
                    modifier = Modifier.testTag("filter_debit_accounts_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filtrar cuentas",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cuentas Débito",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Selector de período: Este mes, Esta quincena, Próximos 30 días, Personalizado
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PeriodMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentPeriodMode == mode,
                        onClick = { onSelectPeriodMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            // Anillo grande central con el Restante (verde con check si es positivo, rojo con alerta si es negativo)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(188.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 14.dp.toPx()
                        drawArc(
                            color = ringColor.copy(alpha = 0.16f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = if (isNegative) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ringColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Restante",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.format(available.availableForPeriod, hideBalance = hideBalances),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = ringColor,
                            modifier = Modifier.testTag("available_per_day_amount")
                        )
                        Text(
                            text = "${CopFormatter.formatCompact(available.availablePerDay, hideBalances)} / día",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Desglose exacto: Balance actual, Balance proyectado, Gasto proyectado y línea aparte "Pago de tarjeta"
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = border,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BreakdownLineRow(
                        label = "Balance actual (Cuentas Débito)",
                        value = CopFormatter.format(available.currentDebitBalance, hideBalances),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    BreakdownLineRow(
                        label = "Balance proyectado (+ ingresos pend.)",
                        value = CopFormatter.format(available.projectedBalance, hideBalances),
                        valueColor = MaterialTheme.colorScheme.secondary
                    )
                    BreakdownLineRow(
                        label = "Gasto proyectado (pagos pendientes)",
                        value = CopFormatter.format(-available.projectedExpense, hideBalances),
                        valueColor = MaterialTheme.colorScheme.error
                    )
                    if (available.creditCardPaymentDueInPeriod > 0L || available.deductCreditCardPayment) {
                        BreakdownLineRow(
                            label = "  ↳ Pago de tarjeta en el período",
                            value = CopFormatter.format(-available.creditCardPaymentDueInPeriod, hideBalances),
                            valueColor = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Restar pago de tarjeta al vencimiento",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Las cuentas de Ahorros nunca cuentan como disponible",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = available.deductCreditCardPayment,
                            onCheckedChange = onToggleDeductCard
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showFormulaBreakdownDialog = true },
                            modifier = Modifier.testTag("how_it_is_calculated_button")
                        ) {
                            Text("¿Cómo se calcula?")
                        }
                    }
                }
            }
        }
    }

    if (showFormulaBreakdownDialog) {
        val cardDeduction = if (available.deductCreditCardPayment) available.creditCardPaymentDueInPeriod else 0L
        AlertDialog(
            onDismissRequest = { showFormulaBreakdownDialog = false },
            title = { Text("¿Cómo se calcula?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Saldo en cuentas + ingresos pendientes − pagos pendientes − pago de tarjeta = disponible, ÷ días restantes = por día.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    BreakdownLineRow(
                        label = "Saldo en cuentas (Débito)",
                        value = CopFormatter.format(available.currentDebitBalance, hideBalances),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    BreakdownLineRow(
                        label = "+ Ingresos pendientes",
                        value = CopFormatter.format(available.projectedIncomeInPeriod, hideBalances, showPlusSign = true),
                        valueColor = MaterialTheme.colorScheme.secondary
                    )
                    BreakdownLineRow(
                        label = "− Pagos pendientes",
                        value = CopFormatter.format(-available.pendingPaymentsInPeriod, hideBalances),
                        valueColor = MaterialTheme.colorScheme.error
                    )
                    BreakdownLineRow(
                        label = "− Pago de tarjeta",
                        value = CopFormatter.format(-cardDeduction, hideBalances),
                        valueColor = MaterialTheme.colorScheme.tertiary
                    )
                    HorizontalDivider()
                    BreakdownLineRow(
                        label = "= Disponible (${available.periodLabel})",
                        value = CopFormatter.format(available.availableForPeriod, hideBalances),
                        valueColor = ringColor
                    )
                    BreakdownLineRow(
                        label = "÷ ${available.remainingDaysInPeriod} días restantes = Por día",
                        value = CopFormatter.format(available.availablePerDay, hideBalances),
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFormulaBreakdownDialog = false }) {
                    Text("Entendido")
                }
            }
        )
    }
}

@Composable
private fun BreakdownLineRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun ThisMonthThreeCardCarousel(
    summary: ThisMonthCarouselSummary,
    hideBalances: Boolean,
    border: BorderStroke
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 8.dp)
    ) {
        // Tarjeta 1: Gasto general (total y las 2 categorías principales con su color)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                border = border,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(248.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Gasto general",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = CopFormatter.format(summary.generalExpenseTotal, hideBalances),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (summary.topTwoGeneralCategories.isEmpty()) {
                        Text(
                            text = "Sin gastos este mes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        summary.topTwoGeneralCategories.forEach { slice ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(slice.category.colorHex))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = slice.category.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = CopFormatter.formatCompact(slice.amount, hideBalances),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tarjeta 2: Tarjetas de crédito (gasto del mes con tarjeta y categorías principales)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                border = border,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(248.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CreditCard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tarjetas de crédito",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = CopFormatter.format(summary.creditCardExpenseTotal, hideBalances),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (summary.topTwoCardCategories.isEmpty()) {
                        Text(
                            text = "Sin compras con tarjeta este mes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        summary.topTwoCardCategories.forEach { slice ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(slice.category.colorHex))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = slice.category.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = CopFormatter.formatCompact(slice.amount, hideBalances),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tarjeta 3: Flujo de efectivo (neto, ingresos y egresos, con flecha arriba o abajo)
        item {
            val isPositive = summary.cashFlowNet >= 0L
            val netColor = if (isPositive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            Card(
                shape = RoundedCornerShape(24.dp),
                border = border,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(248.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Flujo de efectivo",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPositive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = netColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = CopFormatter.format(summary.cashFlowNet, hideBalances, showPlusSign = isPositive),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = netColor
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Ingresos: ${CopFormatter.formatCompact(summary.cashFlowIncomes, hideBalances)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Egresos: ${CopFormatter.formatCompact(summary.cashFlowOutflows, hideBalances)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingPendingRowCard(
    item: UpcomingPendingItem,
    hideBalances: Boolean,
    border: BorderStroke,
    onClick: () -> Unit
) {
    val tx = item.transaction
    val isIncome = AvailableToSpendCalculator.isIncomeTransactionForTotals(tx)
    val amountColor = when {
        item.isOverdue -> MaterialTheme.colorScheme.error
        isIncome -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val signedAmount = if (isIncome) tx.amount else -tx.amount

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        border = if (item.isOverdue) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CategoryOrAccountBadge(
                    iconName = item.category?.iconName ?: "payments",
                    colorHex = item.category?.colorHex ?: 0xFF38BDF8,
                    size = 42.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tx.note.ifBlank { item.category?.name ?: tx.type.label },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tx.isRecurring || tx.recurrence != RecurrenceFrequency.NINGUNA) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.EventRepeat,
                                contentDescription = "Recurrente",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        if (item.statusLabel != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = item.statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${CopFormatter.formatDayHeader(tx.timestamp)} • ${CopFormatter.formatTime(tx.timestamp)} • ${item.accountName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CopFormatter.format(signedAmount, hideBalances, showPlusSign = isIncome),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Text(
                    text = "Quedará: ${CopFormatter.format(item.projectedBalanceAfter, hideBalances)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.projectedBalanceAfter < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DemoActiveFixedBanner(
    onClearDemo: () -> Unit,
    border: BorderStroke,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.testTag("demo_active_fixed_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Estás viendo datos de ejemplo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onClearDemo,
                modifier = Modifier.testTag("clear_demo_button")
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Borrar demo")
            }
        }
    }
}

@Composable
private fun DemoBannerCompact(
    isDemoLoaded: Boolean,
    onLoadDemo: () -> Unit,
    onClearDemo: () -> Unit,
    border: BorderStroke
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isDemoLoaded) "Estás viendo datos de ejemplo" else "¿Quieres ver la app con datos completos?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isDemoLoaded) "Puedes borrarlos en cualquier momento." else "Carga cuentas, tarjetas, compras a cuotas y próximos pagos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isDemoLoaded) {
                OutlinedButton(
                    onClick = onClearDemo,
                    modifier = Modifier.testTag("clear_demo_button")
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Borrar demo")
                }
            } else {
                FilledTonalButton(
                    onClick = onLoadDemo,
                    modifier = Modifier.testTag("load_demo_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ver demo")
                }
            }
        }
    }
}

/**
 * Tarjeta reutilizable para filas de movimientos en modales o listas secundarias.
 */
@Composable
fun MovementRowCard(
    tx: TransactionEntity,
    category: CategoryEntity?,
    accountName: String,
    toAccountName: String?,
    hideBalances: Boolean,
    onClick: (() -> Unit)? = null
) {
    val isIncome = AvailableToSpendCalculator.isIncomeTransactionForTotals(tx)
    val isExpense = AvailableToSpendCalculator.isExpenseTransactionForTotals(tx)
    val amountColor = when {
        isIncome -> MaterialTheme.colorScheme.secondary
        isExpense -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val signedAmount = if (isExpense && tx.type != TransactionType.REEMBOLSO) -tx.amount else tx.amount

    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                CategoryOrAccountBadge(
                    iconName = category?.iconName ?: "swap_horiz",
                    colorHex = category?.colorHex ?: 0xFF38BDF8,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = tx.note.ifBlank { category?.name ?: tx.type.label },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(accountName)
                            if (toAccountName != null) append(" → $toAccountName")
                            append(" • ")
                            append(CopFormatter.formatTime(tx.timestamp))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (signedAmount < 0L) Icons.Default.TrendingDown else Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = amountColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = CopFormatter.format(signedAmount, hideBalances, showPlusSign = isIncome),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
            }
        }
    }
}

@Composable
private fun HomeSmartAlertsSection(
    alerts: List<SmartAlertItem>,
    border: BorderStroke
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_smart_alerts_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        alerts.forEachIndexed { idx, alert ->
            val accentColor = if (alert.isCriticalRed) {
                MaterialTheme.colorScheme.error
            } else {
                Color(0xFFF59E0B)
            }
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = accentColor.copy(alpha = 0.12f)
                ),
                border = border,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_smart_alert_$idx")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alert.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                        Text(
                            text = alert.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

