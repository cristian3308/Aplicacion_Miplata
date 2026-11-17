package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CopFormatter
import com.example.ui.HistoryTransactionItem
import com.example.ui.MiPlataUiState
import com.example.ui.components.CategoryOrAccountBadge
import com.example.ui.components.IllustratedEmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryDateRangePreset(val label: String, val days: Int?) {
    ULTIMOS_7("Últimos 7 días", 7),
    ULTIMOS_30("Últimos 30 días", 30),
    ULTIMOS_90("Últimos 90 días", 90),
    TODO("Todo el historial", null)
}

/**
 * PESTANYA 5: HISTORIAL
 * - Buscador arriba y botón de filtros (por tipo, cuenta, categoría y estado).
 * - Selector de rango de fechas (por defecto, últimos 30 días).
 * - Tarjeta de resumen del rango con tres líneas: Ingreso, Gastos y Pagos de tarjeta de crédito,
 *   más un contador de transacciones en un círculo.
 * - Lista de transacciones con fecha y hora, íconos de recurrente y pagado, nombre, categoría, cuenta,
 *   monto y debajo el saldo de esa cuenta después de la transacción.
 * - Deslizar para editar o eliminar con opción de deshacer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementsScreen(
    uiState: MiPlataUiState,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onOpenQuickRegister: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf<TransactionType?>(null) }
    var selectedAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedStatus by rememberSaveable { mutableStateOf<TransactionStatus?>(null) }
    var datePreset by rememberSaveable { mutableStateOf(HistoryDateRangePreset.ULTIMOS_30) }

    var showFiltersDialog by remember { mutableStateOf(false) }
    var receiptPreviewTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val hideBalances = uiState.prefs.hideBalances
    val nowMillis = System.currentTimeMillis()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy • h:mm a", Locale("es", "CO")) }
    val rangeTitleFormat = remember { SimpleDateFormat("dd MMM", Locale("es", "CO")) }

    val filteredHistoryItems = remember(
        uiState.historyItemsWithRunningBalance,
        searchQuery,
        selectedType,
        selectedAccountId,
        selectedCategoryId,
        selectedStatus,
        datePreset
    ) {
        uiState.historyItemsWithRunningBalance.filter { item ->
            val tx = item.transaction
            if (selectedType != null && tx.type != selectedType) return@filter false
            if (selectedAccountId != null && tx.accountId != selectedAccountId && tx.toAccountId != selectedAccountId) {
                return@filter false
            }
            if (selectedCategoryId != null && tx.categoryId != selectedCategoryId) return@filter false
            if (selectedStatus != null && tx.status != selectedStatus) return@filter false

            val days = datePreset.days
            if (days != null) {
                val minTime = nowMillis - days * 24L * 60 * 60 * 1000L
                if (tx.timestamp < minTime) return@filter false
            }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                val catName = item.category?.name?.lowercase() ?: ""
                val accName = item.accountName.lowercase()
                val matchesNote = tx.note.lowercase().contains(q)
                val matchesType = tx.type.label.lowercase().contains(q)
                val matchesAmount = tx.amount.toString().contains(q)
                if (!matchesNote && !catName.contains(q) && !accName.contains(q) && !matchesType && !matchesAmount) {
                    return@filter false
                }
            }
            true
        }
    }

    // Cálculo de las 3 líneas de la tarjeta de resumen del rango: Ingreso, Gastos, Pagos de tarjeta de crédito
    val rangeIncomeTotal = remember(filteredHistoryItems) {
        filteredHistoryItems
            .map { it.transaction }
            .filter { AvailableToSpendCalculator.isIncomeTransactionForTotals(it) && !it.isInitialBalance }
            .sumOf { it.amount }
    }
    val rangeExpenseTotal = remember(filteredHistoryItems) {
        filteredHistoryItems
            .map { it.transaction }
            .filter { AvailableToSpendCalculator.isExpenseTransactionForTotals(it) }
            .sumOf { AvailableToSpendCalculator.netCategoryExpenseAmount(it) }
            .coerceAtLeast(0L)
    }
    val rangeCardPaymentsTotal = remember(filteredHistoryItems) {
        filteredHistoryItems
            .map { it.transaction }
            .filter { it.type == TransactionType.PAGO_TARJETA }
            .sumOf { it.amount }
    }

    val activeFiltersCount = listOfNotNull(
        selectedType,
        selectedAccountId,
        selectedCategoryId,
        selectedStatus
    ).size

    val dateRangeSubtitle = remember(datePreset, nowMillis) {
        val days = datePreset.days
        if (days == null) {
            "Todo el historial"
        } else {
            val startMillis = nowMillis - days * 24L * 60 * 60 * 1000L
            "${rangeTitleFormat.format(Date(startMillis))} - ${rangeTitleFormat.format(Date(nowMillis))} (${datePreset.label})"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("movements_screen")
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // 1. Buscador arriba + Botón de filtros
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar por nota, cuenta, categoría...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Buscar")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("movements_search_input")
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (activeFiltersCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                modifier = Modifier
                    .clickable { showFiltersDialog = true }
                    .testTag("history_filters_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filtros",
                        tint = if (activeFiltersCount > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    if (activeFiltersCount > 0) {
                        Text(
                            text = activeFiltersCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Selector de rango de fechas (por defecto: Últimos 30 días)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            HistoryDateRangePreset.entries.forEach { preset ->
                FilterChip(
                    selected = datePreset == preset,
                    onClick = { datePreset = preset },
                    label = { Text(preset.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Tarjeta de resumen del rango con 3 líneas: Ingreso, Gastos y Pagos de tarjeta de crédito + Contador en círculo
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = dateRangeSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Línea 1: Ingreso
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Ingreso",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.format(rangeIncomeTotal, hideBalance = hideBalances),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }

                    // Línea 2: Gastos
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Gastos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.format(rangeExpenseTotal, hideBalance = hideBalances),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Línea 3: Pagos de tarjeta de crédito
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Pagos de tarjeta de crédito",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.format(rangeCardPaymentsTotal, hideBalance = hideBalances),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Contador de transacciones en un círculo
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = filteredHistoryItems.size.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "movs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredHistoryItems.isEmpty()) {
            IllustratedEmptyState(
                title = "Sin transacciones en este rango",
                subtitle = "Ajusta el filtro de fechas o toca el botón '+' para registrar un movimiento.",
                actionLabel = "Agregar transacción",
                onActionClick = onOpenQuickRegister
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredHistoryItems,
                    key = { it.transaction.id }
                ) { item ->
                    val tx = item.transaction
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    onEditTransaction(tx)
                                    false
                                }
                                SwipeToDismissBoxValue.EndToStart -> {
                                    onDeleteTransaction(tx)
                                    true
                                }
                                SwipeToDismissBoxValue.Settled -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val direction = dismissState.dismissDirection
                            val bgColor = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(bgColor)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                    Alignment.CenterStart
                                } else {
                                    Alignment.CenterEnd
                                }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                            Icons.Default.Edit
                                        } else {
                                            Icons.Default.Delete
                                        },
                                        contentDescription = null,
                                        tint = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (direction == SwipeToDismissBoxValue.StartToEnd) "Editar" else "Eliminar",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    ) {
                        HistoryTransactionCard(
                            item = item,
                            formattedDate = dateFormat.format(Date(tx.timestamp)),
                            hideBalances = hideBalances,
                            onClick = { onEditTransaction(tx) },
                            onViewReceipt = { receiptPreviewTx = tx }
                        )
                    }
                }
            }
        }
    }

    // Diálogo de filtros avanzados (por tipo, cuenta, categoría y estado)
    if (showFiltersDialog) {
        AlertDialog(
            onDismissRequest = { showFiltersDialog = false },
            title = { Text("Filtrar historial", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Estado:", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedStatus == null,
                            onClick = { selectedStatus = null },
                            label = { Text("Todos") }
                        )
                        TransactionStatus.entries.forEach { st ->
                            FilterChip(
                                selected = selectedStatus == st,
                                onClick = { selectedStatus = if (selectedStatus == st) null else st },
                                label = { Text(st.label) }
                            )
                        }
                    }

                    Text("Tipo de transacción:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == null,
                            onClick = { selectedType = null },
                            label = { Text("Todos") }
                        )
                        TransactionType.entries.forEach { tType ->
                            FilterChip(
                                selected = selectedType == tType,
                                onClick = { selectedType = if (selectedType == tType) null else tType },
                                label = { Text(tType.label) }
                            )
                        }
                    }

                    Text("Cuenta:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedAccountId == null,
                            onClick = { selectedAccountId = null },
                            label = { Text("Todas") }
                        )
                        uiState.accountsWithBalance.forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.account.id,
                                onClick = {
                                    selectedAccountId = if (selectedAccountId == acc.account.id) null else acc.account.id
                                },
                                label = { Text(acc.account.name) }
                            )
                        }
                    }

                    Text("Categoría:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null },
                            label = { Text("Todas") }
                        )
                        uiState.categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = {
                                    selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id
                                },
                                label = { Text(cat.name) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFiltersDialog = false }) {
                    Text("Aplicar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedType = null
                        selectedAccountId = null
                        selectedCategoryId = null
                        selectedStatus = null
                        showFiltersDialog = false
                    }
                ) {
                    Text("Limpiar todo")
                }
            }
        )
    }

    // Visor de foto del recibo adjunto
    receiptPreviewTx?.let { tx ->
        AlertDialog(
            onDismissRequest = { receiptPreviewTx = null },
            title = { Text("Recibo: ${tx.note.ifBlank { CopFormatter.format(tx.amount) }}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = Uri.parse(tx.receiptUri),
                        contentDescription = "Foto del recibo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monto: ${CopFormatter.format(tx.amount)} • ${CopFormatter.formatShortDate(tx.timestamp)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { receiptPreviewTx = null }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
private fun HistoryTransactionCard(
    item: HistoryTransactionItem,
    formattedDate: String,
    hideBalances: Boolean,
    onClick: () -> Unit,
    onViewReceipt: () -> Unit
) {
    val tx = item.transaction
    val isIncome = AvailableToSpendCalculator.isIncomeTransactionForTotals(tx)
    val isTransferOrCardPay = tx.type == TransactionType.TRANSFERENCIA || tx.type == TransactionType.PAGO_TARJETA
    val amountColor = when {
        isIncome -> Color(0xFF10B981)
        isTransferOrCardPay -> Color(0xFF38BDF8)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = when {
        isIncome -> "+"
        isTransferOrCardPay -> "⇄ "
        else -> "-"
    }

    val categoryTitle = item.category?.name ?: tx.type.label
    val displayTitle = tx.note.ifBlank { categoryTitle }
    val accountSubtitle = if (item.toAccountName != null) {
        "${item.accountName} → ${item.toAccountName}"
    } else {
        item.accountName
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Fila 1: Fecha y hora + íconos de recurrente y pagado/pendiente
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tx.isRecurring) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = "Recurrente",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (tx.status == TransactionStatus.PAGADO) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Schedule
                        },
                        contentDescription = tx.status.label,
                        tint = if (tx.status == TransactionStatus.PAGADO) {
                            Color(0xFF10B981)
                        } else {
                            Color(0xFFF59E0B)
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = tx.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tx.status == TransactionStatus.PAGADO) {
                            Color(0xFF10B981)
                        } else {
                            Color(0xFFF59E0B)
                        }
                    )
                }
            }

            // Fila 2: Badge + Nombre + Categoría + Cuenta a la izquierda | Monto y Saldo después a la derecha
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    CategoryOrAccountBadge(
                        iconName = item.category?.iconName ?: "payments",
                        colorHex = item.category?.colorHex ?: 0xFF38BDF8L,
                        size = 40.dp
                    )
                    Column {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$categoryTitle • $accountSubtitle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = prefix + CopFormatter.format(tx.amount, hideBalance = hideBalances),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = amountColor
                    )
                    Text(
                        text = "Saldo: ${CopFormatter.format(item.accountBalanceAfter, hideBalance = hideBalances)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!tx.receiptUri.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onViewReceipt)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = "Ver recibo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Ver foto del recibo adjunto",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
