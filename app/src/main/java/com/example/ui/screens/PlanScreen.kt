package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.CategoryEntity
import com.example.data.SavingGoalEntity
import com.example.data.TransactionEntity
import com.example.domain.AvailableToSpendCalculator
import com.example.domain.CopFormatter
import com.example.ui.CategoryBudgetSummaryItem
import com.example.ui.MiPlataUiState
import com.example.ui.components.CategoryOrAccountBadge
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class BudgetSortMode(val label: String) {
    MAYOR_GASTO("Mayor gasto"),
    PORCENTAJE_LIMITE("Uso de límite"),
    ALFABETICO("A - Z")
}

/**
 * PESTANYA 3: PRESUPUESTO
 * - Selector de mes, botón de ordenar, botón de agrupar/desagrupar y botón de ajustes.
 * - Categorías agrupadas por grupos (Estilo de vida, Hogar y servicios, Transporte y movilidad,
 *   Finanzas y negocio, Sin grupo) con el total del grupo.
 * - Cada categoría muestra: ícono, nombre, total gastado a la derecha, barra de progreso debajo,
 *   número de transacciones ("N transacciones") y su límite mensual o "Sin límite".
 * - La barra cambia a amarillo al 80% y a rojo al 100%.
 * - Tocar una categoría abre sus transacciones y permite fijar o editar su límite mensual.
 */
