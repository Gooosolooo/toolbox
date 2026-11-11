package com.example.floatingclock

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONException

class ShoppingActivity : ComponentActivity() {
    private val store by lazy { ShoppingStore.get(this) }
    private var selectedId by mutableStateOf<String?>(null)
    private var showingAdd by mutableStateOf(false)
    private var overlayEnabled by mutableStateOf(false)
    private var adding by mutableStateOf(false)
    private var exporting by mutableStateOf(false)
    private var focusRequest: FocusRequest? = null
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val request = focusRequest
        focusRequest = null
        request?.let(::beginFocus)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString(EXTRA_ITEM) ?: intent.getStringExtra(EXTRA_ITEM)
        showingAdd = savedInstanceState?.getBoolean(EXTRA_ADD) ?: intent.getBooleanExtra(EXTRA_ADD, false)
        enableToolboxEdgeToEdge()
        setContent {
            ToolboxTheme {
                val state by store.state.collectAsState()
                val item = state.items.find { it.id == selectedId }
                val back = {
                    if (showingAdd && !intent.getBooleanExtra(EXTRA_ADD, false)) showingAdd = false
                    else if (item != null && !showingAdd) selectedId = null
                    else finish()
                }
                BackHandler(showingAdd || item != null, onBack = back)
                Box(Modifier.fillMaxSize()) {
                    ShoppingScaffold(
                        title = when {
                            showingAdd -> R.string.shopping_add
                            item != null -> R.string.shopping_detail
                            else -> R.string.shopping_tool
                        },
                        onBack = back,
                        onAdd = if (showingAdd) null else ({ showingAdd = true }),
                        onExport = if (!showingAdd && item == null && !exporting) ::chooseBackupLocation else null,
                    ) {
                        when {
                            showingAdd -> ShoppingQuickAdd(adding, ::addItem)
                            item != null -> ShoppingDetail(
                                item = item,
                                active = state.session?.itemId == item.id,
                                overlayEnabled = overlayEnabled,
                                dateText = { DateFormat.getDateFormat(this@ShoppingActivity).format(Date(it)) },
                                onOverlay = ::openOverlaySettings,
                                onFocus = { priceStage, minutes ->
                                    requestFocus(FocusRequest(item.id, item.name, minutes, priceStage))
                                },
                                onEdit = { edit -> mutate { it.updateItem(item.id, edit) } },
                                onComplete = { complete(item.id, !item.done) },
                                onDelete = { delete(item.id) },
                            )
                            else -> ShoppingList(
                                state.items,
                                onSelect = { selectedId = it },
                                onTemporary = { name, minutes -> requestFocus(FocusRequest("", name, minutes, false)) },
                            )
                        }
                    }
                    val session = state.session
                    if (!overlayEnabled && session != null) {
                        ShoppingInAppTimer(
                            session = session,
                            bootCount = store.bootCount,
                            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 68.dp, end = 16.dp),
                            onAction = { action ->
                                startService(Intent(this@ShoppingActivity, ShoppingTimerService::class.java)
                                    .setAction(action).putExtra(ShoppingNotifications.EXTRA_SESSION, session.id))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedId = intent.getStringExtra(EXTRA_ITEM)
        showingAdd = intent.getBooleanExtra(EXTRA_ADD, false)
    }

    private fun chooseBackupLocation() {
        try {
            createBackup.launch(getString(R.string.shopping_backup_filename, LocalDate.now().toString()))
        } catch (exception: ActivityNotFoundException) {
            showBackupError(exception)
        }
    }

    private fun exportBackup(destination: Uri) {
        exporting = true
        lifecycleScope.launch {
            try {
                ShoppingBackup.export(contentResolver, destination, store)
                Toast.makeText(this@ShoppingActivity, R.string.shopping_backup_saved, Toast.LENGTH_SHORT).show()
            } catch (exception: IOException) {
                showBackupError(exception)
            } catch (exception: SecurityException) {
                showBackupError(exception)
            } catch (exception: JSONException) {
                showBackupError(exception)
            } finally {
                exporting = false
            }
        }
    }

    private fun showBackupError(exception: Exception) {
        android.util.Log.w("ShoppingActivity", "Shopping backup export failed", exception)
        Toast.makeText(this, R.string.shopping_backup_error, Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_ITEM, selectedId)
        outState.putBoolean(EXTRA_ADD, showingAdd)
    }

    override fun onResume() {
        super.onResume()
        overlayEnabled = Settings.canDrawOverlays(this)
        val active = store.state.value.session ?: return
        if (active.remaining(store.bootCount) == 0L) {
            lifecycleScope.launch {
                try {
                    finishShoppingTimer(this@ShoppingActivity, active.id)
                } catch (exception: IOException) {
                    saveError()
                }
            }
        } else {
            startTimerService()
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun addItem(name: String) {
        if (adding || name.isBlank()) return
        adding = true
        val item = ShoppingItem(name = name.trim())
        lifecycleScope.launch {
            try {
                store.change { it.copy(items = listOf(item) + it.items) }
                Toast.makeText(this@ShoppingActivity, R.string.shopping_saved, Toast.LENGTH_SHORT).show()
                if (intent.getBooleanExtra(EXTRA_ADD, false)) finish() else {
                    selectedId = null
                    showingAdd = false
                }
            } catch (exception: IOException) {
                saveError()
            } finally {
                adding = false
            }
        }
    }

    private fun requestFocus(request: FocusRequest) {
        val alreadyAsked = getPreferences(MODE_PRIVATE).getBoolean(NOTIFICATIONS_ASKED, false)
        if (!alreadyAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
            focusRequest = request
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginFocus(request)
        }
    }

    private fun beginFocus(request: FocusRequest) {
        lifecycleScope.launch {
            try {
                val state = store.change { original ->
                    val current = original.session
                    val state = if (current != null && current.remaining(store.bootCount) == 0L) {
                        original.settle(current.duration / SECOND_MS)
                    } else original
                    if (state.session?.let { it.itemId != request.itemId } == true) return@change state
                    val duration = request.minutes * MINUTE_MS
                    val now = System.currentTimeMillis()
                    val session = state.session ?: ShoppingSession(
                        UUID.randomUUID().toString(), request.itemId, request.name.ifBlank { getString(R.string.shopping_focus) },
                        now, duration, SystemClock.elapsedRealtime() + duration, now + duration, store.bootCount,
                    )
                    state.updateItem(request.itemId) { it.copy(priceStage = request.priceStage) }.copy(session = session)
                }
                if (state.session?.itemId != request.itemId) {
                    Toast.makeText(this@ShoppingActivity, R.string.shopping_active_elsewhere, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                updateShoppingAlarm(this@ShoppingActivity, state.session)
                startTimerService()
                if (request.priceStage) {
                    startActivity(Intent(this@ShoppingActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_QUERY, request.name))
                }
            } catch (exception: IOException) {
                saveError()
            }
        }
    }

    private fun startTimerService() {
        try {
            startForegroundService(Intent(this, ShoppingTimerService::class.java))
        } catch (exception: IllegalStateException) {
            android.util.Log.w("ShoppingActivity", "Shopping timer service could not start", exception)
        }
    }

    private fun complete(id: String, done: Boolean) {
        mutate(onSaved = { selectedId = null }) { current ->
            val session = current.session
            val settled = if (session?.itemId == id) current.settle(elapsedSeconds(session, store.bootCount)) else current
            settled.updateItem(id) { it.copy(done = done) }
        }
    }

    private fun delete(id: String) {
        mutate(onSaved = { selectedId = null }) { current ->
            current.copy(
                items = current.items.filterNot { it.id == id },
                session = current.session?.takeUnless { it.itemId == id },
            )
        }
    }

    private fun mutate(onSaved: () -> Unit = {}, transform: (ShoppingState) -> ShoppingState) {
        lifecycleScope.launch {
            try {
                store.change(transform)
                onSaved()
            } catch (exception: IOException) {
                saveError()
            }
        }
    }

    private fun saveError() {
        Toast.makeText(this, R.string.shopping_save_error, Toast.LENGTH_SHORT).show()
    }

    private data class FocusRequest(val itemId: String, val name: String, val minutes: Int, val priceStage: Boolean)

    companion object {
        internal const val EXTRA_ITEM = "com.example.floatingclock.SHOPPING_ITEM"
        internal const val EXTRA_ADD = "com.example.floatingclock.SHOPPING_ADD"
        private const val NOTIFICATIONS_ASKED = "shopping_notifications_asked"
    }
}

private const val DEFAULT_MINUTES = 15
private const val MAX_MINUTES = 120
private const val MAX_NAME_LENGTH = 120
private const val MAX_NOTE_LENGTH = 4_000
private val shoppingCardHeight = 54.dp
private val shoppingChipHeight = 42.dp
private val defaultChipHeight = 32.dp
private val minimumTouchTarget = 48.dp
private val budgetPattern = Regex("[0-9]*(\\.[0-9]{0,2})?")

@Composable
private fun ShoppingScaffold(
    title: Int,
    onBack: () -> Unit,
    onAdd: (() -> Unit)?,
    onExport: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_tool_back), stringResource(R.string.search_back))
                }
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                if (onAdd != null) OutlinedButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.shopping_add))
                }
                if (onExport != null) ShoppingBackupMenu(onExport)
            }
            content()
        }
    }
}

