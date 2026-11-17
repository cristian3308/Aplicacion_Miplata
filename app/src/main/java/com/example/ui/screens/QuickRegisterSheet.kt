package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CreditScore
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AccountType
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.ParsedMovementDraft
import com.example.data.RecurrenceFrequency
import com.example.data.TransactionEntity
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import com.example.domain.CopFormatter
import com.example.ui.MiPlataUiState
import com.example.ui.components.CategoryOrAccountBadge
import com.example.ui.components.ShimmerSkeletonBox

/**
 * BOTÓN "+": Hoja "Agregar transacción"
 * - Paso inicial: Lista con ícono, título y descripción corta de cada una de las 8 opciones
 *   (Gasto, Pago, Ingreso, Transferencia, Reembolso, Compra a cuotas, Pago de tarjeta, Cashback)
 *   + barra de entrada por texto, voz y foto de recibo con Gemini.
 * - Al elegir una opción (o al editar una transacción), muestra el teclado numérico grande,
 *   estado (Pagado/Pendiente), recurrencia (Semanal, Quincenal, Mensual, Anual, Personalizada),
 *   selector de N cuotas y selector de cuenta/categoría.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickRegisterSheet(
    uiState: MiPlataUiState,
    editingTransaction: TransactionEntity?,
    isParsingAiDraft: Boolean,
    parsedDraft: ParsedMovementDraft?,
    parseDraftError: String?,
    onParseNaturalText: (String) -> Unit,
    onParseReceiptBitmap: (android.graphics.Bitmap) -> Unit,
    onAutoCategorizeNote: (String, (CategoryEntity) -> Unit) -> Unit,
    onClearDraft: () -> Unit,
    onSaveMovement: (
        id: Long,
        type: TransactionType,
        amount: Long,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        note: String,
        receiptUri: String?,
        isRecurring: Boolean,
        status: TransactionStatus,
        recurrence: RecurrenceFrequency,
        totalInstallments: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Si estamos editando, entramos directo al formulario; si es nuevo, mostramos primero el selector de las 8 opciones
    var showTypeChooserMenu by rememberSaveable { mutableStateOf(editingTransaction == null) }

    var selectedType by rememberSaveable {
        mutableStateOf(editingTransaction?.type ?: TransactionType.GASTO)
    }
    var selectedStatus by rememberSaveable {
        mutableStateOf(
            editingTransaction?.status
                ?: if (selectedType == TransactionType.PAGO_PROGRAMADO) TransactionStatus.PENDIENTE else TransactionStatus.PAGADO
        )
    }
    var selectedRecurrence by rememberSaveable {
        mutableStateOf(editingTransaction?.recurrence ?: RecurrenceFrequency.NINGUNA)
    }
    var selectedInstallments by rememberSaveable {
        mutableIntStateOf(editingTransaction?.totalInstallments ?: 3)
    }
    var amountDigits by rememberSaveable {
        mutableStateOf(editingTransaction?.amount?.toString() ?: "")
    }

    val creditCardAccountId = uiState.accountsWithBalance
        .firstOrNull { it.account.type.normalized() == AccountType.TARJETA_CREDITO }?.account?.id
    val defaultAccountId = editingTransaction?.accountId ?: uiState.prefs.lastUsedAccountId

    var selectedAccountId by rememberSaveable { mutableLongStateOf(defaultAccountId) }
    val defaultToAccountId = creditCardAccountId
        ?: uiState.accountsWithBalance.firstOrNull { it.account.id != selectedAccountId }?.account?.id
        ?: 1L
    var selectedToAccountId by rememberSaveable {
        mutableLongStateOf(editingTransaction?.toAccountId ?: defaultToAccountId)
    }

    val filteredCategories = remember(uiState.categories, selectedType) {
        val catType = if (selectedType == TransactionType.INGRESO || selectedType == TransactionType.CASHBACK) {
            CategoryType.INGRESO
        } else {
            CategoryType.GASTO
        }
        uiState.categories.filter { it.type == catType }
    }

    var selectedCategoryId by rememberSaveable {
        mutableLongStateOf(
            editingTransaction?.categoryId
                ?: filteredCategories.firstOrNull()?.id
                ?: 1L
        )
    }
    var noteText by rememberSaveable { mutableStateOf(editingTransaction?.note ?: "") }
    var receiptUriString by rememberSaveable { mutableStateOf(editingTransaction?.receiptUri) }
    var naturalVoiceText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(selectedType, filteredCategories) {
        if (filteredCategories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = filteredCategories.firstOrNull()?.id ?: 1L
        }
        if (selectedType == TransactionType.COMPRA_CUOTAS || selectedType == TransactionType.CASHBACK) {
            creditCardAccountId?.let { selectedAccountId = it }
        }
        if (selectedType == TransactionType.PAGO_TARJETA) {
            creditCardAccountId?.let { selectedToAccountId = it }
        }
    }

    LaunchedEffect(parsedDraft) {
        parsedDraft?.let { draft ->
            selectedType = draft.type
            showTypeChooserMenu = false
            if (draft.amount > 0L) {
                amountDigits = draft.amount.toString()
            }
            noteText = draft.note
            uiState.accountsWithBalance.find {
                it.account.name.equals(draft.accountName, ignoreCase = true)
            }?.let { matchedAcc ->
                selectedAccountId = matchedAcc.account.id
            }
            uiState.categories.find {
                it.name.equals(draft.categoryName, ignoreCase = true)
            }?.let { matchedCat ->
                selectedCategoryId = matchedCat.id
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                naturalVoiceText = spoken
                onParseNaturalText(spoken)
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            receiptUriString = uri.toString()
            runCatching {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                onParseReceiptBitmap(bitmap)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .testTag("quick_register_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Barra inteligente con Gemini (siempre disponible por texto, voz o foto de recibo)
            GeminiQuickInputBar(
                naturalVoiceText = naturalVoiceText,
                onNaturalTextChange = { naturalVoiceText = it },
                isParsingAiDraft = isParsingAiDraft,
                parseDraftError = parseDraftError,
                parsedDraft = parsedDraft,
                onVoiceClick = {
                    runCatching {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ej: Gasté 25 mil en almuerzo con Nequi")
                        }
                        voiceLauncher.launch(intent)
                    }
                },
                onPhotoClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onSendTextToGemini = { onParseNaturalText(naturalVoiceText) },
                onConfirmDraft = { draft ->
                    if (draft.amount > 0L) {
                        onSaveMovement(
                            editingTransaction?.id ?: 0L,
                            selectedType,
                            draft.amount,
                            selectedAccountId,
                            if (selectedType == TransactionType.TRANSFERENCIA || selectedType == TransactionType.PAGO_TARJETA) selectedToAccountId else null,
                            if (selectedType == TransactionType.TRANSFERENCIA || selectedType == TransactionType.PAGO_TARJETA) null else selectedCategoryId,
                            noteText,
                            receiptUriString,
                            selectedRecurrence != RecurrenceFrequency.NINGUNA,
                            selectedStatus,
                            selectedRecurrence,
                            selectedInstallments
                        )
                        onClearDraft()
                        onDismiss()
                    }
                }
            )

            if (showTypeChooserMenu) {
                Text(
                    text = "Agregar transacción",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Elige el tipo de operación o usa el registro rápido:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TransactionType.entries.forEach { option ->
                    TransactionTypeOptionRow(
                        type = option,
                        onClick = {
                            selectedType = option
                            selectedStatus = if (option == TransactionType.PAGO_PROGRAMADO) {
                                TransactionStatus.PENDIENTE
                            } else {
                                TransactionStatus.PAGADO
                            }
                            if (option == TransactionType.PAGO_PROGRAMADO) {
                                selectedRecurrence = RecurrenceFrequency.MENSUAL
                            }
                            showTypeChooserMenu = false
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // ENCABEZADO DEL FORMULARIO DE REGISTRO
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editingTransaction == null) {
                            IconButton(onClick = { showTypeChooserMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Cambiar tipo de transacción"
                                )
                            }
                        }
                        Column {
                            Text(
                                text = selectedType.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedType.shortDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Selector de Estado (Pagado / Pendiente) y Recurrencia
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransactionStatus.entries.forEach { st ->
                        FilterChip(
                            selected = selectedStatus == st,
                            onClick = { selectedStatus = st },
                            label = { Text(st.label) }
                        )
                    }
                    RecurrenceFrequency.entries.forEach { freq ->
                        FilterChip(
                            selected = selectedRecurrence == freq,
                            onClick = { selectedRecurrence = freq },
                            label = { Text(freq.label) }
                        )
                    }
                }

                // Si es Compra a cuotas, selector de número de cuotas (2 a 36)
                if (selectedType == TransactionType.COMPRA_CUOTAS) {
                    Text(
                        text = "Número de cuotas mensuales (genera automáticamente las cuotas pendientes):",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2, 3, 6, 12, 18, 24, 36).forEach { n ->
                            FilterChip(
                                selected = selectedInstallments == n,
                                onClick = { selectedInstallments = n },
                                label = { Text("$n cuotas") }
                            )
                        }
                    }
                }

                // Pantalla del monto en COP
                val currentAmount = amountDigits.toLongOrNull() ?: 0L
                val amountAccent = when (selectedType) {
                    TransactionType.INGRESO,
                    TransactionType.REEMBOLSO,
                    TransactionType.CASHBACK -> MaterialTheme.colorScheme.secondary
                    TransactionType.TRANSFERENCIA,
                    TransactionType.PAGO_TARJETA -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (selectedType == TransactionType.COMPRA_CUOTAS) {
                                    "Valor total de la compra ($selectedInstallments cuotas de ${CopFormatter.format(currentAmount / selectedInstallments.coerceAtLeast(1))})"
                                } else {
                                    "Monto en COP (${selectedStatus.label})"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = CopFormatter.format(currentAmount),
                                style = MaterialTheme.typography.displayMedium,
                                color = amountAccent,
                                modifier = Modifier.testTag("quick_register_amount_display")
                            )
                        }
                        IconButton(
                            onClick = {
                                if (amountDigits.isNotEmpty()) {
                                    amountDigits = amountDigits.dropLast(1)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Borrar dígito"
                            )
                        }
                    }
                }

                // Teclado numérico grande
                NumericKeypadGrid(
                    onKeyClick = { token ->
                        when (token) {
                            "C" -> amountDigits = ""
                            "000" -> if (amountDigits.isNotEmpty() && amountDigits.length <= 9) {
                                amountDigits += "000"
                            }
                            else -> if (amountDigits.length <= 10) {
                                if (amountDigits == "0") amountDigits = token else amountDigits += token
                            }
                        }
                    }
                )

                // Selector de Cuenta origen
                Text(
                    text = when (selectedType) {
                        TransactionType.PAGO_TARJETA -> "Pagar desde cuenta de Débito:"
                        TransactionType.COMPRA_CUOTAS -> "Tarjeta de crédito de la compra:"
                        TransactionType.TRANSFERENCIA -> "Cuenta de origen:"
                        else -> "Cuenta:"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.accountsWithBalance.forEach { accItem ->
                        FilterChip(
                            selected = selectedAccountId == accItem.account.id,
                            onClick = { selectedAccountId = accItem.account.id },
                            label = { Text(accItem.account.name) }
                        )
                    }
                }

                if (selectedType == TransactionType.TRANSFERENCIA || selectedType == TransactionType.PAGO_TARJETA) {
                    Text(
                        text = if (selectedType == TransactionType.PAGO_TARJETA) "Tarjeta de crédito a abonar:" else "Cuenta destino:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val destCandidates = if (selectedType == TransactionType.PAGO_TARJETA) {
                            uiState.accountsWithBalance.filter {
                                it.account.type.normalized() == AccountType.TARJETA_CREDITO
                            }.ifEmpty { uiState.accountsWithBalance }
                        } else {
                            uiState.accountsWithBalance
                        }
                        destCandidates.forEach { accItem ->
                            FilterChip(
                                selected = selectedToAccountId == accItem.account.id,
                                onClick = { selectedToAccountId = accItem.account.id },
                                label = { Text(accItem.account.name) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Categoría:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(164.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredCategories, key = { it.id }) { cat ->
                            val isSelected = selectedCategoryId == cat.id
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) Color(cat.colorHex).copy(alpha = 0.22f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) Color(cat.colorHex) else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selectedCategoryId = cat.id }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CategoryOrAccountBadge(
                                    iconName = cat.iconName,
                                    colorHex = cat.colorHex,
                                    size = 32.dp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Nota y auto-categorización con Gemini
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Nota o comercio (ej. Arriendo, Éxito, Netflix)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            onAutoCategorizeNote(noteText) { matchedCat ->
                                selectedCategoryId = matchedCat.id
                            }
                        },
                        enabled = noteText.isNotBlank()
                    ) {
                        Text("Auto-Cat")
                    }
                }

                Button(
                    onClick = {
                        val amount = amountDigits.toLongOrNull() ?: 0L
                        if (amount > 0L) {
                            val needsDest = selectedType == TransactionType.TRANSFERENCIA || selectedType == TransactionType.PAGO_TARJETA
                            onSaveMovement(
                                editingTransaction?.id ?: 0L,
                                selectedType,
                                amount,
                                selectedAccountId,
                                if (needsDest) selectedToAccountId else null,
                                if (needsDest) null else selectedCategoryId,
                                noteText,
                                receiptUriString,
                                selectedRecurrence != RecurrenceFrequency.NINGUNA,
                                selectedStatus,
                                selectedRecurrence,
                                selectedInstallments
                            )
                            onClearDraft()
                            onDismiss()
                        }
                    },
                    enabled = currentAmount > 0L,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("save_movement_button")
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Guardar ${selectedType.label} (${CopFormatter.format(currentAmount)})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TransactionTypeOptionRow(
    type: TransactionType,
    onClick: () -> Unit
) {
    val (icon, tint) = when (type) {
        TransactionType.GASTO -> Icons.Default.ShoppingBag to Color(0xFFF87171)
        TransactionType.PAGO_PROGRAMADO -> Icons.Default.EventRepeat to Color(0xFFF59E0B)
        TransactionType.INGRESO -> Icons.Default.TrendingUp to Color(0xFF10B981)
        TransactionType.TRANSFERENCIA -> Icons.Default.SwapHoriz to Color(0xFF38BDF8)
        TransactionType.REEMBOLSO -> Icons.Default.Replay to Color(0xFF34D399)
        TransactionType.COMPRA_CUOTAS -> Icons.Default.CreditCard to Color(0xFFA855F7)
        TransactionType.PAGO_TARJETA -> Icons.Default.CreditScore to Color(0xFF60A5FA)
        TransactionType.CASHBACK -> Icons.Default.CardGiftcard to Color(0xFF2DD4BF)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("add_tx_option_${type.name.lowercase()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = type.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = type.shortDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GeminiQuickInputBar(
    naturalVoiceText: String,
    onNaturalTextChange: (String) -> Unit,
    isParsingAiDraft: Boolean,
    parseDraftError: String?,
    parsedDraft: ParsedMovementDraft?,
    onVoiceClick: () -> Unit,
    onPhotoClick: () -> Unit,
    onSendTextToGemini: () -> Unit,
    onConfirmDraft: (ParsedMovementDraft) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Registro por texto, voz o foto con Gemini",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Row {
                    IconButton(onClick = onVoiceClick, modifier = Modifier.testTag("voice_input_button")) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = "Dictar con voz")
                    }
                    IconButton(onClick = onPhotoClick, modifier = Modifier.testTag("receipt_photo_button")) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = "Fotografiar recibo")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = naturalVoiceText,
                    onValueChange = onNaturalTextChange,
                    placeholder = { Text("Ej: gasté 25 mil en almuerzo con Nequi") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("natural_language_input")
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onSendTextToGemini,
                    enabled = naturalVoiceText.isNotBlank() && !isParsingAiDraft
                ) {
                    Text("IA")
                }
            }

            if (isParsingAiDraft) {
                ShimmerSkeletonBox(height = 48.dp)
            }
            if (parseDraftError != null) {
                Text(
                    text = parseDraftError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            parsedDraft?.let { draft ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
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
                                text = "${draft.type.label}: ${CopFormatter.format(draft.amount)} • ${draft.categoryName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${draft.accountName} • ${draft.note}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { onConfirmDraft(draft) }) {
                            Text("Confirmar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumericKeypadGrid(
    onKeyClick: (String) -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "000")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Surface(
                        onClick = { onKeyClick(key) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("keypad_btn_$key")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (key == "000") ".000" else key,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
