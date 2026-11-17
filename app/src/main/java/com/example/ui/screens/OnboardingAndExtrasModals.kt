package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import com.example.R
import com.example.data.AccountEntity
import com.example.data.AccountType
import com.example.data.BackupRestoreResult
import com.example.data.CategoryEntity
import com.example.data.CategoryType
import com.example.data.OnboardingAccountChoice
import com.example.data.PeriodMode
import com.example.data.PinUnlockResult
import com.example.domain.CopFormatter
import com.example.ui.MiPlataUiState
import com.example.ui.NotificationHelper
import com.example.ui.components.CategoryOrAccountBadge
import com.example.ui.components.CopThousandSeparatorTransformation
import com.example.ui.components.IconCatalog
import com.example.ui.components.sanitizeCopDigitsInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class OnboardingPresetOption(
    val key: String,
    val label: String,
    val colorHex: Long,
    val iconName: String,
    val defaultChecked: Boolean = false
)

/**
 * ONBOARDING EN 3 PASOS REALES:
 * - Paso 1 — "¿Dónde tienes tu plata?": casillas con Efectivo (marcada por defecto), Nequi, Daviplata,
 *   Bancolombia, Nu, Davivienda, "Otra…" y campo de saldo actual para las marcadas.
 * - Paso 2 — "¿Cada cuánto te pagan?": Mensual, Quincenal o Día de corte personalizado, e ingreso aproximado (opcional).
 * - Paso 3 — "¿Tienes un pago fijo?": nombre, monto, día del mes y de qué cuenta sale (opcional).
 * Crea solo las cuentas elegidas; no crea ninguna tarjeta de crédito ni cuenta de ahorros por su cuenta.
 */