@Composable
private fun ShoppingBackupMenu(onExport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_tool_more), stringResource(R.string.shopping_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shopping_export_backup)) },
                onClick = {
                    expanded = false
                    onExport()
                },
            )
        }
    }
}

@Composable
private fun ShoppingQuickAdd(saving: Boolean, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var requestedFocus by remember { mutableStateOf(false) }
    val save = { if (!saving && name.isNotBlank()) onSave(name) }
    LaunchedEffect(windowFocused) {
        if (windowFocused && !requestedFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
            requestedFocus = true
        }
    }
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(MAX_NAME_LENGTH) },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(stringResource(R.string.shopping_name)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
    )
    Button(onClick = save, enabled = !saving && name.isNotBlank()) { Text(stringResource(R.string.shopping_done)) }
}

@Composable
private fun ShoppingInAppTimer(
    session: ShoppingSession,
    bootCount: Int,
    modifier: Modifier,
    onAction: (String) -> Unit,
) {
    var seconds by remember(session) { mutableStateOf(remainingSeconds(session.remaining(bootCount))) }
    LaunchedEffect(session) {
        while (seconds > 0 && session.pausedRemaining == null) {
            delay(250)
            seconds = remainingSeconds(session.remaining(bootCount))
        }
    }
    Surface(modifier.alpha(0.5f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60))
            IconButton(onClick = { onAction(ShoppingNotifications.ACTION_PAUSE) }) {
                val paused = session.pausedRemaining != null
                val icon = if (paused) R.drawable.ic_shopping_resume else R.drawable.ic_shopping_pause
                val label = if (paused) R.string.shopping_resume else R.string.shopping_pause
                Icon(painterResource(icon), stringResource(label))
            }
            IconButton(onClick = { onAction(ShoppingNotifications.ACTION_END) }) {
                Icon(painterResource(R.drawable.ic_shopping_done), stringResource(R.string.shopping_end))
            }
        }
    }
}

