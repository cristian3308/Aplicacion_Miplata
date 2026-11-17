package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CalendarDayProjection
import com.example.domain.CopFormatter
import com.example.ui.MiPlataUiState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PESTANYA 2: CALENDARIO
 * - Vista mensual (semana de lunes a domingo) con flechas de mes.
 * - Debajo de cada día, el saldo proyectado al cierre de ese día (o flujo neto).
 * - Días con transacciones resaltados; el día de hoy lleva un círculo.
 * - Controles: interruptor Balance / Flujo, interruptor Cuentas / Tarjetas de crédito y filtro de cuentas.
 * - Debajo del calendario: tarjeta "Balance al <último día del mes>" con total en Cuentas y Tarjetas de crédito,
 *   y dos listas desplegables ("Pendiente · N" y "Pagado · N").
 * - Tocar un día muestra las transacciones de ese día.
 */
@Composable
fun CalendarScreen(
    uiState: MiPlataUiState,
    onChangeMonth: (Int) -> Unit,
    onToggleFlowMode: (Boolean) -> Unit,
    onToggleCreditCardsMode: (Boolean) -> Unit,
    onToggleAccountFilter: (AccountEntity) -> Unit,
    onMarkTransactionPaid: (TransactionEntity) -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val hide = uiState.prefs.hideBalances
    var selectedDayForDetail by remember { mutableStateOf<CalendarDayProjection?>(null) }
    var showAccountFilterModal by remember { mutableStateOf(false) }
    var expandPendingList by rememberSaveable { mutableStateOf(true) }
    var expandPaidList by rememberSaveable { mutableStateOf(false) }

    val accountsById = remember(uiState.accountsWithBalance) {
        uiState.accountsWithBalance.associate { it.account.id to it.account.name }
    }
    val categoriesById = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }

    // Calcular desplazamiento del día 1 del mes en semana Lunes (0) a Domingo (6)
    val firstDayOffsetMondayZero = remember(uiState.calendarYear, uiState.calendarMonthZeroBased) {
        val cal = Calendar.getInstance().apply {
            set(uiState.calendarYear, uiState.calendarMonthZeroBased, 1)
        }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // Sunday=1, Monday=2... Saturday=7
        if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
    }

    val totalCells = firstDayOffsetMondayZero + uiState.calendarDays.size
    val rowsCount = (totalCells + 6) / 7

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("calendar_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Selector de mes con flechas + botón de filtro de cuentas
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onChangeMonth(-1) },
                        modifier = Modifier.testTag("calendar_prev_month_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Mes anterior"
                        )
                    }
                    Text(
                        text = uiState.calendarMonthTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    IconButton(
                        onClick = { onChangeMonth(1) },
                        modifier = Modifier.testTag("calendar_next_month_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Mes siguiente"
                        )
                    }
                }

                FilterChip(
                    selected = false,
                    onClick = { showAccountFilterModal = true },
                    label = { Text("Cuentas") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        // 2. Controles: Interruptor Balance / Flujo e Interruptor Cuentas / Tarjetas de crédito
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Segmento Balance / Flujo
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        CalendarTogglePill(
                            text = "Balance",
                            selected = !uiState.calendarShowFlowInsteadOfBalance,
                            onClick = { onToggleFlowMode(false) }
                        )
                        CalendarTogglePill(
                            text = "Flujo",
                            selected = uiState.calendarShowFlowInsteadOfBalance,
                            onClick = { onToggleFlowMode(true) }
                        )
                    }
                }

                // Segmento Cuentas / Tarjetas de crédito
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        CalendarTogglePill(
                            text = "Cuentas",
                            selected = !uiState.calendarShowCreditCards,
                            onClick = { onToggleCreditCardsMode(false) }
                        )
                        CalendarTogglePill(
                            text = "Tarjetas",
                            selected = uiState.calendarShowCreditCards,
                            onClick = { onToggleCreditCardsMode(true) }
                        )
                    }
                }
            }
        }

        // 3. Cuadrícula del calendario mensual (Lunes a Domingo)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Encabezado Lunes a Domingo
                    val weekHeaders = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        weekHeaders.forEach { dayName ->
                            Text(
                                text = dayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Filas de días
                    for (row in 0 until rowsCount) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (col in 0 until 7) {
                                val cellIndex = row * 7 + col
                                val dayIndex = cellIndex - firstDayOffsetMondayZero
                                val dayProj = uiState.calendarDays.getOrNull(dayIndex)

                                if (dayProj == null) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                    )
                                } else {
                                    CalendarDayCell(
                                        dayProj = dayProj,
                                        showFlow = uiState.calendarShowFlowInsteadOfBalance,
                                        showCreditCards = uiState.calendarShowCreditCards,
                                        hideBalances = hide,
                                        onClick = { selectedDayForDetail = dayProj },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Tarjeta "Balance al <último día del mes>" con Cuentas y Tarjetas de crédito
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Balance al ${uiState.calendarLastDayLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Columna Cuentas
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Cuentas",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = CopFormatter.format(uiState.calendarEndOfMonthAccountsBalance, hideBalance = hide),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (uiState.calendarEndOfMonthAccountsBalance >= 0L) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                        }

                        // Columna Tarjetas de crédito
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreditCard,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Tarjetas de crédito",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = CopFormatter.format(uiState.calendarEndOfMonthCardsBalance, hideBalance = hide),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (uiState.calendarEndOfMonthCardsBalance > 0L) {
                                            Color(0xFFF59E0B)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Lista desplegable: "Pendiente · N"
        item {
            ExpandableMonthTransactionsCard(
                title = "Pendiente · ${uiState.calendarMonthPendingTxs.size}",
                isPendingSection = true,
                expanded = expandPendingList,
                onToggleExpand = { expandPendingList = !expandPendingList },
                transactions = uiState.calendarMonthPendingTxs,
                accountsById = accountsById,
                categoriesById = categoriesById,
                hideBalances = hide,
                onMarkPaid = onMarkTransactionPaid,
                onEdit = onEditTransaction
            )
        }

        // 6. Lista desplegable: "Pagado · N"
        item {
            ExpandableMonthTransactionsCard(
                title = "Pagado · ${uiState.calendarMonthPaidTxs.size}",
                isPendingSection = false,
                expanded = expandPaidList,
                onToggleExpand = { expandPaidList = !expandPaidList },
                transactions = uiState.calendarMonthPaidTxs,
                accountsById = accountsById,
                categoriesById = categoriesById,
                hideBalances = hide,
                onMarkPaid = onMarkTransactionPaid,
                onEdit = onEditTransaction
            )
        }
    }

    // Modal al tocar un día del calendario para ver sus transacciones
    selectedDayForDetail?.let { dayProj ->
        val dayEndMillis = dayProj.timestampStartOfDay + 24L * 60 * 60 * 1000L - 1L
        val txsOfDay = remember(dayProj, uiState.allTransactions) {
            uiState.allTransactions.filter { it.timestamp in dayProj.timestampStartOfDay..dayEndMillis }
        }

        AlertDialog(
            onDismissRequest = { selectedDayForDetail = null },
            title = {
                Text(
                    text = "Día ${dayProj.dayOfMonth} • ${uiState.calendarMonthTitle}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Saldo proyectado al cierre",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = CopFormatter.format(dayProj.projectedClosingBalance, hideBalance = hide),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Flujo del día",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = CopFormatter.format(dayProj.netDailyFlow, hideBalance = hide, showPlusSign = true),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (dayProj.netDailyFlow >= 0L) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (txsOfDay.isEmpty()) {
                        Text(
                            text = "No hay transacciones registradas ni programadas para este día.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        txsOfDay.forEach { tx ->
                            val isIncome = AvailableToSpendCalculator.isIncomeTransactionForTotals(tx)
                            val catName = tx.categoryId?.let { categoriesById[it]?.name } ?: tx.type.label
                            val accName = accountsById[tx.accountId] ?: "Cuenta"
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDayForDetail = null
                                        onEditTransaction(tx)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tx.note.ifBlank { catName },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$accName • ${tx.status.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = (if (isIncome) "+" else "-") + CopFormatter.format(tx.amount, hideBalance = hide),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isIncome) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (tx.status == TransactionStatus.PENDIENTE) {
                                            IconButton(
                                                onClick = {
                                                    onMarkTransactionPaid(tx)
                                                    selectedDayForDetail = null
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Marcar pagada",
                                                    tint = Color(0xFF10B981),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDayForDetail = null }) {
                    Text("Cerrar")
                }
            }
        )
    }

    // Modal para filtrar qué cuentas de débito entran en el cálculo proyectado
    if (showAccountFilterModal) {
        val debitAccounts = uiState.accountsWithBalance
            .map { it.account }
            .filter { it.type.normalized() == AccountType.DEBITO }
        AlertDialog(
            onDismissRequest = { showAccountFilterModal = false },
            title = { Text("Filtrar cuentas de débito") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Elige las cuentas incluidas en el saldo proyectado del calendario:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    debitAccounts.forEach { acc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAccountFilter(acc) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = acc.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Checkbox(
                                checked = acc.includeInAvailableFilter,
                                onCheckedChange = { onToggleAccountFilter(acc) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAccountFilterModal = false }) {
                    Text("Listo")
                }
            }
        )
    }
}

@Composable
private fun CalendarTogglePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CalendarDayCell(
    dayProj: CalendarDayProjection,
    showFlow: Boolean,
    showCreditCards: Boolean,
    hideBalances: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTransactions = dayProj.hasTransactions
    val valueToDisplay = if (showFlow) dayProj.netDailyFlow else dayProj.projectedClosingBalance

    val bgColor = when {
        dayProj.isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        hasTransactions -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        else -> Color.Transparent
    }

    val borderStroke = when {
        dayProj.isToday -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        hasTransactions -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        else -> null
    }

    val numberColor = when {
        showFlow && valueToDisplay > 0L -> Color(0xFF10B981)
        showFlow && valueToDisplay < 0L -> MaterialTheme.colorScheme.error
        !showCreditCards && valueToDisplay < 0L -> MaterialTheme.colorScheme.error
        showCreditCards && valueToDisplay > 0L -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = borderStroke,
        modifier = modifier
            .height(58.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Número del día (con círculo si es hoy)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (dayProj.isToday) MaterialTheme.colorScheme.primary else Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayProj.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (dayProj.isToday || hasTransactions) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (dayProj.isToday) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Saldo proyectado o flujo compacto debajo de cada día
            val compactText = if (hideBalances) {
                "•••"
            } else if (showFlow && valueToDisplay == 0L) {
                "—"
            } else {
                formatCalendarCompactCop(valueToDisplay)
            }

            Text(
                text = compactText,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = if (hasTransactions) FontWeight.Bold else FontWeight.Normal,
                color = numberColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatCalendarCompactCop(amount: Long): String {
    val sign = if (amount < 0L) "-" else ""
    val absVal = kotlin.math.abs(amount)
    return when {
        absVal >= 1_000_000L -> {
            val m = absVal / 1_000_000.0
            String.format(Locale("es", "CO"), "%s$%.1fM", sign, m).replace(",0M", "M")
        }
        absVal >= 1_000L -> {
            val k = absVal / 1_000L
            "$sign$${k}k"
        }
        else -> "$sign$$absVal"
    }
}

@Composable
private fun ExpandableMonthTransactionsCard(
    title: String,
    isPendingSection: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    transactions: List<TransactionEntity>,
    accountsById: Map<Long, String>,
    categoriesById: Map<Long, com.example.data.CategoryEntity>,
    hideBalances: Boolean,
    onMarkPaid: (TransactionEntity) -> Unit,
    onEdit: (TransactionEntity) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM • h:mm a", Locale("es", "CO")) }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isPendingSection) Icons.Default.Schedule else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isPendingSection) Color(0xFFF59E0B) else Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Contraer" else "Expandir"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (transactions.isEmpty()) {
                        Text(
                            text = "Sin transacciones en esta sección para este mes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        transactions.forEach { tx ->
                            val isIncome = AvailableToSpendCalculator.isIncomeTransactionForTotals(tx)
                            val catName = tx.categoryId?.let { categoriesById[it]?.name } ?: tx.type.label
                            val accName = accountsById[tx.accountId] ?: "Cuenta"

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEdit(tx) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (tx.isRecurring) {
                                                Icon(
                                                    imageVector = Icons.Default.Repeat,
                                                    contentDescription = "Recurrente",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Text(
                                                text = tx.note.ifBlank { catName },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${dateFormat.format(Date(tx.timestamp))} • $accName",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = (if (isIncome) "+" else "-") + CopFormatter.format(tx.amount, hideBalance = hideBalances),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isIncome) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isPendingSection) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { onMarkPaid(tx) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Marcar pagada",
                                                    tint = Color(0xFF10B981),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Editar",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