@Composable
fun BudgetScreen(
    uiState: MiPlataUiState,
    onChangeMonth: (Int) -> Unit,
    onUpdateCategoryLimitAndGroup: (CategoryEntity, Long, String) -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onOpenBudgetSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hide = uiState.prefs.hideBalances
    var isGroupedByGroup by rememberSaveable { mutableStateOf(true) }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentSort = BudgetSortMode.entries[sortIndex % BudgetSortMode.entries.size]

    var selectedCategoryItem by remember { mutableStateOf<CategoryBudgetSummaryItem?>(null) }

    val sortedItems = remember(uiState.categoryBudgetItems, currentSort) {
        when (currentSort) {
            BudgetSortMode.MAYOR_GASTO -> uiState.categoryBudgetItems.sortedByDescending { it.spentInMonth }
            BudgetSortMode.PORCENTAJE_LIMITE -> uiState.categoryBudgetItems.sortedWith(
                compareByDescending<CategoryBudgetSummaryItem> { it.monthlyLimit > 0L }
                    .thenByDescending { it.percentageUsed }
                    .thenByDescending { it.spentInMonth }
            )
            BudgetSortMode.ALFABETICO -> uiState.categoryBudgetItems.sortedBy { it.category.name }
        }
    }

    val totalMonthBudgeted = remember(uiState.categoryBudgetItems) {
        uiState.categoryBudgetItems.sumOf { it.monthlyLimit }
    }
    val totalMonthSpent = remember(uiState.categoryBudgetItems) {
        uiState.categoryBudgetItems.sumOf { it.spentInMonth }
    }

    val groupedMap = remember(sortedItems) {
        val groupOrder = listOf(
            "Estilo de vida",
            "Hogar y servicios",
            "Transporte y movilidad",
            "Finanzas y negocio",
            "Sin grupo"
        )
        sortedItems.groupBy { it.groupName.ifBlank { "Sin grupo" } }
            .entries
            .sortedBy { (gName, _) ->
                val idx = groupOrder.indexOfFirst { it.equals(gName, ignoreCase = true) }
                if (idx >= 0) idx else 99
            }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("budget_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Barra superior de controles: Selector de mes + Ordenar + Agrupar/Desagrupar + Ajustes
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onChangeMonth(-1) },
                        modifier = Modifier.testTag("budget_prev_month_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Mes anterior"
                        )
                    }
                    Text(
                        text = uiState.budgetMonthTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    IconButton(
                        onClick = { onChangeMonth(1) },
                        modifier = Modifier.testTag("budget_next_month_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Mes siguiente"
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Botón de ordenar
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clickable { sortIndex = (sortIndex + 1) % BudgetSortMode.entries.size }
                            .testTag("budget_sort_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Ordenar",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = currentSort.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Botón de agrupar / desagrupar
                    IconButton(
                        onClick = { isGroupedByGroup = !isGroupedByGroup },
                        modifier = Modifier.testTag("budget_group_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (isGroupedByGroup) Icons.Default.FolderOpen else Icons.Default.ViewAgenda,
                            contentDescription = if (isGroupedByGroup) "Desagrupar categorías" else "Agrupar categorías",
                            tint = if (isGroupedByGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Botón de ajustes de presupuesto
                    IconButton(
                        onClick = onOpenBudgetSettings,
                        modifier = Modifier.testTag("budget_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ajustes de presupuesto"
                        )
                    }
                }
            }
        }

        // 2. Resumen rápido del mes (Total gastado vs Límite total asignado)
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Total gastado en ${uiState.budgetMonthTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.format(totalMonthSpent, hideBalance = hide),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Presupuesto asignado",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (totalMonthBudgeted > 0L) {
                                CopFormatter.format(totalMonthBudgeted, hideBalance = hide)
                            } else {
                                "Sin límites"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 3. Lista de categorías agrupadas por grupos o lista plana
        if (isGroupedByGroup) {
            groupedMap.forEach { (groupTitle, itemsInGroup) ->
                val groupTotalSpent = itemsInGroup.sumOf { it.spentInMonth }

                item(key = "group_header_$groupTitle") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = groupTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = CopFormatter.format(groupTotalSpent, hideBalance = hide),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(
                    items = itemsInGroup,
                    key = { "cat_${it.category.id}" }
                ) { item ->
                    BudgetCategoryRowCard(
                        item = item,
                        hideBalances = hide,
                        onClick = { selectedCategoryItem = item }
                    )
                }
            }
        } else {
            items(
                items = sortedItems,
                key = { "flat_cat_${it.category.id}" }
            ) { item ->
                BudgetCategoryRowCard(
                    item = item,
                    hideBalances = hide,
                    onClick = { selectedCategoryItem = item }
                )
            }
        }
    }

    // Modal al tocar una categoría: muestra sus transacciones del mes y permite fijar o editar su límite mensual
    selectedCategoryItem?.let { catItem ->
        CategoryDetailAndLimitDialog(
            item = catItem,
            monthTitle = uiState.budgetMonthTitle,
            hideBalances = hide,
            onSaveLimitAndGroup = { limit, group ->
                onUpdateCategoryLimitAndGroup(catItem.category, limit, group)
                selectedCategoryItem = null
            },
            onEditTransaction = { tx ->
                selectedCategoryItem = null
                onEditTransaction(tx)
            },
            onDismiss = { selectedCategoryItem = null }
        )
    }
}

@Composable
private fun BudgetCategoryRowCard(
    item: CategoryBudgetSummaryItem,
    hideBalances: Boolean,
    onClick: () -> Unit
) {
    val hasLimit = item.monthlyLimit > 0L
    val barColor = when {
        !hasLimit -> Color(item.category.colorHex)
        item.percentageUsed >= 100 -> MaterialTheme.colorScheme.error // Rojo al 100%
        item.percentageUsed >= 80 -> Color(0xFFF59E0B) // Amarillo al 80%
        else -> Color(0xFF10B981) // Verde saludable < 80%
    }

    val targetProgress = when {
        hasLimit -> (item.spentInMonth.toFloat() / item.monthlyLimit.toFloat()).coerceIn(0f, 1f)
        item.spentInMonth > 0L -> 0.35f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(targetValue = targetProgress, label = "cat_budget_progress")

    val txCountText = if (item.transactionsCount == 1) {
        "1 transacción"
    } else {
        "${item.transactionsCount} transacciones"
    }

    val limitLabel = if (hasLimit) {
        "Límite: ${CopFormatter.format(item.monthlyLimit, hideBalance = hideBalances)} (${item.percentageUsed}%)"
    } else {
        "Sin límite"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("budget_category_card_${item.category.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Fila superior: Ícono + Nombre a la izquierda, Total gastado a la derecha
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
                        iconName = item.category.iconName,
                        colorHex = item.category.colorHex,
                        size = 40.dp
                    )
                    Text(
                        text = item.category.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = CopFormatter.format(item.spentInMonth, hideBalance = hideBalances),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (hasLimit && item.percentageUsed >= 100) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Barra de progreso debajo
            LinearProgressIndicator(
                progress = { animatedProgress },
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            // Fila inferior: "N transacciones" a la izquierda, Límite mensual o "Sin límite" a la derecha
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = txCountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = limitLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (hasLimit) FontWeight.Bold else FontWeight.Medium,
                    color = if (hasLimit && item.percentageUsed >= 80) barColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryDetailAndLimitDialog(
    item: CategoryBudgetSummaryItem,
    monthTitle: String,
    hideBalances: Boolean,
    onSaveLimitAndGroup: (Long, String) -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var limitInput by remember {
        mutableStateOf(if (item.monthlyLimit > 0L) item.monthlyLimit.toString() else "")
    }
    var selectedGroup by remember {
        mutableStateOf(item.groupName.ifBlank { "Estilo de vida" })
    }
    val availableGroups = listOf(
        "Estilo de vida",
        "Hogar y servicios",
        "Transporte y movilidad",
        "Finanzas y negocio",
        "Sin grupo"
    )
    val dateFormat = remember { SimpleDateFormat("dd MMM • h:mm a", Locale("es", "CO")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CategoryOrAccountBadge(
                    iconName = item.category.iconName,
                    colorHex = item.category.colorHex,
                    size = 36.dp
                )
                Column {
                    Text(text = item.category.name, fontWeight = FontWeight.Bold)
                    Text(
                        text = "$monthTitle • Gastado: ${CopFormatter.format(item.spentInMonth, hideBalance = hideBalances)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Límite mensual de presupuesto (deja vacío o en 0 para 'Sin límite'):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { limitInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Límite mensual en COP (ej. 450000)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Grupo de categoría:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    availableGroups.chunked(2).forEach { rowGroups ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowGroups.forEach { grp ->
                                FilterChip(
                                    selected = selectedGroup.equals(grp, ignoreCase = true),
                                    onClick = { selectedGroup = grp },
                                    label = { Text(grp, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "Transacciones en $monthTitle (${item.transactionsInMonth.size}):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                if (item.transactionsInMonth.isEmpty()) {
                    Text(
                        text = "No hay gastos pagados en esta categoría durante este mes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    item.transactionsInMonth.take(6).forEach { tx ->
                        val netAmt = AvailableToSpendCalculator.netCategoryExpenseAmount(tx)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditTransaction(tx) }
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
                                        text = tx.note.ifBlank { item.category.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = dateFormat.format(Date(tx.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = CopFormatter.format(netAmt, hideBalance = hideBalances),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limitVal = limitInput.toLongOrNull() ?: 0L
                    onSaveLimitAndGroup(limitVal, selectedGroup)
                }
            ) {
                Text("Guardar límite")
            }
        },
        dismissButton = {
            Row {
                if (item.monthlyLimit > 0L) {
                    OutlinedButton(
                        onClick = { onSaveLimitAndGroup(0L, selectedGroup) }
                    ) {
                        Text("Quitar límite")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        }
    )
}

/**
 * Modal de Metas de Ahorro accesible desde el menú superior derecho ("Metas").
 */
@Composable
fun GoalsDialogModal(
    uiState: MiPlataUiState,
    onSaveGoal: (String, Long, Long, Int) -> Unit,
    onContributeToGoal: (SavingGoalEntity, Long, Long) -> Unit,
    onDeleteGoal: (SavingGoalEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val hide = uiState.prefs.hideBalances
    var showCreateForm by remember { mutableStateOf(false) }
    var goalToContribute by remember { mutableStateOf<SavingGoalEntity?>(null) }

    var name by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("2000000") }
    var monthlyText by remember { mutableStateOf("250000") }
    var monthsText by remember { mutableStateOf("8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Metas de ahorro", fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = { showCreateForm = !showCreateForm }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showCreateForm) "Ver lista" else "Nueva")
                }
            }
        },
        text = {
            if (showCreateForm) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre de la meta (ej. Viaje, Moto)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it.filter { c -> c.isDigit() } },
                        label = { Text("Monto objetivo (COP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = monthlyText,
                        onValueChange = { monthlyText = it.filter { c -> c.isDigit() } },
                        label = { Text("Aporte mensual sugerido (COP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = monthsText,
                        onValueChange = { monthsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Plazo en meses") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val t = targetText.toLongOrNull() ?: 0L
                            val m = monthlyText.toLongOrNull() ?: 100_000L
                            val mo = monthsText.toIntOrNull() ?: 6
                            if (name.isNotBlank() && t > 0L) {
                                onSaveGoal(name, t, m, mo)
                                name = ""
                                showCreateForm = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Guardar meta")
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(340.dp)
                ) {
                    if (uiState.savingGoals.isEmpty()) {
                        item {
                            Text(
                                text = "Aún no tienes metas de ahorro. Toca '+ Nueva' para crear tu primera meta.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(uiState.savingGoals, key = { it.id }) { goal ->
                            val pct = if (goal.targetAmount > 0L) {
                                ((goal.savedAmount.toDouble() / goal.targetAmount.toDouble()) * 100.0)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                            } else 0
                            val remaining = (goal.targetAmount - goal.savedAmount).coerceAtLeast(0L)
                            val monthsRem = if (goal.monthlyTargetContribution > 0L && remaining > 0L) {
                                ceil(remaining.toDouble() / goal.monthlyTargetContribution.toDouble()).toInt()
                            } else 0
                            val cal = Calendar.getInstance().apply { add(Calendar.MONTH, monthsRem) }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = goal.name, fontWeight = FontWeight.Bold)
                                        IconButton(
                                            onClick = { onDeleteGoal(goal) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Eliminar meta",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "${CopFormatter.format(goal.savedAmount, hide)} / ${CopFormatter.format(goal.targetAmount, hide)} ($pct%)",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (monthsRem <= 0) "¡Meta cumplida!" else "Est: ${CopFormatter.formatShortDate(cal.timeInMillis)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilledTonalButton(onClick = { goalToContribute = goal }) {
                                            Text("+ Aportar")
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
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )

    goalToContribute?.let { goal ->
        var amountStr by remember { mutableStateOf(goal.monthlyTargetContribution.toString()) }
        var selectedAccId by remember { mutableLongStateOf(uiState.prefs.lastUsedAccountId) }
        AlertDialog(
            onDismissRequest = { goalToContribute = null },
            title = { Text("Aportar a ${goal.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it.filter { c -> c.isDigit() } },
                        label = { Text("Monto en COP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Cuenta origen:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.accountsWithBalance.take(3).forEach { acc ->
                            FilterChip(
                                selected = selectedAccId == acc.account.id,
                                onClick = { selectedAccId = acc.account.id },
                                label = { Text(acc.account.name) }
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
                            onContributeToGoal(goal, amt, selectedAccId)
                            goalToContribute = null
                        }
                    }
                ) {
                    Text("Aportar")
                }
            },
            dismissButton = {
                TextButton(onClick = { goalToContribute = null }) { Text("Cancelar") }
            }
        )
    }
}
