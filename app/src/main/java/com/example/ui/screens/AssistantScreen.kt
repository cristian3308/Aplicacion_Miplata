package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AiFinancialReport
import com.example.ui.ChatMessageItem
import com.example.ui.components.ShimmerSkeletonBox

/**
 * Pestaña 5 — ASISTENTE (Gemini):
 * Chat financiero en español colombiano con resumen agregado (sin enviar la base de datos cruda)
 * + Resumen semanal/mensual estructurado (qué mejoró, qué empeoró y 3 recomendaciones concretas con cifras).
 */
@Composable
fun AssistantScreen(
    messages: List<ChatMessageItem>,
    isChatLoading: Boolean,
    aiReport: AiFinancialReport?,
    isGeneratingReport: Boolean,
    reportError: String?,
    onSendQuestion: (String) -> Unit,
    onGenerateReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputQuestion by rememberSaveable { mutableStateOf("") }

    val quickPrompts = listOf(
        "¿En qué gasté más este mes?",
        "¿Puedo comprar algo de $300.000 esta semana?",
        "¿Cuánto debo ahorrar al mes para mi meta?",
        "Dame un diagnóstico directo de mis cuentas hoy"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("assistant_screen")
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Gemini",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Asistente MiPlata (Gemini)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Privacidad activa: solo usa totales agregados en COP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalButton(
                onClick = onGenerateReport,
                enabled = !isGeneratingReport,
                modifier = Modifier.testTag("generate_ai_report_button")
            ) {
                Icon(imageVector = Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Resumen mes")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Lista de mensajes + Tarjeta de Resumen Semanal/Mensual Estructurado
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isGeneratingReport) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Analizando tus cifras con Gemini...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        ShimmerSkeletonBox(height = 140.dp)
                    }
                }
            }

            if (reportError != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = reportError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            if (aiReport != null && !isGeneratingReport) {
                item {
                    StructuredReportCard(report = aiReport)
                }
            }

            items(items = messages, key = { it.id }) { msg ->
                ChatBubbleRow(msg = msg)
            }
        }

        // Chips de preguntas frecuentes con un toque
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickPrompts.forEach { q ->
                AssistChip(
                    onClick = { onSendQuestion(q) },
                    enabled = !isChatLoading,
                    label = { Text(q) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Caja de texto para chatear con Gemini
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 84.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputQuestion,
                onValueChange = { inputQuestion = it },
                placeholder = { Text("Pregúntale a Gemini sobre tu plata...") },
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("assistant_chat_input")
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputQuestion.isNotBlank()) {
                        onSendQuestion(inputQuestion)
                        inputQuestion = ""
                    }
                },
                enabled = inputQuestion.isNotBlank() && !isChatLoading,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("assistant_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar pregunta",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun StructuredReportCard(report: AiFinancialReport) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = report.periodTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Qué mejoró:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(report.whatImproved, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Qué empeoró / atención:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(report.whatWorsened, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = "3 Recomendaciones concretas:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )

            report.recommendations.forEachIndexed { idx, rec ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${idx + 1}. $rec",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleRow(msg: ChatMessageItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        val bg = when {
            msg.isUser -> MaterialTheme.colorScheme.primary
            msg.isError -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surface
        }
        val fg = when {
            msg.isUser -> MaterialTheme.colorScheme.onPrimary
            msg.isError -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (msg.isUser) 18.dp else 4.dp,
                bottomEnd = if (msg.isUser) 4.dp else 18.dp
            ),
            color = bg,
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (msg.isStreaming && msg.text.isBlank()) {
                Box(modifier = Modifier.padding(12.dp)) {
                    ShimmerSkeletonBox(
                        modifier = Modifier.width(180.dp),
                        height = 28.dp
                    )
                }
            } else {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}
