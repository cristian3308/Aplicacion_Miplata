package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.CopFormatter
import com.example.ui.AnalyticsRangeFilter
import com.example.ui.BalanceEvolutionPoint
import com.example.ui.CategoryMonthComparisonItem
import com.example.ui.CategorySliceData
import com.example.ui.DailyExpensePoint
import com.example.ui.MiPlataUiState
import com.example.ui.MonthlyBarPoint
import com.example.ui.components.CategoryOrAccountBadge
import com.example.ui.components.IllustratedEmptyState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pestaña 3 — ANÁLISIS:
 * Selector de período (Semana, Mes, Año, Personalizado) y los 5 gráficos interactivos
 * con animación de entrada, tooltip al tocar y leyenda.
 */
@Composable
fun AnalyticsScreen(
    uiState: MiPlataUiState,
    onSelectFilter: (AnalyticsRangeFilter, Int) -> Unit,
    onLoadDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hideBalances = uiState.prefs.hideBalances
    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.analyticsFilter, uiState.allTransactions.size) {
        animationPlayed = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "charts_entry_animation"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("analytics_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Selector de período: Semana, Mes, Año, Personalizado
        item {
            Column {
                Text(
                    text = "Análisis financiero interactivo",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalyticsRangeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.analyticsFilter == filter,
                            onClick = { onSelectFilter(filter, uiState.customAnalyticsDays) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("analytics_filter_${filter.name.lowercase()}")
                        )
                    }
                }
                AnimatedVisibility(visible = uiState.analyticsFilter == AnalyticsRangeFilter.PERSONALIZADO) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "Rango personalizado: últimos ${uiState.customAnalyticsDays} días",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = uiState.customAnalyticsDays.toFloat(),
                            onValueChange = { onSelectFilter(AnalyticsRangeFilter.PERSONALIZADO, it.roundToInt()) },
                            valueRange = 14f..180f
                        )
                    }
                }
            }
        }

        if (uiState.allTransactions.isEmpty()) {
            item {
                IllustratedEmptyState(
                    title = "Sin datos suficientes para graficar",
                    subtitle = "Carga la demo de 3 meses o registra tus primeros movimientos para explorar los 5 gráficos interactivos.",
                    actionLabel = "Ver demo de 3 meses",
                    onActionClick = onLoadDemo
                )
            }
        } else {
            // 1. DONA DE GASTOS POR CATEGORÍA (Tocar un segmento abre el detalle de esa categoría)
            item {
                InteractiveDonutChartCard(
                    slices = uiState.donutSlices,
                    entryProgress = entryProgress,
                    hideBalances = hideBalances
                )
            }

            // 2. BARRAS DE INGRESOS VS. GASTOS POR MES (Últimos 6 meses)
            item {
                SixMonthIncomeVsExpenseCard(
                    bars = uiState.sixMonthBars,
                    entryProgress = entryProgress,
                    hideBalances = hideBalances
                )
            }

            // 3. LÍNEA DE EVOLUCIÓN DEL SALDO TOTAL
            item {
                BalanceEvolutionLineCard(
                    points = uiState.balanceEvolution,
                    entryProgress = entryProgress,
                    hideBalances = hideBalances
                )
            }

            // 4. BARRAS DE GASTO DIARIO DEL MES CON LA LÍNEA DEL PROMEDIO
            item {
                DailyExpenseWithAverageCard(
                    dailyPoints = uiState.dailyExpensesCurrentMonth,
                    averageExpense = uiState.dailyAverageExpense,
                    entryProgress = entryProgress,
                    hideBalances = hideBalances
                )
            }

            // 5. COMPARACIÓN CON EL MES ANTERIOR POR CATEGORÍA (Subió / Bajó en %)
            item {
                CategoryMonthComparisonCard(
                    comparisons = uiState.categoryComparisons,
                    hideBalances = hideBalances
                )
            }
        }
    }
}

