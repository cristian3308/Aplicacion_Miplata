package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AccountType
import com.example.data.TransactionEntity
import com.example.data.TransactionType
import com.example.data.configureAppCheck
import com.example.ui.MiPlataViewModel
import com.example.ui.screens.AccountsScreen
import com.example.ui.screens.AddAccountDialog
import com.example.ui.screens.AssistantScreen
import com.example.ui.screens.BudgetScreen
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.GoalsDialogModal
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MovementsScreen
import com.example.ui.screens.OnboardingThreeStepScreen
import com.example.ui.screens.PinAndBiometricLockScreen
import com.example.ui.screens.QuickRegisterSheet
import com.example.ui.screens.SettingsAndBackupDialog
import com.example.ui.theme.MiPlataTheme
import com.example.ui.widget.MiPlataWidgetProvider

/**
 * Definición de las 5 pestañas de la barra inferior flotante solo con ícono:
 * Inicio, Calendario, Presupuesto, Cuentas, Historial.
 */
enum class MiPlataTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    INICIO("Inicio", Icons.Filled.Home, Icons.Outlined.Home, "nav_tab_home"),
    CALENDARIO("Calendario", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, "nav_tab_calendar"),
    PRESUPUESTO("Presupuesto", Icons.Filled.PieChart, Icons.Outlined.PieChart, "nav_tab_budget"),
    CUENTAS("Cuentas", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet, "nav_tab_accounts"),
    HISTORIAL("Historial", Icons.Filled.History, Icons.Outlined.History, "nav_tab_history")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PASO 1 OBLIGATORIO: Configurar Firebase App Check con el intent de lanzamiento
        // antes de inicializar cualquier UI o ViewModel de Gemini.
        configureAppCheck(this, intent)

        val openQuickAddFromWidget = intent?.getBooleanExtra(
            MiPlataWidgetProvider.EXTRA_OPEN_QUICK_REGISTER,
            false
        ) == true

        enableEdgeToEdge()
        setContent {
            val vm: MiPlataViewModel = viewModel()
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            // Por defecto modo oscuro Navy si el usuario no lo ha forzado a claro
            val useDarkTheme = uiState.prefs.darkModeOverride ?: true

            MiPlataTheme(darkTheme = useDarkTheme) {
                MiPlataAppRoot(
                    viewModel = vm,
                    useDarkTheme = useDarkTheme,
                    openQuickRegisterInitially = openQuickAddFromWidget
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiPlataAppRoot(
    viewModel: MiPlataViewModel,
    useDarkTheme: Boolean = true,
    openQuickRegisterInitially: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val isGeneratingReport by viewModel.isGeneratingReport.collectAsStateWithLifecycle()
    val aiReport by viewModel.aiReport.collectAsStateWithLifecycle()
    val aiReportError by viewModel.aiReportError.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isChatLoading by viewModel.isChatLoading.collectAsStateWithLifecycle()
    val isParsingAiDraft by viewModel.isParsingAiDraft.collectAsStateWithLifecycle()
    val parsedDraft by viewModel.parsedDraft.collectAsStateWithLifecycle()
    val parseDraftError by viewModel.parseDraftError.collectAsStateWithLifecycle()
    val lastDeletedTx by viewModel.lastDeletedTransaction.collectAsStateWithLifecycle()
    val statusBannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showQuickRegisterSheet by rememberSaveable { mutableStateOf(openQuickRegisterInitially) }
    var quickRegisterInitialType by remember { mutableStateOf<TransactionType?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }

    var showTopOverflowMenu by remember { mutableStateOf(false) }
    var showGeminiAssistantSheet by rememberSaveable { mutableStateOf(false) }
    var showGoalsModal by rememberSaveable { mutableStateOf(false) }
    var showSettingsModal by rememberSaveable { mutableStateOf(false) }
    var showAddAccountModal by rememberSaveable { mutableStateOf(false) }
    var addAccountInitialType by remember { mutableStateOf(AccountType.DEBITO) }

    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(uiState.prefs.onboardingCompleted) {
        if (uiState.prefs.onboardingCompleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Manejo del botón Atrás en pestañas secundarias para volver a Inicio
    if (currentTabIndex != 0) {
        BackHandler {
            currentTabIndex = 0
        }
    }

    // Mostrar Snackbar con opción "Deshacer" al eliminar un movimiento
    LaunchedEffect(lastDeletedTx) {
        val deleted = lastDeletedTx ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Transacción eliminada",
            actionLabel = "Deshacer",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDeleteTransaction()
        } else {
            viewModel.clearUndoState()
        }
    }

    // Mostrar mensajes de estado
    LaunchedEffect(statusBannerMessage) {
        val msg = statusBannerMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        viewModel.dismissStatusBanner()
    }

    // 1. Si el usuario tiene activo el bloqueo con PIN/Huella y aún no ha desbloqueado
    if (uiState.prefs.pinLockEnabled && !isAppUnlocked) {
        PinAndBiometricLockScreen(
            onUnlockWithPin = { viewModel.unlockWithPin(it) },
            onUnlockWithDeviceCredential = { viewModel.unlockWithBiometricDeviceCredential() }
        )
        return
    }

    // 2. Si es la primera vez que abre la app, mostrar el Onboarding de 3 pasos
    if (!uiState.isLoading && !uiState.prefs.onboardingCompleted) {
        OnboardingThreeStepScreen(
            onCompleteOnboarding = { ef, nu, neq, mode, cutoff, income, obName, obAmt, obDay ->
                viewModel.completeOnboarding(ef, nu, neq, mode, cutoff, income, obName, obAmt, obDay)
            },
            onLoadDemoDirect = {
                viewModel.loadThreeMonthDemo()
            }
        )
        return
    }

    val currentTab = MiPlataTab.entries[currentTabIndex]
    val backgroundBrush = if (useDarkTheme) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0C182D),
                Color(0xFF070E1B)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.background
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentTab.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        // Ocultar / mostrar saldos
                        IconButton(
                            onClick = { viewModel.toggleHideBalances() },
                            modifier = Modifier.testTag("toggle_hide_balances_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.prefs.hideBalances) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = "Ocultar o mostrar saldos"
                            )
                        }

                        // Ícono de estado de copia de seguridad
                        IconButton(
                            onClick = {
                                viewModel.showStatusMessage("Copia local sincronizada • Toca para respaldar en JSON/CSV")
                                showSettingsModal = true
                            },
                            modifier = Modifier.testTag("backup_status_icon_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "Estado de copia de seguridad",
                                tint = Color(0xFF10B981)
                            )
                        }

                        // Menú superior derecho (Asistente Gemini, Metas, Ajustes y Exportar)
                        Box {
                            IconButton(
                                onClick = { showTopOverflowMenu = true },
                                modifier = Modifier.testTag("top_overflow_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Menú principal"
                                )
                            }

                            DropdownMenu(
                                expanded = showTopOverflowMenu,
                                onDismissRequest = { showTopOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Asistente Gemini", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        showGeminiAssistantSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Metas") },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Flag, contentDescription = null)
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        showGoalsModal = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ajustes") },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        showSettingsModal = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Exportar") },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null)
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        showSettingsModal = true
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    MiPlataTab.INICIO -> HomeScreen(
                        uiState = uiState,
                        onChangePeriodMode = { mode ->
                            viewModel.setPeriodConfiguration(
                                mode,
                                uiState.prefs.customCutoffDay,
                                uiState.prefs.baseMonthlyIncome
                            )
                        },
                        onToggleDeductCreditCard = { enabled ->
                            viewModel.setDeductCreditCardInAvailable(enabled)
                        },
                        onToggleAccountInAvailableFilter = { acc ->
                            viewModel.toggleAccountIncludedInAvailable(acc)
                        },
                        onMarkPendingAsPaid = { tx ->
                            viewModel.markTransactionPaid(tx)
                        },
                        onEditTransaction = { tx ->
                            editingTransaction = tx
                            quickRegisterInitialType = tx.type
                            showQuickRegisterSheet = true
                        },
                        onSkipPendingTransaction = { tx ->
                            viewModel.skipPendingTransaction(tx)
                        },
                        onLoadDemo = { viewModel.loadThreeMonthDemo() },
                        onClearDemo = { viewModel.clearDemoData() },
                        onOpenQuickRegister = {
                            editingTransaction = null
                            quickRegisterInitialType = null
                            showQuickRegisterSheet = true
                        }
                    )

                    MiPlataTab.CALENDARIO -> CalendarScreen(
                        uiState = uiState,
                        onChangeMonth = { delta -> viewModel.changeCalendarMonth(delta) },
                        onToggleFlowMode = { showFlow -> viewModel.toggleCalendarFlowMode(showFlow) },
                        onToggleCreditCardsMode = { showCards -> viewModel.toggleCalendarCreditCardsMode(showCards) },
                        onToggleAccountFilter = { acc -> viewModel.toggleAccountIncludedInAvailable(acc) },
                        onMarkTransactionPaid = { tx -> viewModel.markTransactionPaid(tx) },
                        onEditTransaction = { tx ->
                            editingTransaction = tx
                            quickRegisterInitialType = tx.type
                            showQuickRegisterSheet = true
                        }
                    )

                    MiPlataTab.PRESUPUESTO -> BudgetScreen(
                        uiState = uiState,
                        onChangeMonth = { delta -> viewModel.changeBudgetMonth(delta) },
                        onUpdateCategoryLimitAndGroup = { cat, limit, group ->
                            viewModel.updateCategoryBudgetLimit(cat, limit, group)
                        },
                        onEditTransaction = { tx ->
                            editingTransaction = tx
                            quickRegisterInitialType = tx.type
                            showQuickRegisterSheet = true
                        },
                        onOpenBudgetSettings = { showSettingsModal = true }
                    )

                    MiPlataTab.CUENTAS -> AccountsScreen(
                        uiState = uiState,
                        onOpenAddAccountWithType = { initialType ->
                            addAccountInitialType = initialType
                            showAddAccountModal = true
                        },
                        onRegisterLoanOrLentAbono = { targetAcc, amt, debitAccId ->
                            viewModel.registerLoanOrLentAbono(targetAcc, amt, debitAccId)
                        }
                    )

                    MiPlataTab.HISTORIAL -> MovementsScreen(
                        uiState = uiState,
                        onEditTransaction = { tx ->
                            editingTransaction = tx
                            quickRegisterInitialType = tx.type
                            showQuickRegisterSheet = true
                        },
                        onDeleteTransaction = { tx ->
                            viewModel.deleteTransactionWithUndo(tx)
                        },
                        onOpenQuickRegister = {
                            editingTransaction = null
                            quickRegisterInitialType = null
                            showQuickRegisterSheet = true
                        }
                    )
                }

                // BARRA INFERIOR FLOTANTE CON 5 PESTANYAS SOLO CON ÍCONO + BOTÓN FLOTANTE "+" A LA DERECHA
                FloatingBottomNavAndFab(
                    currentTabIndex = currentTabIndex,
                    onSelectTab = { currentTabIndex = it },
                    onClickFab = {
                        editingTransaction = null
                        quickRegisterInitialType = null
                        showQuickRegisterSheet = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }

    // Hoja inferior de "Agregar transacción" (Menú de 8 tipos + formulario completo + Gemini voz/texto/foto)
    if (showQuickRegisterSheet) {
        QuickRegisterSheet(
            uiState = uiState,
            editingTransaction = editingTransaction,
            isParsingAiDraft = isParsingAiDraft,
            parsedDraft = parsedDraft,
            parseDraftError = parseDraftError,
            onParseNaturalText = { viewModel.parseVoiceOrTextToDraft(it) },
            onParseReceiptBitmap = { viewModel.parseReceiptBitmapToDraft(it) },
            onAutoCategorizeNote = { note, cb -> viewModel.autoCategorizeNote(note, cb) },
            onClearDraft = { viewModel.clearParsedDraft() },
            onSaveMovement = { id, type, amount, accId, toAccId, catId, note, receiptUri, isRec, status, recFreq, installments ->
                viewModel.saveTransaction(
                    id = id,
                    type = type,
                    amount = amount,
                    accountId = accId,
                    toAccountId = toAccId,
                    categoryId = catId,
                    note = note,
                    receiptUri = receiptUri,
                    isRecurring = isRec,
                    status = status,
                    recurrence = recFreq,
                    totalInstallments = installments
                )
            },
            onDismiss = {
                showQuickRegisterSheet = false
                editingTransaction = null
                quickRegisterInitialType = null
                viewModel.clearParsedDraft()
            }
        )
    }

    // Hoja del Asistente Gemini (accesible desde el menú superior derecho)
    if (showGeminiAssistantSheet) {
        val assistantSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showGeminiAssistantSheet = false },
            sheetState = assistantSheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(640.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Asistente Financiero Gemini",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    IconButton(onClick = { showGeminiAssistantSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar asistente")
                    }
                }
                AssistantScreen(
                    messages = chatMessages,
                    isChatLoading = isChatLoading,
                    aiReport = aiReport,
                    isGeneratingReport = isGeneratingReport,
                    reportError = aiReportError,
                    onSendQuestion = { viewModel.sendChatMessageToGemini(it) },
                    onGenerateReport = { viewModel.requestGeminiReportAndTip() }
                )
            }
        }
    }

    // Modal de Metas (accesible desde el menú superior derecho)
    if (showGoalsModal) {
        GoalsDialogModal(
            uiState = uiState,
            onSaveGoal = { name, target, monthly, months ->
                viewModel.saveSavingGoal(name, target, monthly, months)
            },
            onContributeToGoal = { goal, amt, accId ->
                viewModel.contributeToGoal(goal, amt, accId)
            },
            onDeleteGoal = { viewModel.deleteSavingGoal(it) },
            onDismiss = { showGoalsModal = false }
        )
    }

    // Modal de Ajustes y Exportar CSV/JSON
    if (showSettingsModal) {
        SettingsAndBackupDialog(
            uiState = uiState,
            onSavePeriodConfig = { mode, cutoff, baseIncome ->
                viewModel.setPeriodConfiguration(mode, cutoff, baseIncome)
            },
            onToggleTheme = { viewModel.toggleThemeMode(it) },
            onConfigurePin = { enabled, pin -> viewModel.configurePinLock(enabled, pin) },
            onExportCsv = { viewModel.repository.exportTransactionsToCsv() },
            onCreateBackupJson = { viewModel.repository.createBackupJson() },
            onRestoreBackupJson = { viewModel.repository.restoreFromBackupJson(it) },
            onLoadDemo = { viewModel.loadThreeMonthDemo() },
            onClearDemo = { viewModel.clearDemoData() },
            onStatusMessage = { msg -> viewModel.showStatusMessage(msg) },
            onDismiss = { showSettingsModal = false }
        )
    }

    // Modal para crear nueva cuenta (Débito, Ahorros, Tarjeta de crédito, Préstamo o Prestado)
    if (showAddAccountModal) {
        AddAccountDialog(
            initialType = addAccountInitialType,
            onSaveAccountFull = { name, type, initBal, colorHex, iconName, limit, cutoff, dueDay, last4, person, orig, recOrPaid, inst ->
                viewModel.saveAccountFull(
                    name = name,
                    type = type,
                    initialBalance = initBal,
                    colorHex = colorHex,
                    iconName = iconName,
                    creditLimit = limit,
                    billingCutoffDay = cutoff,
                    paymentDueDay = dueDay,
                    lastFourDigits = last4,
                    personName = person,
                    originalAmount = orig,
                    receivedOrPaidAmount = recOrPaid,
                    monthlyInstallment = inst
                )
            },
            onDismiss = { showAddAccountModal = false }
        )
    }
}

/**
 * Barra inferior flotante con 5 pestañas solo con ícono (Inicio, Calendario, Presupuesto, Cuentas, Historial)
 * y botón flotante "+" siempre visible a la derecha.
 */
@Composable
private fun FloatingBottomNavAndFab(
    currentTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    onClickFab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cápsula flotante con las 5 pestañas solo con ícono
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .weight(1f)
                .height(62.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiPlataTab.entries.forEachIndexed { index, tab ->
                    val selected = currentTabIndex == index
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSelectTab(index) }
                            .testTag(tab.tag),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Botón flotante "+" siempre visible a la derecha
        FloatingActionButton(
            onClick = onClickFab,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(60.dp)
                .testTag("fab_quick_register")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Agregar transacción",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