@Composable
private fun ShoppingList(items: List<ShoppingItem>, onSelect: (String) -> Unit, onTemporary: (String, Int) -> Unit) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var temporary by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(DEFAULT_MINUTES.toString()) }
    val duration = minutes.toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShoppingStatusChip(R.string.shopping_pending, selected = pagerState.currentPage == 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            ShoppingStatusChip(R.string.shopping_completed, selected = pagerState.currentPage == 1) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val done = page == 1
            ShoppingListPage(items.filter { it.done == done }, done, onSelect, onStart = { temporary = true })
        }
    }
    if (temporary) AlertDialog(
        onDismissRequest = { temporary = false },
        title = { Text(stringResource(R.string.shopping_temporary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(MAX_NAME_LENGTH) }, singleLine = true,
                    label = { Text(stringResource(R.string.shopping_optional_name)) })
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.shopping_duration)) }, singleLine = true,
                    isError = duration == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(enabled = duration != null, onClick = {
                if (duration != null) onTemporary(name.trim(), duration)
                temporary = false
                name = ""
            }) {
                Text(stringResource(R.string.shopping_start))
            }
        },
        dismissButton = { TextButton(onClick = { temporary = false }) { Text(stringResource(R.string.shopping_cancel)) } },
    )
}

@Composable
private fun ShoppingListPage(items: List<ShoppingItem>, done: Boolean, onSelect: (String) -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!done) TextButton(onClick = onStart) { Text(stringResource(R.string.shopping_temporary)) }
        if (items.isEmpty()) Text(stringResource(R.string.shopping_empty))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = ShoppingItem::id) { item ->
                Surface(shape = MaterialTheme.shapes.medium, onClick = { onSelect(item.id) }) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = shoppingCardHeight).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, Modifier.weight(1f))
                        val status = when {
                            item.done -> stringResource(
                                R.string.shopping_total,
                                durationLabel(item.records.sumOf { it.seconds }),
                            )
                            item.budget.isNotBlank() -> stringResource(R.string.shopping_amount, item.budget)
                            item.priceStage -> stringResource(R.string.shopping_stage_budget)
                            else -> stringResource(R.string.shopping_stage_need)
                        }
                        Text(status)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingStatusChip(label: Int, selected: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    val scale = shoppingChipHeight / defaultChipHeight
    val colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    // Keep the touch target at 48 dp in the surrounding density.
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        LocalMinimumInteractiveComponentSize provides (minimumTouchTarget / scale),
    ) {
        FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) }, colors = colors)
    }
}