@Composable
private fun InteractiveDonutChartCard(
    slices: List<CategorySliceData>,
    entryProgress: Float,
    hideBalances: Boolean
) {
    var selectedSliceIndex by remember(slices) { mutableIntStateOf(if (slices.isNotEmpty()) 0 else -1) }
    val selectedSlice = slices.getOrNull(selectedSliceIndex)
    val totalSpent = slices.sumOf { it.amount }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "1. Gastos por categoría (Dona interactiva)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Toca cualquier segmento de la dona o elemento de la leyenda para abrir su detalle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (slices.isEmpty()) {
                Text(
                    text = "No hay gastos registrados en este período.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(200.dp)
                            .pointerInput(slices) {
                                detectTapGestures { tapOffset ->
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val dx = tapOffset.x - center.x
                                    val dy = tapOffset.y - center.y
                                    val dist = hypot(dx, dy)
                                    val outerR = min(size.width, size.height) / 2f
                                    val innerR = outerR * 0.45f
                                    if (dist in innerR..outerR) {
                                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                        angle = (angle + 90f + 360f) % 360f
                                        var accAngle = 0f
                                        slices.forEachIndexed { index, slice ->
                                            val sweep = (slice.percentage / 100f) * 360f
                                            if (angle >= accAngle && angle <= accAngle + sweep) {
                                                selectedSliceIndex = index
                                                return@detectTapGestures
                                            }
                                            accAngle += sweep
                                        }
                                    }
                                }
                            }
                    ) {
                        var startAngle = -90f
                        slices.forEachIndexed { idx, slice ->
                            val sweepAngle = (slice.percentage / 100f) * 360f * entryProgress
                            val isSelected = idx == selectedSliceIndex
                            val strokeW = if (isSelected) 36.dp.toPx() else 28.dp.toPx()
                            drawArc(
                                color = Color(slice.category.colorHex),
                                startAngle = startAngle,
                                sweepAngle = max(1f, sweepAngle - 2f),
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Butt)
                            )
                            startAngle += sweepAngle
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = selectedSlice?.category?.name ?: "Total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CopFormatter.formatCompact(selectedSlice?.amount ?: totalSpent, hideBalances),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedSlice != null) {
                            Text(
                                text = "${selectedSlice.percentage.roundToInt()}% del total",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(selectedSlice.category.colorHex)
                            )
                        }
                    }
                }

                // Tooltip / tarjeta de detalle del segmento seleccionado
                selectedSlice?.let { s ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(s.category.colorHex).copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryOrAccountBadge(
                                    iconName = s.category.iconName,
                                    colorHex = s.category.colorHex,
                                    size = 38.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Detalle: ${s.category.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${s.transactionsCount} movimientos (${s.percentage.roundToInt()}% del gasto)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = CopFormatter.format(s.amount, hideBalance = hideBalances),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Leyenda interactiva
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEachIndexed { idx, slice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedSliceIndex = idx }
                                .padding(vertical = 4.dp, horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(slice.category.colorHex))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = slice.category.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (idx == selectedSliceIndex) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            Text(
                                text = "${CopFormatter.format(slice.amount, hideBalances)} (${slice.percentage.roundToInt()}%)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SixMonthIncomeVsExpenseCard(
    bars: List<MonthlyBarPoint>,
    entryProgress: Float,
    hideBalances: Boolean
) {
    var selectedIndex by remember(bars) { mutableIntStateOf(bars.lastIndex.coerceAtLeast(0)) }
    val selectedBar = bars.getOrNull(selectedIndex)
    val primaryGreen = MaterialTheme.colorScheme.primary
    val expenseRed = MaterialTheme.colorScheme.error

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "2. Ingresos vs. Gastos (Últimos 6 meses)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Tooltip del mes tocado
            selectedBar?.let { b ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mes: ${b.monthLabel}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Ingresos: ${CopFormatter.formatCompact(b.income, hideBalances)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = primaryGreen
                        )
                        Text(
                            text = "Gastos: ${CopFormatter.formatCompact(b.expense, hideBalances)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = expenseRed
                        )
                    }
                }
            }

            val maxVal = max(1L, bars.maxOfOrNull { max(it.income, it.expense) } ?: 1L)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .pointerInput(bars) {
                        detectTapGestures { offset ->
                            if (bars.isNotEmpty()) {
                                val slotWidth = size.width / bars.size
                                val idx = (offset.x / slotWidth).toInt().coerceIn(0, bars.lastIndex)
                                selectedIndex = idx
                            }
                        }
                    }
            ) {
                val slotWidth = size.width / max(1, bars.size)
                val barW = slotWidth * 0.28f
                val chartHeight = size.height

                bars.forEachIndexed { idx, item ->
                    val centerX = slotWidth * idx + slotWidth / 2f
                    val incH = (item.income.toFloat() / maxVal.toFloat()) * chartHeight * entryProgress
                    val expH = (item.expense.toFloat() / maxVal.toFloat()) * chartHeight * entryProgress

                    if (idx == selectedIndex) {
                        drawRoundRect(
                            color = primaryGreen.copy(alpha = 0.08f),
                            topLeft = Offset(slotWidth * idx + 4f, 0f),
                            size = Size(slotWidth - 8f, chartHeight),
                            cornerRadius = CornerRadius(12f, 12f)
                        )
                    }

                    // Barra Ingreso (Verde Esmeralda)
                    drawRoundRect(
                        color = primaryGreen,
                        topLeft = Offset(centerX - barW - 2f, chartHeight - incH),
                        size = Size(barW, incH),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    // Barra Gasto (Rojo)
                    drawRoundRect(
                        color = expenseRed,
                        topLeft = Offset(centerX + 2f, chartHeight - expH),
                        size = Size(barW, expH),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                bars.forEachIndexed { idx, item ->
                    Text(
                        text = item.monthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (idx == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (idx == selectedIndex) primaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Leyenda
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = primaryGreen, text = "Ingresos")
                LegendDot(color = expenseRed, text = "Gastos")
            }
        }
    }
}

@Composable
private fun BalanceEvolutionLineCard(
    points: List<BalanceEvolutionPoint>,
    entryProgress: Float,
    hideBalances: Boolean
) {
    var selectedIdx by remember(points) { mutableIntStateOf(points.lastIndex.coerceAtLeast(0)) }
    val selectedPoint = points.getOrNull(selectedIdx)
    val lineColor = MaterialTheme.colorScheme.primary

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "3. Evolución del saldo total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            selectedPoint?.let { pt ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Fecha: ${pt.dateLabel}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "Saldo: ${CopFormatter.format(pt.totalBalance, hideBalances)}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (pt.totalBalance < 0L) MaterialTheme.colorScheme.error else lineColor
                        )
                    }
                }
            }

            if (points.size >= 2) {
                val minBal = points.minOf { it.totalBalance }
                val maxBal = points.maxOf { it.totalBalance }
                val span = max(1L, maxBal - minBal)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .pointerInput(points) {
                            detectTapGestures { offset ->
                                val stepX = size.width / max(1, points.size - 1)
                                val idx = ((offset.x + stepX / 2f) / stepX).toInt().coerceIn(0, points.lastIndex)
                                selectedIdx = idx
                            }
                        }
                ) {
                    val stepX = size.width / max(1, points.size - 1)
                    val path = Path()
                    val coords = points.mapIndexed { idx, pt ->
                        val x = idx * stepX
                        val normY = (pt.totalBalance - minBal).toFloat() / span.toFloat()
                        val y = size.height - (normY * (size.height - 28f) * entryProgress + 14f)
                        Offset(x, y)
                    }

                    coords.forEachIndexed { idx, offset ->
                        if (idx == 0) path.moveTo(offset.x, offset.y)
                        else path.lineTo(offset.x, offset.y)
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    coords.forEachIndexed { idx, offset ->
                        drawCircle(
                            color = if (idx == selectedIdx) lineColor else lineColor.copy(alpha = 0.65f),
                            radius = if (idx == selectedIdx) 7.dp.toPx() else 4.dp.toPx(),
                            center = offset
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = points.first().dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = points[points.size / 2].dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = points.last().dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LegendDot(color = lineColor, text = "Saldo consolidado de tus cuentas (COP)")
        }
    }
}

@Composable
private fun DailyExpenseWithAverageCard(
    dailyPoints: List<DailyExpensePoint>,
    averageExpense: Long,
    entryProgress: Float,
    hideBalances: Boolean
) {
    var selectedDayIndex by remember(dailyPoints) { mutableIntStateOf(dailyPoints.lastIndex.coerceAtLeast(0)) }
    val selectedDay = dailyPoints.getOrNull(selectedDayIndex)
    val barColor = MaterialTheme.colorScheme.primary
    val overAvgColor = MaterialTheme.colorScheme.error
    val avgLineColor = MaterialTheme.colorScheme.tertiary

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "4. Gasto diario del mes y línea de promedio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            selectedDay?.let { d ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Día ${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "Gasto: ${CopFormatter.format(d.amount, hideBalances)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (d.amount > averageExpense) overAvgColor else barColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prom: ${CopFormatter.formatCompact(averageExpense, hideBalances)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = avgLineColor
                        )
                    }
                }
            }

            val maxDayVal = max(1L, max(averageExpense, dailyPoints.maxOfOrNull { it.amount } ?: 1L))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(dailyPoints) {
                        detectTapGestures { offset ->
                            if (dailyPoints.isNotEmpty()) {
                                val slotW = size.width / dailyPoints.size
                                val idx = (offset.x / slotW).toInt().coerceIn(0, dailyPoints.lastIndex)
                                selectedDayIndex = idx
                            }
                        }
                    }
            ) {
                val count = max(1, dailyPoints.size)
                val slotW = size.width / count
                val barW = max(4f, slotW * 0.62f)
                val chartH = size.height

                dailyPoints.forEachIndexed { idx, pt ->
                    val h = (pt.amount.toFloat() / maxDayVal.toFloat()) * chartH * entryProgress
                    val x = idx * slotW + (slotW - barW) / 2f
                    val color = if (pt.amount > averageExpense && averageExpense > 0L) overAvgColor else barColor
                    drawRoundRect(
                        color = if (idx == selectedDayIndex) color else color.copy(alpha = 0.72f),
                        topLeft = Offset(x, chartH - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }

                // Línea horizontal punteada del promedio diario
                val avgY = chartH - (averageExpense.toFloat() / maxDayVal.toFloat()) * chartH * entryProgress
                drawLine(
                    color = avgLineColor,
                    start = Offset(0f, avgY.coerceIn(0f, chartH)),
                    end = Offset(size.width, avgY.coerceIn(0f, chartH)),
                    strokeWidth = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = barColor, text = "Bajo el promedio")
                LegendDot(color = overAvgColor, text = "Sobre el promedio")
                LegendDot(color = avgLineColor, text = "Promedio (${CopFormatter.formatCompact(averageExpense, hideBalances)}/día)")
            }
        }
    }
}

@Composable
private fun CategoryMonthComparisonCard(
    comparisons: List<CategoryMonthComparisonItem>,
    hideBalances: Boolean
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "5. Comparación vs. mes anterior por categoría",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Variación porcentual de tus gastos de este mes frente al mes pasado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (comparisons.isEmpty()) {
                Text(
                    text = "Aún no hay suficientes meses para comparar.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val maxCatAmount = max(
                    1L,
                    comparisons.maxOfOrNull { max(it.currentMonthAmount, it.previousMonthAmount) } ?: 1L
                )

                comparisons.forEach { item ->
                    val wentUp = item.deltaPercentage > 0
                    val badgeColor = if (wentUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    val sign = if (wentUp) "+" else ""

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryOrAccountBadge(
                                    iconName = item.category.iconName,
                                    colorHex = item.category.colorHex,
                                    size = 34.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = item.category.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Mes actual: ${CopFormatter.format(item.currentMonthAmount, hideBalances)} • Ant: ${CopFormatter.format(item.previousMonthAmount, hideBalances)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (wentUp) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$sign${item.deltaPercentage}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                (item.currentMonthAmount.toFloat() / maxCatAmount.toFloat()).coerceIn(0.04f, 1f)
                            },
                            color = badgeColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
