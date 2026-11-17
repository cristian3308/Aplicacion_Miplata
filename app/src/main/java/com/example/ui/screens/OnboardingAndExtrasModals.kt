package com.example.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.R
import com.example.data.AccountType
import com.example.data.PeriodMode
import com.example.domain.CopFormatter
import com.example.ui.MiPlataUiState
import com.example.ui.NotificationHelper
import kotlinx.coroutines.launch

/**
 * ONBOARDING DE 3 PASOS (Primera vez):
 * Paso 1: Crear/confirmar cuentas (Efectivo, Nu, Nequi) con su saldo inicial.
 * Paso 2: Definir ingreso estimado y modo de período (Mensual, Quincenal o Fecha personalizada).
 * Paso 3: Agregar obligación fija principal (ej. Arriendo).
 * Incluye botón directo "Ver demo de 3 meses" para explorar la app de inmediato.
 */
@Composable
fun OnboardingThreeStepScreen(
    onCompleteOnboarding: (
        efectivoInit: Long,
        nuInit: Long,
        nequiInit: Long,
        periodMode: PeriodMode,
        cutoffDay: Int,
        baseIncome: Long,
        obligationName: String,
        obligationAmount: Long,
        obligationDay: Int
    ) -> Unit,
    onLoadDemoDirect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by rememberSaveable { mutableIntStateOf(1) }

    var efectivoStr by rememberSaveable { mutableStateOf("150000") }
    var nuStr by rememberSaveable { mutableStateOf("1200000") }
    var nequiStr by rememberSaveable { mutableStateOf("350000") }

    var selectedPeriodMode by rememberSaveable { mutableStateOf(PeriodMode.MENSUAL) }
    var cutoffDayStr by rememberSaveable { mutableStateOf("1") }
    var baseIncomeStr by rememberSaveable { mutableStateOf("3500000") }

    var obName by rememberSaveable { mutableStateOf("Arriendo") }
    var obAmountStr by rememberSaveable { mutableStateOf("1100000") }
    var obDayStr by rememberSaveable { mutableStateOf("5") }

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
                    .height(145.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )

            Text(
                text = "Bienvenido a MiPlata",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Paso $currentStep de 3 • Configura tu disponible diario en segundos",
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
                                text = "1. Tus cuentas y saldos iniciales (COP)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Creamos por defecto Efectivo, Nu y Nequi. Ajusta tu saldo actual en cada una:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = efectivoStr,
                                onValueChange = { efectivoStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Saldo inicial en Efectivo (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = nuStr,
                                onValueChange = { nuStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Saldo inicial en Nu (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = nequiStr,
                                onValueChange = { nequiStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Saldo inicial en Nequi (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        2 -> {
                            Text(
                                text = "2. Tu ingreso y período de corte",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Si tus ingresos llegan en efectivo o en fechas irregulares, elige cómo calcular tu disponible:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PeriodMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = selectedPeriodMode == mode,
                                        onClick = { selectedPeriodMode = mode },
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                            if (selectedPeriodMode == PeriodMode.PERSONALIZADO) {
                                OutlinedTextField(
                                    value = cutoffDayStr,
                                    onValueChange = { cutoffDayStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Día de inicio de tu período (1 al 28)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = baseIncomeStr,
                                onValueChange = { baseIncomeStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Ingreso estimado del período (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        else -> {
                            Text(
                                text = "3. Tu obligación fija principal",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MiPlata separará tus obligaciones fijas antes de decirte cuánto puedes gastar hoy:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = obName,
                                onValueChange = { obName = it },
                                label = { Text("Nombre (ej. Arriendo, Servicios)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = obAmountStr,
                                onValueChange = { obAmountStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Valor mensual en COP") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = obDayStr,
                                onValueChange = { obDayStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Día de pago del mes (1 al 31)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                if (currentStep < 3) {
                                    currentStep++
                                } else {
                                    onCompleteOnboarding(
                                        efectivoStr.toLongOrNull() ?: 0L,
                                        nuStr.toLongOrNull() ?: 0L,
                                        nequiStr.toLongOrNull() ?: 0L,
                                        selectedPeriodMode,
                                        (cutoffDayStr.toIntOrNull() ?: 1).coerceIn(1, 28),
                                        baseIncomeStr.toLongOrNull() ?: 0L,
                                        obName,
                                        obAmountStr.toLongOrNull() ?: 0L,
                                        (obDayStr.toIntOrNull() ?: 5).coerceIn(1, 31)
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

/**
 * Pantalla de Bloqueo con PIN o Huella / Credencial del dispositivo al abrir la app.
 */
@Composable
fun PinAndBiometricLockScreen(
    onUnlockWithPin: (String) -> Boolean,
    onUnlockWithDeviceCredential: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enteredPin by rememberSaveable { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val deviceCredLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            onUnlockWithDeviceCredential()
        }
    }

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
                text = "Ingresa tu PIN de 4 dígitos o usa tu huella/bloqueo del teléfono.",
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
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!onUnlockWithPin(enteredPin)) {
                            errorMsg = "PIN incorrecto. Intenta de nuevo."
                        }
                    }
                ) {
                    Text("Desbloquear")
                }

                FilledTonalButton(
                    onClick = {
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        @Suppress("DEPRECATION")
                        val intent = km?.createConfirmDeviceCredentialIntent(
                            "Desbloquear MiPlata",
                            "Usa tu huella o desbloqueo de pantalla"
                        )
                        if (intent != null) {
                            deviceCredLauncher.launch(intent)
                        } else {
                            onUnlockWithDeviceCredential()
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Huella / Sistema")
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
    onConfigurePin: (Boolean, String) -> Unit,
    onExportCsv: suspend () -> String,
    onCreateBackupJson: suspend () -> String,
    onRestoreBackupJson: suspend (String) -> Boolean,
    onLoadDemo: () -> Unit,
    onClearDemo: () -> Unit,
    onStatusMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var periodMode by remember { mutableStateOf(uiState.prefs.periodMode) }
    var cutoffDayStr by remember { mutableStateOf(uiState.prefs.customCutoffDay.toString()) }
    var baseIncomeStr by remember { mutableStateOf(uiState.prefs.baseMonthlyIncome.toString()) }
    var pinEnabled by remember { mutableStateOf(uiState.prefs.pinLockEnabled) }
    var pinCodeStr by remember { mutableStateOf(uiState.prefs.pinCode.ifBlank { "1234" }) }

    // Selector para guardar CSV
    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val csv = onExportCsv()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                onStatusMessage("Movimientos exportados a CSV correctamente.")
            }
        }
    }

    // Selector para guardar Backup JSON
    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val json = onCreateBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                onStatusMessage("Copia de seguridad JSON guardada con éxito.")
            }
        }
    }

    // Selector para restaurar Backup JSON
    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!content.isNullOrBlank()) {
                    val ok = onRestoreBackupJson(content)
                    onStatusMessage(
                        if (ok) "Copia de seguridad JSON restaurada." else "El archivo JSON no tiene un formato válido."
                    )
                }
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
                    onValueChange = { baseIncomeStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Ingreso base esperado del período (COP)") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Solicitar PIN / Huella al iniciar", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = pinEnabled, onCheckedChange = { pinEnabled = it })
                }
                if (pinEnabled) {
                    OutlinedTextField(
                        value = pinCodeStr,
                        onValueChange = { pinCodeStr = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("PIN numérico (4 a 6 dígitos)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("4. Notificaciones y Recordatorios", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = {
                        NotificationHelper.sendDailyReminderNotification(
                            context = context,
                            availableTodayCop = uiState.availableToSpend.availablePerDay
                        )
                        uiState.upcomingObligations7Days.firstOrNull()?.let { ob ->
                            NotificationHelper.sendUpcomingObligationAlert(
                                context = context,
                                obligationName = ob.name,
                                daysUntilDue = 2,
                                amountCop = ob.amount
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
                    onClick = { openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restaurar desde archivo JSON")
                }

                Text("6. Datos de Ejemplo (3 meses en COP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onLoadDemo()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cargar Demo")
                    }
                    OutlinedButton(
                        onClick = {
                            onClearDemo()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Borrar Demo")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSavePeriodConfig(
                        periodMode,
                        (cutoffDayStr.toIntOrNull() ?: 1).coerceIn(1, 28),
                        baseIncomeStr.toLongOrNull() ?: 0L
                    )
                    onConfigurePin(pinEnabled, pinCodeStr)
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
 * Diálogo completo para crear una cuenta en cualquiera de los 5 tipos:
 * Débito, Ahorros, Tarjeta de crédito (cupo, día de corte, día de pago, últimos 4 dígitos),
 * Préstamo (lo que debo) o Prestado (lo que me deben).
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
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(initialType.normalized()) }
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
                                AccountType.DEBITO -> "Nombre (ej. Efectivo, Nu Débito, Nequi)"
                                AccountType.AHORROS -> "Nombre (ej. Cajita Nu, Bolsillo Ahorro)"
                                AccountType.TARJETA_CREDITO -> "Nombre de la tarjeta (ej. Nu Morada, Visa)"
                                AccountType.PRESTAMO -> "Nombre del préstamo (ej. Crédito Libre Inversión)"
                                AccountType.PRESTADO -> "Concepto (ej. Préstamo a Andrés)"
                                else -> "Nombre"
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
                            onValueChange = { creditLimitText = it.filter { c -> c.isDigit() } },
                            label = { Text("Cupo total en COP") },
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
                                onValueChange = { initialBalanceText = it.filter { c -> c.isDigit() } },
                                label = { Text("Deuda actual (COP)") },
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
                            onValueChange = { originalAmountText = it.filter { c -> c.isDigit() } },
                            label = { Text("Monto original en COP") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = receivedOrPaidText,
                            onValueChange = { receivedOrPaidText = it.filter { c -> c.isDigit() } },
                            label = {
                                Text(
                                    if (selectedType == AccountType.PRESTAMO) {
                                        "Monto ya pagado (COP)"
                                    } else {
                                        "Monto ya recibido (COP)"
                                    }
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (selectedType == AccountType.PRESTAMO) {
                            OutlinedTextField(
                                value = monthlyInstallmentText,
                                onValueChange = { monthlyInstallmentText = it.filter { c -> c.isDigit() } },
                                label = { Text("Cuota mensual sugerida (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = initialBalanceText,
                            onValueChange = { initialBalanceText = it.filter { c -> c.isDigit() } },
                            label = { Text("Saldo inicial en COP") },
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
                            AccountType.PRESTAMO -> "Description"
                            AccountType.PRESTADO -> "payments"
                            else -> "account_balance"
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