@Composable
private fun ShoppingDetail(
    item: ShoppingItem,
    active: Boolean,
    overlayEnabled: Boolean,
    dateText: (Long) -> String,
    onOverlay: () -> Unit,
    onFocus: (Boolean, Int) -> Unit,
    onEdit: ((ShoppingItem) -> ShoppingItem) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    var minutes by rememberSaveable(item.id) { mutableStateOf(DEFAULT_MINUTES.toString()) }
    var noteExpanded by rememberSaveable(item.id) { mutableStateOf(item.note.isNotBlank()) }
    var note by rememberSaveable(item.id) { mutableStateOf(item.note) }
    var budget by rememberSaveable(item.id) { mutableStateOf(item.budget) }
    var history by rememberSaveable(item.id) { mutableStateOf(false) }
    var rename by rememberSaveable(item.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(item.id) { mutableStateOf(false) }
    val duration = minutes.toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { rename = true }) { Text(stringResource(R.string.shopping_rename)) }
            }
            if (!item.done) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.shopping_duration)) }, singleLine = true,
                    enabled = !active, isError = duration == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(budget, { value ->
                if (value.length <= 12 && value.matches(budgetPattern)) {
                    budget = value
                    onEdit { it.copy(budget = value.takeIf { amount -> amount.toBigDecimalOrNull() != null }.orEmpty()) }
                }
            }, label = { Text(stringResource(R.string.shopping_budget)) }, singleLine = true,
                suffix = { Text(stringResource(R.string.shopping_yuan)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (!item.done) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { noteExpanded = true; onFocus(false, duration ?: DEFAULT_MINUTES) },
                        enabled = active || duration != null, modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.shopping_need_action)) }
                    OutlinedButton(
                        onClick = { onFocus(true, duration ?: DEFAULT_MINUTES) },
                        enabled = active || duration != null, modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.shopping_budget_action)) }
                }
            }
            if (noteExpanded) OutlinedTextField(note, { value ->
                note = value.take(MAX_NOTE_LENGTH)
                val updated = note
                onEdit { it.copy(note = updated) }
            }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.shopping_note)) }, minLines = 3)
            OutlinedButton(onClick = { history = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shopping_total, item.records.sumOf { it.seconds }.let { durationLabel(it) }))
            }
            if (!overlayEnabled && !item.done) TextButton(onClick = onOverlay) {
                Text(stringResource(R.string.shopping_overlay_permission))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.shopping_delete)) }
            Spacer(Modifier.weight(1f))
            if (item.done) OutlinedButton(onClick = onComplete) { Text(stringResource(R.string.shopping_restore)) }
            else Button(onClick = onComplete, shape = MaterialTheme.shapes.medium) {
                Icon(painterResource(R.drawable.ic_shopping_done), stringResource(R.string.shopping_mark_done))
            }
        }
    }
    if (history) ShoppingHistory(item, dateText, onDismiss = { history = false }, onEdit = onEdit)
    if (rename) {
        var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.shopping_rename)) },
            text = { OutlinedTextField(name, { name = it.take(MAX_NAME_LENGTH) }, singleLine = true,
                label = { Text(stringResource(R.string.shopping_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val updated = name.trim()
                    onEdit { it.copy(name = updated) }
                    rename = false
                }) { Text(stringResource(R.string.shopping_save)) }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.shopping_cancel)) } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.shopping_delete_title, item.name)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.shopping_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.shopping_cancel)) } },
    )
}