@Composable
fun OnboardingThreeStepScreen(
    onCompleteOnboarding: (
        selectedAccounts: List<OnboardingAccountChoice>,
        periodMode: PeriodMode,
        cutoffDay: Int,
        baseIncome: Long,
        obligationName: String,
        obligationAmount: Long,
        obligationDay: Int,
        obligationAccountName: String?
    ) -> Unit,
    onLoadDemoDirect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val copVisualTransformation = remember { CopThousandSeparatorTransformation("$") }
    var currentStep by rememberSaveable { mutableIntStateOf(1) }

    val presets = remember {
        listOf(
            OnboardingPresetOption("efectivo", "Efectivo", 0xFF10B981, "payments", defaultChecked = true),
            OnboardingPresetOption("nequi", "Nequi", 0xFFEC4899, "smartphone"),
            OnboardingPresetOption("daviplata", "Daviplata", 0xFFEF4444, "smartphone"),
            OnboardingPresetOption("bancolombia", "Bancolombia", 0xFFEAB308, "account_balance"),
            OnboardingPresetOption("nu", "Nu", 0xFF8B5CF6, "account_balance"),
            OnboardingPresetOption("davivienda", "Davivienda", 0xFFDC2626, "account_balance")
        )
    }

    var checkedKeys by rememberSaveable { mutableStateOf(setOf("efectivo")) }
    var balancesByKey by rememberSaveable { mutableStateOf(mapOf("efectivo" to "0")) }
    var otherChecked by rememberSaveable { mutableStateOf(false) }
    var otherCustomName by rememberSaveable { mutableStateOf("") }
    var otherBalanceStr by rememberSaveable { mutableStateOf("0") }
    var step1Error by remember { mutableStateOf<String?>(null) }

    // Paso 2
    var selectedPeriodMode by rememberSaveable { mutableStateOf(PeriodMode.MENSUAL) }
    var cutoffDayStr by rememberSaveable { mutableStateOf("1") }
    var baseIncomeStr by rememberSaveable { mutableStateOf("") }

    // Paso 3 (opcional)
    var obName by rememberSaveable { mutableStateOf("") }
    var obAmountStr by rememberSaveable { mutableStateOf("") }
    var obDayStr by rememberSaveable { mutableStateOf("5") }
    var selectedObAccountName by rememberSaveable { mutableStateOf<String?>(null) }

    val currentSelectedChoices: List<OnboardingAccountChoice> = remember(
        checkedKeys,
        balancesByKey,
        otherChecked,
        otherCustomName,
        otherBalanceStr
    ) {
        buildList {
            for (preset in presets) {
                if (preset.key in checkedKeys) {
                    val bal = balancesByKey[preset.key]?.toLongOrNull() ?: 0L
                    add(
                        OnboardingAccountChoice(
                            name = preset.label,
                            initialBalance = bal,
                            colorHex = preset.colorHex,
                            iconName = preset.iconName
                        )
                    )
                }
            }
            if (otherChecked && otherCustomName.trim().isNotEmpty()) {
                add(
                    OnboardingAccountChoice(
                        name = otherCustomName.trim(),
                        initialBalance = otherBalanceStr.toLongOrNull() ?: 0L,
                        colorHex = 0xFF38BDF8,
                        iconName = "wallet"
                    )
                )
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_finance_1794276865197),
                contentDescription = "Bienvenido a MiPlata",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )

            Text(
                text = "Bienvenido a MiPlata",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Paso $currentStep de 3 • Configura tus cuentas reales en segundos",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LinearProgressIndicator(
                progress = { currentStep / 3f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (currentStep) {
                        1 -> {
                            Text(
                                text = "1. ¿Dónde tienes tu plata?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Marca solo las cuentas que usas hoy e ingresa su saldo actual. No crearemos cuentas ni tarjetas que no elijas:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            presets.forEach { preset ->
                                val isChecked = preset.key in checkedKeys
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                step1Error = null
                                                checkedKeys = if (isChecked) {
                                                    checkedKeys - preset.key
                                                } else {
                                                    checkedKeys + preset.key
                                                }
                                            }
                                            .testTag("onboarding_check_${preset.key}")
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                step1Error = null
                                                checkedKeys = if (checked) {
                                                    checkedKeys + preset.key
                                                } else {
                                                    checkedKeys - preset.key
                                                }
                                            }
                                        )
                                        CategoryOrAccountBadge(
                                            iconName = preset.iconName,
                                            colorHex = preset.colorHex,
                                            size = 32.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = preset.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (isChecked) {
                                        val balText = balancesByKey[preset.key] ?: ""
                                        OutlinedTextField(
                                            value = balText,
                                            onValueChange = { raw ->
                                                balancesByKey = balancesByKey + (preset.key to sanitizeCopDigitsInput(raw))
                                            },
                                            label = { Text("Saldo actual en ${preset.label} (COP)") },
                                            visualTransformation = copVisualTransformation,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp)
                                                .testTag("onboarding_balance_${preset.key}")
                                        )
                                    }
                                }
                            }

                            // Opción "Otra…"
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            step1Error = null
                                            otherChecked = !otherChecked
                                        }
                                        .testTag("onboarding_check_otra")
                                ) {
                                    Checkbox(
                                        checked = otherChecked,
                                        onCheckedChange = {
                                            step1Error = null
                                            otherChecked = it
                                        }
                                    )
                                    Text(
                                        text = "Otra…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (otherChecked) {
                                    OutlinedTextField(
                                        value = otherCustomName,
                                        onValueChange = {
                                            step1Error = null
                                            otherCustomName = it
                                        },
                                        label = { Text("Nombre de la otra cuenta (ej. Lulo Bank, BBVA)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp)
                                            .testTag("onboarding_other_name_input")
                                    )
                                    OutlinedTextField(
                                        value = otherBalanceStr,
                                        onValueChange = { otherBalanceStr = sanitizeCopDigitsInput(it) },
                                        label = { Text("Saldo actual (COP)") },
                                        visualTransformation = copVisualTransformation,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp)
                                            .testTag("onboarding_balance_otra")
                                    )
                                }
                            }

                            if (step1Error != null) {
                                Text(
                                    text = step1Error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        2 -> {
                            Text(
                                text = "2. ¿Cada cuánto te pagan?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Elige cómo quieres calcular tu disponible para gastar:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val onboardingModes = listOf(
                                PeriodMode.MENSUAL to "Mensual",
                                PeriodMode.QUINCENAL to "Quincenal",
                                PeriodMode.PERSONALIZADO to "Día de corte personalizado"
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                onboardingModes.forEach { (mode, label) ->
                                    FilterChip(
                                        selected = selectedPeriodMode == mode,
                                        onClick = { selectedPeriodMode = mode },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            if (selectedPeriodMode == PeriodMode.PERSONALIZADO) {
                                OutlinedTextField(
                                    value = cutoffDayStr,
                                    onValueChange = { cutoffDayStr = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("Día de inicio de tu período (1 al 28)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = baseIncomeStr,
                                onValueChange = { baseIncomeStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Ingreso aproximado del período en COP (opcional)") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("onboarding_income_input")
                            )
                        }

                        else -> {
                            Text(
                                text = "3. ¿Tienes un pago fijo? (opcional)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Si quieres, registra un pago fijo mensual (ej. Arriendo o Internet) para apartarlo de tu disponible. Puedes dejarlo en blanco si prefieres agregarlo después:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = obName,
                                onValueChange = { obName = it },
                                label = { Text("Nombre del pago fijo (ej. Arriendo, Servicios)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("onboarding_fixed_name_input")
                            )
                            OutlinedTextField(
                                value = obAmountStr,
                                onValueChange = { obAmountStr = sanitizeCopDigitsInput(it) },
                                label = { Text("Monto mensual en COP (opcional)") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("onboarding_fixed_amount_input")
                            )
                            OutlinedTextField(
                                value = obDayStr,
                                onValueChange = { obDayStr = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("Día del mes en que se paga (1 al 31)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val effectiveSelectedAccountName = selectedObAccountName
                                ?.takeIf { name -> currentSelectedChoices.any { it.name == name } }
                                ?: currentSelectedChoices.firstOrNull()?.name

                            if (currentSelectedChoices.isNotEmpty()) {
                                Text(
                                    text = "¿De qué cuenta sale este pago?",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    currentSelectedChoices.forEach { choice ->
                                        FilterChip(
                                            selected = effectiveSelectedAccountName == choice.name,
                                            onClick = { selectedObAccountName = choice.name },
                                            label = { Text(choice.name) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (currentStep > 1) {
                            OutlinedButton(onClick = { currentStep-- }) {
                                Text("Atrás")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Button(
                            onClick = {
                                if (currentStep == 1) {
                                    if (otherChecked && otherCustomName.isBlank()) {
                                        step1Error = "Escribe el nombre de la cuenta en 'Otra…' o desmárcala."
                                        return@Button
                                    }
                                    if (currentSelectedChoices.isEmpty()) {
                                        step1Error = "Marca al menos una cuenta donde tengas tu plata."
                                        return@Button
                                    }
                                    step1Error = null
                                    currentStep = 2
                                } else if (currentStep == 2) {
                                    currentStep = 3
                                } else {
                                    val chosenObAccount = selectedObAccountName
                                        ?.takeIf { name -> currentSelectedChoices.any { it.name == name } }
                                        ?: currentSelectedChoices.firstOrNull()?.name
                                    onCompleteOnboarding(
                                        currentSelectedChoices,
                                        selectedPeriodMode,
                                        (cutoffDayStr.toIntOrNull() ?: 1).coerceIn(1, 28),
                                        baseIncomeStr.toLongOrNull() ?: 0L,
                                        obName.trim(),
                                        obAmountStr.toLongOrNull() ?: 0L,
                                        (obDayStr.toIntOrNull() ?: 5).coerceIn(1, 31),
                                        chosenObAccount
                                    )
                                }
                            },
                            modifier = Modifier.testTag("onboarding_next_button")
                        ) {
                            Text(if (currentStep < 3) "Siguiente" else "Empezar en MiPlata")
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onLoadDemoDirect,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_demo_button")
            ) {
                Text("Saltar y Ver Demo con 3 meses de datos en COP")
            }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Pantalla de Bloqueo con PIN (PBKDF2 + backoff exponencial) o BiometricPrompt (BIOMETRIC_STRONG or DEVICE_CREDENTIAL).
 */
@Composable
fun PinAndBiometricLockScreen(
    onVerifyPin: (String) -> PinUnlockResult,
    onQueryRemainingLockoutSeconds: () -> Long,
    onUnlockWithDeviceCredential: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enteredPin by rememberSaveable { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var remainingLockoutSeconds by remember { mutableLongStateOf(onQueryRemainingLockoutSeconds()) }

    LaunchedEffect(remainingLockoutSeconds) {
        if (remainingLockoutSeconds > 0L) {
            delay(1_000L)
            remainingLockoutSeconds = onQueryRemainingLockoutSeconds()
        }
    }

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val isBiometricOrDeviceCredentialAvailable = remember(context) {
        runCatching {
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }
    val fragmentActivity = remember(context) { context.findFragmentActivity() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("pin_lock_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "App bloqueada",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "MiPlata está protegida",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Ingresa tu PIN de 4 a 6 dígitos para desbloquear.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = enteredPin,
                onValueChange = {
                    enteredPin = it.filter { c -> c.isDigit() }.take(6)
                    errorMsg = null
                },
                label = { Text("PIN de seguridad") },
                enabled = remainingLockoutSeconds <= 0L,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.testTag("pin_unlock_input")
            )

            if (remainingLockoutSeconds > 0L) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Demasiados intentos fallidos. Espera ${remainingLockoutSeconds} s para intentar de nuevo.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("pin_lockout_countdown_text")
                )
            } else if (errorMsg != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        when (val res = onVerifyPin(enteredPin)) {
                            is PinUnlockResult.Success -> {
                                errorMsg = null
                            }
                            is PinUnlockResult.InvalidPin -> {
                                errorMsg = "PIN incorrecto (${res.failedAttempts} fallos). Te quedan ${res.remainingAttemptsBeforeLockout} intentos."
                            }
                            is PinUnlockResult.LockedOut -> {
                                remainingLockoutSeconds = res.remainingSeconds
                                errorMsg = "Demasiados intentos. Espera ${res.remainingSeconds} s."
                            }
                        }
                    },
                    enabled = remainingLockoutSeconds <= 0L && enteredPin.length in 4..6,
                    modifier = Modifier.testTag("pin_unlock_button")
                ) {
                    Text("Desbloquear")
                }

                if (isBiometricOrDeviceCredentialAvailable && fragmentActivity != null) {
                    FilledTonalButton(
                        onClick = {
                            val executor = ContextCompat.getMainExecutor(context)
                            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                .setTitle("Desbloquear MiPlata")
                                .setSubtitle("Usa tu huella o bloqueo de pantalla")
                                .setAllowedAuthenticators(authenticators)
                                .build()
                            val biometricPrompt = BiometricPrompt(
                                fragmentActivity,
                                executor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                        super.onAuthenticationSucceeded(result)
                                        onUnlockWithDeviceCredential()
                                    }

                                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                        super.onAuthenticationError(errorCode, errString)
                                        errorMsg = errString.toString()
                                    }

                                    override fun onAuthenticationFailed() {
                                        super.onAuthenticationFailed()
                                        errorMsg = "No se pudo verificar la identidad."
                                    }
                                }
                            )
                            biometricPrompt.authenticate(promptInfo)
                        },
                        modifier = Modifier.testTag("biometric_unlock_button")
                    ) {
                        Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Huella / Sistema")
                    }
                }
            }
        }
    }
}

/**
 * Diálogo de Configuración, Período, Bloqueo PIN, Recordatorio Diario, Exportación CSV y Copia JSON.
 */
@Composable
fun SettingsAndBackupDialog(
    uiState: MiPlataUiState,
    onSavePeriodConfig: (PeriodMode, Int, Long) -> Unit,
    onToggleTheme: (Boolean?) -> Unit,
    onConfigurePin: (enablePin: Boolean, currentPin: String, newPin: String, confirmNewPin: String) -> String?,
    onExportCsv: suspend () -> String,
    onCreateBackupJson: suspend () -> String,
    onRestoreBackupJson: suspend (String) -> BackupRestoreResult,
    onLoadDemo: () -> Unit,
    onClearDemo: () -> Unit,
    onUnarchiveAccount: (AccountEntity) -> Unit = {},
    onSaveDailyReminderConfig: (Boolean, Int) -> Unit = { _, _ -> },
    onToggleAiFeatures: (Boolean) -> Unit = {},
    onStatusMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copVisualTransformation = remember { CopThousandSeparatorTransformation("$") }

    var periodMode by remember { mutableStateOf(uiState.prefs.periodMode) }
    var cutoffDayStr by remember { mutableStateOf(uiState.prefs.customCutoffDay.toString()) }
    var baseIncomeStr by remember { mutableStateOf(uiState.prefs.baseMonthlyIncome.toString()) }

    val isPinCurrentlyActive = uiState.prefs.pinLockEnabled
    var wantsToEnableNewPin by remember { mutableStateOf(false) }
    // Modo cuando ya hay PIN activo: "KEEP" (no tocar), "CHANGE" (cambiar PIN), "DISABLE" (desactivar PIN)
    var existingPinActionMode by remember { mutableStateOf("KEEP") }
    var currentPinInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmNewPinInput by remember { mutableStateOf("") }
    var pinConfigError by remember { mutableStateOf<String?>(null) }

    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    var reminderEnabled by remember(uiState.prefs.dailyReminderEnabled) {
        mutableStateOf(uiState.prefs.dailyReminderEnabled)
    }
    var reminderHour by remember(uiState.prefs.dailyReminderHour) {
        mutableIntStateOf(uiState.prefs.dailyReminderHour.coerceIn(0, 23))
    }
    var hasNotifPermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    var showNotifRationaleDialog by remember { mutableStateOf(false) }
    var pendingEnableReminderAfterPermission by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted || NotificationHelper.hasNotificationPermission(context)
        if (granted) {
            if (pendingEnableReminderAfterPermission) {
                reminderEnabled = true
                onSaveDailyReminderConfig(true, reminderHour)
                onStatusMessage("Recordatorio diario activado a las ${String.format("%02d:00", reminderHour)}.")
            } else {
                onStatusMessage("Permiso de notificaciones concedido.")
            }
        } else {
            if (pendingEnableReminderAfterPermission) {
                reminderEnabled = false
                onSaveDailyReminderConfig(false, reminderHour)
            }
            onStatusMessage("Permiso de notificaciones no concedido; no se enviarán recordatorios.")
        }
        pendingEnableReminderAfterPermission = false
    }

    // Selector para guardar CSV en Dispatchers.IO con manejo de errores
    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val writeResult = runCatching {
                    val csv = onExportCsv()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("No se pudo abrir el archivo de destino.")
                        stream.use { out ->
                            out.write(csv.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                }
                writeResult.fold(
                    onSuccess = {
                        onStatusMessage("Movimientos exportados a CSV correctamente.")
                    },
                    onFailure = { e ->
                        onStatusMessage("Error al guardar CSV: ${e.localizedMessage ?: "fallo de escritura"}.")
                    }
                )
            }
        }
    }

    // Selector para guardar Backup JSON en Dispatchers.IO con manejo de errores
    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val writeResult = runCatching {
                    val json = onCreateBackupJson()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("No se pudo abrir el archivo de destino.")
                        stream.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                }
                writeResult.fold(
                    onSuccess = {
                        onStatusMessage("Copia de seguridad JSON guardada con éxito.")
                    },
                    onFailure = { e ->
                        onStatusMessage("Error al guardar copia JSON: ${e.localizedMessage ?: "fallo de escritura"}.")
                    }
                )
            }
        }
    }

    // Selector para restaurar Backup JSON leyendo en Dispatchers.IO con manejo de errores
    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val readResult = runCatching {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("No se pudo leer el archivo seleccionado.")
                        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                }
                readResult.fold(
                    onSuccess = { content ->
                        when (val restoreResult = onRestoreBackupJson(content)) {
                            is BackupRestoreResult.Success -> {
                                onStatusMessage(restoreResult.message)
                            }
                            is BackupRestoreResult.Error -> {
                                onStatusMessage(restoreResult.reason)
                            }
                        }
                    },
                    onFailure = { e ->
                        onStatusMessage("Error al leer el archivo: ${e.localizedMessage ?: "archivo inaccesible"}.")
                    }
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración, Período y Respaldo") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("1. Período de 'Disponible para gastar'", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PeriodMode.entries.forEach { m ->
                        FilterChip(
                            selected = periodMode == m,
                            onClick = { periodMode = m },
                            label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                if (periodMode == PeriodMode.PERSONALIZADO) {
                    OutlinedTextField(
                        value = cutoffDayStr,
                        onValueChange = { cutoffDayStr = it.filter { c -> c.isDigit() } },
                        label = { Text("Día de pago / corte personalizado (1–28)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = baseIncomeStr,
                    onValueChange = { baseIncomeStr = sanitizeCopDigitsInput(it) },
                    label = { Text("Ingreso base esperado del período (COP)") },
                    visualTransformation = copVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("2. Apariencia (Modo Claro / Oscuro)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = uiState.prefs.darkModeOverride == null,
                        onClick = { onToggleTheme(null) },
                        label = { Text("Sistema") }
                    )
                    FilterChip(
                        selected = uiState.prefs.darkModeOverride == false,
                        onClick = { onToggleTheme(false) },
                        label = { Text("Claro") }
                    )
                    FilterChip(
                        selected = uiState.prefs.darkModeOverride == true,
                        onClick = { onToggleTheme(true) },
                        label = { Text("Oscuro Navy") }
                    )
                }

                Text("3. Bloqueo con PIN o Huella al abrir", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (!isPinCurrentlyActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Crear PIN de seguridad (4 a 6 dígitos)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = wantsToEnableNewPin,
                            onCheckedChange = {
                                wantsToEnableNewPin = it
                                pinConfigError = null
                            }
                        )
                    }
                    if (wantsToEnableNewPin) {
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = {
                                newPinInput = it.filter { c -> c.isDigit() }.take(6)
                                pinConfigError = null
                            },
                            label = { Text("Nuevo PIN (4 a 6 dígitos)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_new_pin_input")
                        )
                        OutlinedTextField(
                            value = confirmNewPinInput,
                            onValueChange = {
                                confirmNewPinInput = it.filter { c -> c.isDigit() }.take(6)
                                pinConfigError = null
                            },
                            label = { Text("Confirmar nuevo PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_confirm_pin_input")
                        )
                    }
                } else {
                    Text(
                        text = "El bloqueo con PIN está activo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = existingPinActionMode == "KEEP",
                            onClick = {
                                existingPinActionMode = "KEEP"
                                pinConfigError = null
                            },
                            label = { Text("Mantener") }
                        )
                        FilterChip(
                            selected = existingPinActionMode == "CHANGE",
                            onClick = {
                                existingPinActionMode = "CHANGE"
                                pinConfigError = null
                            },
                            label = { Text("Cambiar PIN") }
                        )
                        FilterChip(
                            selected = existingPinActionMode == "DISABLE",
                            onClick = {
                                existingPinActionMode = "DISABLE"
                                pinConfigError = null
                            },
                            label = { Text("Desactivar PIN") }
                        )
                    }
                    if (existingPinActionMode == "CHANGE" || existingPinActionMode == "DISABLE") {
                        OutlinedTextField(
                            value = currentPinInput,
                            onValueChange = {
                                currentPinInput = it.filter { c -> c.isDigit() }.take(6)
                                pinConfigError = null
                            },
                            label = { Text("PIN actual") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_current_pin_input")
                        )
                    }
                    if (existingPinActionMode == "CHANGE") {
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = {
                                newPinInput = it.filter { c -> c.isDigit() }.take(6)
                                pinConfigError = null
                            },
                            label = { Text("Nuevo PIN (4 a 6 dígitos)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_new_pin_input")
                        )
                        OutlinedTextField(
                            value = confirmNewPinInput,
                            onValueChange = {
                                confirmNewPinInput = it.filter { c -> c.isDigit() }.take(6)
                                pinConfigError = null
                            },
                            label = { Text("Confirmar nuevo PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_confirm_pin_input")
                        )
                    }
                }
                if (pinConfigError != null) {
                    Text(
                        text = pinConfigError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings_pin_error_text")
                    )
                }

                Text("4. Notificaciones y Recordatorios", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                if (!hasNotifPermission) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_missing_notif_permission_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Permiso de notificaciones desactivado. No se enviará ningún recordatorio hasta que lo concedas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                            FilledTonalButton(
                                onClick = {
                                    pendingEnableReminderAfterPermission = false
                                    showNotifRationaleDialog = true
                                },
                                modifier = Modifier.testTag("settings_grant_notif_permission_btn")
                            ) {
                                Text("Conceder permiso")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recordatorio diario para registrar gastos",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Te recuerda cuánto puedes gastar hoy y si tienes pagos por vencer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { wantEnabled ->
                            if (wantEnabled && !hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pendingEnableReminderAfterPermission = true
                                showNotifRationaleDialog = true
                            } else {
                                reminderEnabled = wantEnabled
                                onSaveDailyReminderConfig(wantEnabled, reminderHour)
                            }
                        },
                        modifier = Modifier.testTag("settings_daily_reminder_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hora del recordatorio diario",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val nextHour = (reminderHour + 23) % 24
                                reminderHour = nextHour
                                onSaveDailyReminderConfig(reminderEnabled, nextHour)
                            },
                            modifier = Modifier.testTag("settings_reminder_hour_prev")
                        ) {
                            Text("-")
                        }
                        Text(
                            text = String.format("%02d:00", reminderHour),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.testTag("settings_reminder_hour_text")
                        )
                        OutlinedButton(
                            onClick = {
                                val nextHour = (reminderHour + 1) % 24
                                reminderHour = nextHour
                                onSaveDailyReminderConfig(reminderEnabled, nextHour)
                            },
                            modifier = Modifier.testTag("settings_reminder_hour_next")
                        ) {
                            Text("+")
                        }
                    }
                }

                if (showNotifRationaleDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showNotifRationaleDialog = false
                            pendingEnableReminderAfterPermission = false
                        },
                        title = { Text("Permiso para recordatorios") },
                        text = {
                            Text(
                                "MiPlata usa las notificaciones únicamente para enviarte tu recordatorio diario a la hora elegida y avisarte un día antes y en la mañana de tus pagos pendientes."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showNotifRationaleDialog = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                                        if (pendingEnableReminderAfterPermission) {
                                            reminderEnabled = true
                                            onSaveDailyReminderConfig(true, reminderHour)
                                        }
                                        pendingEnableReminderAfterPermission = false
                                    }
                                },
                                modifier = Modifier.testTag("confirm_notif_rationale_btn")
                            ) {
                                Text("Continuar")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showNotifRationaleDialog = false
                                    pendingEnableReminderAfterPermission = false
                                }
                            ) {
                                Text("Ahora no")
                            }
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (!hasNotifPermission) {
                            onStatusMessage("Concede primero el permiso de notificaciones para recibir avisos.")
                            return@OutlinedButton
                        }
                        NotificationHelper.sendDailyReminderNotification(
                            context = context,
                            availableTodayCop = uiState.availableToSpend.availablePerDay,
                            hideBalances = uiState.prefs.hideBalances
                        )
                        uiState.upcomingPendingItems.firstOrNull()?.let { pendingItem ->
                            NotificationHelper.sendUpcomingObligationAlert(
                                context = context,
                                obligationName = pendingItem.transaction.note.ifBlank {
                                    pendingItem.category?.name ?: pendingItem.transaction.type.label
                                },
                                daysUntilDue = 1,
                                amountCop = pendingItem.transaction.amount,
                                hideBalances = uiState.prefs.hideBalances
                            )
                        }
                        onStatusMessage("Notificación de recordatorio enviada.")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Probar recordatorio y alerta de pago fijo")
                }

                Text("5. Exportar CSV y Copia de Seguridad JSON", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { createCsvLauncher.launch("mi_plata_movimientos.csv") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CSV")
                    }
                    FilledTonalButton(
                        onClick = { createJsonLauncher.launch("mi_plata_backup.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Backup JSON")
                    }
                }
                OutlinedButton(
                    onClick = { showRestoreConfirmDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("restore_backup_json_button")
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restaurar desde archivo JSON")
                }

                if (showRestoreConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showRestoreConfirmDialog = false },
                        title = { Text("Restaurar copia de seguridad") },
                        text = {
                            Text("Esto reemplazará todos tus datos actuales por los del archivo. No se puede deshacer.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showRestoreConfirmDialog = false
                                    openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                modifier = Modifier.testTag("confirm_restore_backup_button")
                            ) {
                                Text("Reemplazar")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showRestoreConfirmDialog = false },
                                modifier = Modifier.testTag("cancel_restore_backup_button")
                            ) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                Text("6. Datos de Ejemplo (3 meses en COP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val hasRealData = uiState.allTransactions.any { !it.isDemo } ||
                    uiState.accountsWithBalance.any {
                        !it.account.isDemo && (it.account.initialBalance != 0L || it.account.receivedOrPaidAmount != 0L || !it.account.isDefault)
                    } ||
                    uiState.categories.any { it.monthlyLimit > 0L } ||
                    uiState.savingGoals.any { !it.isDemo }
                var showLoadDemoConfirm by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (hasRealData) {
                                showLoadDemoConfirm = true
                            } else {
                                onLoadDemo()
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_load_demo_button")
                    ) {
                        Text("Cargar Demo")
                    }
                    OutlinedButton(
                        onClick = {
                            onClearDemo()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_clear_demo_button")
                    ) {
                        Text("Borrar Demo")
                    }
                }

                if (showLoadDemoConfirm) {
                    AlertDialog(
                        onDismissRequest = { showLoadDemoConfirm = false },
                        title = { Text("Cargar datos de ejemplo") },
                        text = {
                            Text("Se añadirán cuentas y movimientos de ejemplo junto a los tuyos. Podrás borrarlos después sin afectar tus datos.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showLoadDemoConfirm = false
                                    onLoadDemo()
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("confirm_load_demo_button")
                            ) {
                                Text("Cargar demo")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLoadDemoConfirm = false }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                // 7. Cuentas archivadas (desarchivar desde Ajustes)
                Text("7. Cuentas archivadas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (uiState.archivedAccounts.isEmpty()) {
                    Text(
                        text = "No tienes cuentas archivadas en este momento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    uiState.archivedAccounts.forEach { archivedItem ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = archivedItem.account.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${archivedItem.account.type.label} • Saldo: ${CopFormatter.format(archivedItem.currentBalance, uiState.prefs.hideBalances)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { onUnarchiveAccount(archivedItem.account) },
                                    modifier = Modifier.testTag("unarchive_account_${archivedItem.account.id}")
                                ) {
                                    Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Desarchivar")
                                }
                            }
                        }
                    }
                }

                // 8. Funciones de Inteligencia Artificial (Gemini)
                Text("8. Funciones de Inteligencia Artificial (Gemini)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activar funciones de IA con Gemini",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Asistente financiero, análisis, dictado de gastos, lectura de recibos y Auto-Cat. Si lo desactivas, no se hará ninguna llamada a Gemini.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.prefs.aiFeaturesEnabled,
                        onCheckedChange = { enabled ->
                            onToggleAiFeatures(enabled)
                        },
                        modifier = Modifier.testTag("settings_ai_features_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pinError = when {
                        !isPinCurrentlyActive && wantsToEnableNewPin ->
                            onConfigurePin(true, "", newPinInput, confirmNewPinInput)
                        isPinCurrentlyActive && existingPinActionMode == "CHANGE" ->
                            onConfigurePin(true, currentPinInput, newPinInput, confirmNewPinInput)
                        isPinCurrentlyActive && existingPinActionMode == "DISABLE" ->
                            onConfigurePin(false, currentPinInput, "", "")
                        else -> null
                    }
                    if (pinError != null) {
                        pinConfigError = pinError
                        return@Button
                    }
                    onSavePeriodConfig(
                        periodMode,
                        (cutoffDayStr.toIntOrNull() ?: 1).coerceIn(1, 28),
                        baseIncomeStr.toLongOrNull() ?: 0L
                    )
                    onDismiss()
                }
            ) {
                Text("Guardar cambios")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

/**
 * Pantalla breve informativa de privacidad mostrada la primera vez que se usa cualquier función de IA.
 */
@Composable
fun AiPrivacyConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Text(
                text = "Qué se envía a Gemini",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_privacy_consent_dialog"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Cuando usas una función de inteligencia artificial en MiPlata, se envía a Gemini únicamente:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Un resumen de saldos, gastos por categoría, próximos pagos y metas de ahorro.\n" +
                        "• El texto, la nota o la foto de recibo que tú elijas procesar.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Nunca se envía tu historial completo de movimientos, ni los últimos 4 dígitos de tus tarjetas de crédito, ni los nombres de personas en cuentas de tipo «Prestado». Puedes desactivar todas las funciones de IA en cualquier momento desde Ajustes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("ai_privacy_consent_accept_button")
            ) {
                Text("Aceptar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.testTag("ai_privacy_consent_decline_button")
            ) {
                Text("Ahora no")
            }
        }
    )
}

/**
 * Diálogo completo para crear una cuenta en cualquiera de los 5 tipos:
 * Débito, Ahorros, Tarjeta de crédito (cupo, día de corte, día de pago, últimos 4 dígitos),
 * Préstamo (lo que debo) o Prestado (lo que me deben).
 * Todos los campos de monto formatean con puntos de miles ($1.250.000) mientras se escribe.
 */
@Composable
fun AddAccountDialog(
    initialType: AccountType = AccountType.DEBITO,
    onSaveAccountFull: (
        name: String,
        type: AccountType,
        initialBalance: Long,
        colorHex: Long,
        iconName: String,
        creditLimit: Long,
        billingCutoffDay: Int,
        paymentDueDay: Int,
        lastFourDigits: String,
        personName: String,
        originalAmount: Long,
        receivedOrPaidAmount: Long,
        monthlyInstallment: Long
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val copVisualTransformation = remember { CopThousandSeparatorTransformation("$") }
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(initialType) }
    var initialBalanceText by remember { mutableStateOf("0") }
    var selectedColor by remember { mutableLongStateOf(0xFF38BDF8L) }

    // Campos de Tarjeta de crédito
    var creditLimitText by remember { mutableStateOf("4000000") }
    var cutoffDayText by remember { mutableStateOf("15") }
    var paymentDueDayText by remember { mutableStateOf("5") }
    var lastFourDigitsText by remember { mutableStateOf("4821") }

    // Campos de Préstamo / Prestado
    var personNameText by remember { mutableStateOf("") }
    var originalAmountText by remember { mutableStateOf("1000000") }
    var receivedOrPaidText by remember { mutableStateOf("0") }
    var monthlyInstallmentText by remember { mutableStateOf("150000") }

    val mainTypes = listOf(
        AccountType.DEBITO,
        AccountType.AHORROS,
        AccountType.TARJETA_CREDITO,
        AccountType.PRESTAMO,
        AccountType.PRESTADO
    )

    val colorChoices = listOf(
        0xFF38BDF8L to "Azul Cielo",
        0xFF10B981L to "Esmeralda",
        0xFF8B5CF6L to "Morado Nu",
        0xFFEC4899L to "Rosa Nequi",
        0xFFF59E0BL to "Ámbar"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar ${selectedType.label}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Tipo de cuenta:", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    mainTypes.chunked(3).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowTypes.forEach { t ->
                                FilterChip(
                                    selected = selectedType == t,
                                    onClick = { selectedType = t },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = {
                        Text(
                            when (selectedType) {
                                AccountType.DEBITO -> "Nombre (ej. Efectivo, Bancolombia, Nequi)"
                                AccountType.AHORROS -> "Nombre (ej. Cajita Nu, Bolsillo Ahorro)"
                                AccountType.TARJETA_CREDITO -> "Nombre de la tarjeta (ej. Nu Morada, Visa)"
                                AccountType.PRESTAMO -> "Nombre del préstamo (ej. Crédito Libre Inversión)"
                                AccountType.PRESTADO -> "Concepto (ej. Préstamo a Andrés)"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                when (selectedType) {
                    AccountType.TARJETA_CREDITO -> {
                        OutlinedTextField(
                            value = creditLimitText,
                            onValueChange = { creditLimitText = sanitizeCopDigitsInput(it) },
                            label = { Text("Cupo total en COP") },
                            visualTransformation = copVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = cutoffDayText,
                                onValueChange = { cutoffDayText = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("Día de corte (1-28)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = paymentDueDayText,
                                onValueChange = { paymentDueDayText = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("Día de pago (1-28)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = lastFourDigitsText,
                                onValueChange = { lastFourDigitsText = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("Últimos 4 dígitos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = initialBalanceText,
                                onValueChange = { initialBalanceText = sanitizeCopDigitsInput(it) },
                                label = { Text("Deuda actual (COP)") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    AccountType.PRESTAMO, AccountType.PRESTADO -> {
                        OutlinedTextField(
                            value = personNameText,
                            onValueChange = { personNameText = it },
                            label = {
                                Text(
                                    if (selectedType == AccountType.PRESTAMO) {
                                        "Entidad o persona a quien debes"
                                    } else {
                                        "Persona que te debe"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = originalAmountText,
                            onValueChange = { originalAmountText = sanitizeCopDigitsInput(it) },
                            label = { Text("Monto original en COP") },
                            visualTransformation = copVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = receivedOrPaidText,
                            onValueChange = { receivedOrPaidText = sanitizeCopDigitsInput(it) },
                            label = {
                                Text(
                                    if (selectedType == AccountType.PRESTAMO) {
                                        "Monto ya pagado antes de usar la app (COP)"
                                    } else {
                                        "Monto ya recibido antes de usar la app (COP)"
                                    }
                                )
                            },
                            visualTransformation = copVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (selectedType == AccountType.PRESTAMO) {
                            OutlinedTextField(
                                value = monthlyInstallmentText,
                                onValueChange = { monthlyInstallmentText = sanitizeCopDigitsInput(it) },
                                label = { Text("Cuota mensual sugerida (COP)") },
                                visualTransformation = copVisualTransformation,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = initialBalanceText,
                            onValueChange = { initialBalanceText = sanitizeCopDigitsInput(it) },
                            label = { Text("Saldo inicial en COP") },
                            visualTransformation = copVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text("Color identificador:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colorChoices.take(3).forEach { (hex, label) ->
                        FilterChip(
                            selected = selectedColor == hex,
                            onClick = { selectedColor = hex },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val icon = when (selectedType) {
                            AccountType.DEBITO -> "account_balance"
                            AccountType.AHORROS -> "savings"
                            AccountType.TARJETA_CREDITO -> "credit_card"
                            AccountType.PRESTAMO -> "credit_card"
                            AccountType.PRESTADO -> "payments"
                        }
                        val origAmt = originalAmountText.toLongOrNull() ?: 0L
                        val recOrPaid = receivedOrPaidText.toLongOrNull() ?: 0L
                        val computedInitialBal = when (selectedType) {
                            AccountType.PRESTAMO, AccountType.PRESTADO -> (origAmt - recOrPaid).coerceAtLeast(0L)
                            else -> initialBalanceText.toLongOrNull() ?: 0L
                        }
                        onSaveAccountFull(
                            name,
                            selectedType,
                            computedInitialBal,
                            selectedColor,
                            icon,
                            creditLimitText.toLongOrNull() ?: 0L,
                            (cutoffDayText.toIntOrNull() ?: 15).coerceIn(1, 28),
                            (paymentDueDayText.toIntOrNull() ?: 5).coerceIn(1, 28),
                            lastFourDigitsText,
                            personNameText,
                            origAmt,
                            recOrPaid,
                            monthlyInstallmentText.toLongOrNull() ?: 0L
                        )
                        onDismiss()
                    }
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/**
 * Pantalla / Modal de "Categorías" accesible desde el menú superior:
 * - Crear nueva categoría (Gasto o Ingreso), renombrar, cambiar ícono, color y grupo.
 * - No permite dos categorías con el mismo nombre dentro del mismo tipo.
 * - Al eliminar una categoría con movimientos, pide elegir a qué categoría existente pasan esos movimientos.
 */
@Composable
fun CategoriesManagementModal(
    uiState: MiPlataUiState,
    onSaveOrUpdateCategory: suspend (
        id: Long,
        name: String,
        type: CategoryType,
        iconName: String,
        colorHex: Long,
        groupName: String,
        monthlyLimit: Long
    ) -> String?,
    onDeleteCategoryWithReassignment: suspend (
        category: CategoryEntity,
        reassignToCategoryId: Long?
    ) -> String?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val copVisualTransformation = remember { CopThousandSeparatorTransformation("$") }
    var selectedTypeFilter by rememberSaveable { mutableStateOf(CategoryType.GASTO) }
    var isEditingOrCreating by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    // Estado del formulario de creación/edición
    var formName by remember { mutableStateOf("") }
    var formType by remember { mutableStateOf(CategoryType.GASTO) }
    var formIcon by remember { mutableStateOf("restaurant") }
    var formColor by remember { mutableLongStateOf(0xFF10B981L) }
    var formGroup by remember { mutableStateOf("Estilo de vida") }
    var formLimitStr by remember { mutableStateOf("0") }
    var formError by remember { mutableStateOf<String?>(null) }

    // Estado para eliminar categoría con reasignación de movimientos
    var categoryPendingDeletion by remember { mutableStateOf<CategoryEntity?>(null) }
    var targetReassignCategoryId by remember { mutableLongStateOf(0L) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val filteredCategories = remember(uiState.categories, selectedTypeFilter) {
        uiState.categories.filter { it.type == selectedTypeFilter }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEditingOrCreating) {
                        if (editingCategory != null) "Editar categoría" else "Nueva categoría"
                    } else {
                        "Categorías"
                    },
                    fontWeight = FontWeight.Bold
                )
                if (!isEditingOrCreating) {
                    FilledTonalButton(
                        onClick = {
                            editingCategory = null
                            formName = ""
                            formType = selectedTypeFilter
                            formIcon = if (selectedTypeFilter == CategoryType.INGRESO) "work" else "restaurant"
                            formColor = 0xFF10B981L
                            formGroup = if (selectedTypeFilter == CategoryType.INGRESO) "Ingresos" else "Estilo de vida"
                            formLimitStr = "0"
                            formError = null
                            isEditingOrCreating = true
                        },
                        modifier = Modifier.testTag("add_category_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Crear")
                    }
                }
            }
        },
        text = {
            if (isEditingOrCreating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (editingCategory == null) {
                        Text("Tipo de categoría:", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CategoryType.entries.forEach { t ->
                                FilterChip(
                                    selected = formType == t,
                                    onClick = {
                                        formType = t
                                        formGroup = if (t == CategoryType.INGRESO) "Ingresos" else "Estilo de vida"
                                        formError = null
                                    },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = formName,
                        onValueChange = {
                            formName = it
                            formError = null
                        },
                        label = { Text("Nombre de la categoría") },
                        isError = formError != null,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("category_name_input")
                    )

                    Text("Grupo:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconCatalog.defaultCategoryGroups.forEach { grp ->
                            FilterChip(
                                selected = formGroup.equals(grp, ignoreCase = true),
                                onClick = { formGroup = grp },
                                label = { Text(grp) }
                            )
                        }
                    }

                    Text("Ícono:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconCatalog.availableIcons.forEach { (iconKey, iconLabel) ->
                            FilterChip(
                                selected = formIcon == iconKey,
                                onClick = { formIcon = iconKey },
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
                            val isSelected = formColor == hex
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
                                    .clickable { formColor = hex }
                            )
                        }
                    }

                    if (formType == CategoryType.GASTO) {
                        OutlinedTextField(
                            value = formLimitStr,
                            onValueChange = { formLimitStr = sanitizeCopDigitsInput(it) },
                            label = { Text("Límite mensual de presupuesto en COP (0 = Sin límite)") },
                            visualTransformation = copVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (formError != null) {
                        Text(
                            text = formError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("category_form_error_text")
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryType.entries.forEach { t ->
                            FilterChip(
                                selected = selectedTypeFilter == t,
                                onClick = { selectedTypeFilter = t },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag("categories_tab_${t.name.lowercase()}")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredCategories, key = { it.id }) { cat ->
                            val movCount = uiState.categoryMovementCounts[cat.id] ?: 0
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("category_row_${cat.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        CategoryOrAccountBadge(
                                            iconName = cat.iconName,
                                            colorHex = cat.colorHex,
                                            size = 36.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = cat.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${cat.groupName} • $movCount movimientos",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                editingCategory = cat
                                                formName = cat.name
                                                formType = cat.type
                                                formIcon = cat.iconName
                                                formColor = cat.colorHex
                                                formGroup = cat.groupName
                                                formLimitStr = cat.monthlyLimit.toString()
                                                formError = null
                                                isEditingOrCreating = true
                                            },
                                            modifier = Modifier.testTag("edit_category_${cat.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Editar ${cat.name}",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (cat.systemKey.isNullOrBlank()) {
                                            IconButton(
                                                onClick = {
                                                    val replacementCandidates = uiState.categories.filter {
                                                        it.type == cat.type && it.id != cat.id
                                                    }
                                                    targetReassignCategoryId = replacementCandidates.firstOrNull()?.id ?: 0L
                                                    deleteError = null
                                                    categoryPendingDeletion = cat
                                                },
                                                modifier = Modifier.testTag("delete_category_${cat.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Eliminar ${cat.name}",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
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
        },
        confirmButton = {
            if (isEditingOrCreating) {
                Button(
                    onClick = {
                        scope.launch {
                            val err = onSaveOrUpdateCategory(
                                editingCategory?.id ?: 0L,
                                formName,
                                formType,
                                formIcon,
                                formColor,
                                formGroup,
                                formLimitStr.toLongOrNull() ?: 0L
                            )
                            if (err != null) {
                                formError = err
                            } else {
                                isEditingOrCreating = false
                                editingCategory = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_category_button")
                ) {
                    Text("Guardar")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        },
        dismissButton = {
            if (isEditingOrCreating) {
                TextButton(
                    onClick = {
                        isEditingOrCreating = false
                        editingCategory = null
                        formError = null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        }
    )

    // Diálogo para eliminar categoría (y elegir a qué categoría pasan sus movimientos si tiene alguno)
    categoryPendingDeletion?.let { catToDelete ->
        val movCount = uiState.categoryMovementCounts[catToDelete.id] ?: 0
        val replacementOptions = uiState.categories.filter {
            it.type == catToDelete.type && it.id != catToDelete.id
        }

        AlertDialog(
            onDismissRequest = { categoryPendingDeletion = null },
            title = { Text("Eliminar categoría \"${catToDelete.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (movCount > 0) {
                        Text(
                            text = "Esta categoría tiene $movCount movimientos registrados. Elige a qué categoría existente pasarán esos movimientos para no perder tu historial ni alterar el total gastado del mes:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            replacementOptions.forEach { candidate ->
                                FilterChip(
                                    selected = targetReassignCategoryId == candidate.id,
                                    onClick = { targetReassignCategoryId = candidate.id },
                                    label = { Text(candidate.name) },
                                    modifier = Modifier.testTag("reassign_target_${candidate.id}")
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "¿Seguro que deseas eliminar la categoría \"${catToDelete.name}\"? No tiene movimientos asociados.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (deleteError != null) {
                        Text(
                            text = deleteError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val err = onDeleteCategoryWithReassignment(
                                catToDelete,
                                if (movCount > 0) targetReassignCategoryId else null
                            )
                            if (err != null) {
                                deleteError = err
                            } else {
                                categoryPendingDeletion = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete_category_button")
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryPendingDeletion = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}