@Composable
private fun ShoppingHistory(
    item: ShoppingItem,
    dateText: (Long) -> String,
    onDismiss: () -> Unit,
    onEdit: ((ShoppingItem) -> ShoppingItem) -> Unit,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = item.records.find { it.id == editingId }
    if (editing != null) {
        var minutes by rememberSaveable(editing.id) { mutableStateOf((editing.seconds / 60).toString()) }
        var seconds by rememberSaveable(editing.id) { mutableStateOf((editing.seconds % 60).toString()) }
        val minuteValue = minutes.toLongOrNull()
        val secondValue = seconds.toLongOrNull()?.takeIf { it in 0..59 }
        AlertDialog(
            onDismissRequest = { editingId = null }, title = { Text(stringResource(R.string.shopping_edit_time)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f), label = { Text(stringResource(R.string.shopping_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) },
                        Modifier.width(88.dp), label = { Text(stringResource(R.string.shopping_seconds)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = minuteValue != null && secondValue != null, onClick = {
                    val total = (minuteValue ?: 0) * 60 + (secondValue ?: 0)
                    onEdit { current ->
                        current.copy(records = current.records.map { if (it.id == editing.id) it.copy(seconds = total) else it })
                    }
                    editingId = null
                }) { Text(stringResource(R.string.shopping_save)) }
            },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text(stringResource(R.string.shopping_cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.shopping_total, durationLabel(item.records.sumOf { it.seconds }))) },
            text = {
                if (item.records.isEmpty()) Text(stringResource(R.string.shopping_no_history))
                else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(item.records, key = ShoppingRecord::id) { record ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editingId = record.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(dateText(record.date), Modifier.weight(1f))
                            Text(durationLabel(record.seconds))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shopping_done)) } },
        )
    }
}

@Composable
private fun durationLabel(seconds: Long): String =
    if (seconds % 60 == 0L) stringResource(R.string.shopping_time_minutes, seconds / 60)
    else stringResource(R.string.shopping_time_minutes_seconds, seconds / 60, seconds % 60)

@Preview(showBackground = true, widthDp = 390, locale = "zh")
@Composable
private fun ShoppingPreview() {
    ToolboxTheme {
        ShoppingScaffold(R.string.shopping_tool, {}, {}, onExport = {}) {
            ShoppingList(listOf(ShoppingItem(name = "厕所除臭"), ShoppingItem(name = "牙膏", budget = "35")), {}, { _, _ -> })
        }
    }
}

@Preview(showBackground = true, widthDp = 390, locale = "zh")
@Composable
private fun ShoppingDetailPreview() {
    ToolboxTheme {
        ShoppingScaffold(R.string.shopping_detail, {}, {}) {
            ShoppingDetail(
                item = ShoppingItem(name = "厕所除臭", note = "希望减少异味，能接受轻微香味", budget = "35"),
                active = false, overlayEnabled = true, dateText = { "" }, onOverlay = {},
                onFocus = { _, _ -> }, onEdit = {}, onComplete = {}, onDelete = {},
            )
        }
    }
}
