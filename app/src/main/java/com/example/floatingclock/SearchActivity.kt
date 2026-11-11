package com.example.floatingclock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEARCH_GRID_COLUMNS = 5
private const val SEARCH_ICON_SIZE_DP = 48

private enum class SearchTarget(val label: Int, val packageName: String) {
    TAOBAO(R.string.search_taobao, "com.taobao.taobao"),
    JD(R.string.search_jd, "com.jingdong.app.mall"),
    PINDUODUO(R.string.search_pinduoduo, "com.xunmeng.pinduoduo"),
    DOUYIN_MALL(R.string.search_douyin_mall, "com.ss.android.ugc.livelite"),
    XIAOHONGSHU(R.string.search_xiaohongshu, "com.xingin.xhs"),
    DOUYIN(R.string.search_douyin, "com.ss.android.ugc.aweme"),
    BILIBILI(R.string.search_bilibili, "tv.danmaku.bilibili"),
    CHROME(R.string.search_chrome, "com.chrome.beta"),
}

private enum class SearchCategory(val label: Int, val targets: List<SearchTarget>) {
    SHOPPING(R.string.search_shopping, listOf(
        SearchTarget.TAOBAO, SearchTarget.JD, SearchTarget.PINDUODUO, SearchTarget.DOUYIN_MALL,
    )),
    SOCIAL(R.string.search_social, listOf(
        SearchTarget.XIAOHONGSHU, SearchTarget.DOUYIN, SearchTarget.BILIBILI, SearchTarget.CHROME,
    )),
}

class SearchActivity : ComponentActivity() {
    private var keyword by mutableStateOf("")
    private var inputError by mutableStateOf(false)
    private var pendingTarget by mutableStateOf<SearchTarget?>(null)
    private var permissionVisible by mutableStateOf(false)
    private var assistReady by mutableStateOf(false)
    private var assistEnabled by mutableStateOf(false)
    private var assistTimedOut by mutableStateOf(false)
    private var connectionAttempt by mutableStateOf(0)
    private var resumed by mutableStateOf(false)
    private var requestedSettings by mutableStateOf(false)
    private var collapsedCategories by mutableStateOf(emptySet<SearchCategory>())
    private var appIcons by mutableStateOf(emptyMap<SearchTarget, ImageBitmap>())
    private val layoutPreferences by lazy { getSharedPreferences(LAYOUT_PREFERENCES, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyword = (savedInstanceState?.getString(KEYWORD) ?: intent.getStringExtra(EXTRA_QUERY)).orEmpty()
        pendingTarget = SearchTarget.entries.firstOrNull {
            it.name == savedInstanceState?.getString(TARGET)
        }
        permissionVisible = savedInstanceState?.getBoolean(PERMISSION_VISIBLE) == true
        requestedSettings = savedInstanceState?.getBoolean(REQUESTED_SETTINGS) == true
        val savedCategories = layoutPreferences.getStringSet(COLLAPSED_CATEGORIES, emptySet()).orEmpty()
        collapsedCategories = SearchCategory.entries.filter { it.name in savedCategories }.toSet()
        loadTargetIcons()
        enableToolboxEdgeToEdge()
        setContent {
            ToolboxTheme {
                LaunchedEffect(resumed, permissionVisible, connectionAttempt) {
                    if (!resumed || !permissionVisible) return@LaunchedEffect
                    repeat(CONNECTION_CHECK_COUNT) {
                        refreshAssistState("connection_check")
                        if (assistReady || !assistEnabled) return@LaunchedEffect
                        delay(CONNECTION_CHECK_INTERVAL_MS)
                    }
                    assistTimedOut = true
                }
                SearchScreen(
                    keyword = keyword,
                    inputError = inputError,
                    autoFocus = savedInstanceState == null,
                    collapsedCategories = collapsedCategories,
                    appIcons = appIcons,
                    onKeywordChange = {
                        keyword = it
                        inputError = false
                    },
                    onTarget = ::chooseTarget,
                    onToggleCategory = ::toggleCategory,
                    onBack = ::finish,
                    onAssistSettings = { showPermission(null) },
                )
                if (permissionVisible) {
                    SearchPermissionDialog(
                        target = pendingTarget,
                        ready = assistReady,
                        enabled = assistEnabled,
                        timedOut = assistTimedOut,
                        requestedSettings = requestedSettings,
                        onDismiss = ::dismissPermission,
                        onSettings = ::openSettings,
                        onRetry = ::retryConnection,
                        onContinue = {
                            val target = pendingTarget
                            if (target == null) dismissPermission() else launch(target, assisted = true)
                        },
                        onManual = { pendingTarget?.let { launch(it, assisted = false) } },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAssistState("search_resumed")
        assistTimedOut = false
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEYWORD, keyword)
        outState.putString(TARGET, pendingTarget?.name)
        outState.putBoolean(PERMISSION_VISIBLE, permissionVisible)
        outState.putBoolean(REQUESTED_SETTINGS, requestedSettings)
    }

    private fun chooseTarget(target: SearchTarget) {
        if (keyword.isBlank()) {
            inputError = true
            return
        }
        launch(target, assisted = target != SearchTarget.CHROME)
    }

    private fun launch(target: SearchTarget, assisted: Boolean) {
        val query = keyword.trim()
        val launchIntent = resolveTarget(target)
        if (launchIntent == null) {
            dismissPermission()
            showUnavailable(target)
            return
        }
        val intent = if (target == SearchTarget.CHROME) {
            val uri = Uri.Builder().scheme("https").authority("www.google.com").appendPath("search")
                .appendQueryParameter("q", query).build()
            Intent(Intent.ACTION_VIEW, uri).setPackage(target.packageName)
        } else {
            launchIntent
        }

        SearchAccessibilityService.cancelSearch()
        val installedPackage = launchIntent.component?.packageName ?: target.packageName
        if (assisted && !SearchAccessibilityService.requestSearch(installedPackage, query)) {
            showPermission(target)
            return
        }
        try {
            startActivity(intent)
            dismissPermission()
        } catch (_: ActivityNotFoundException) {
            handleLaunchFailure(target)
        } catch (_: SecurityException) {
            handleLaunchFailure(target)
        }
    }

    private fun resolveTarget(target: SearchTarget): Intent? {
        return packageManager.getLaunchIntentForPackage(target.packageName)
            ?: if (target == SearchTarget.BILIBILI) {
                packageManager.getLaunchIntentForPackage("com.bilibili.app.in")
            } else null
    }

    private fun loadTargetIcons() {
        val iconSize = (SEARCH_ICON_SIZE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        lifecycleScope.launch {
            appIcons = withContext(Dispatchers.IO) {
                buildMap {
                    for (target in SearchTarget.entries) {
                        val component = resolveTarget(target)?.component ?: continue
                        try {
                            val icon = packageManager.getActivityIcon(component)
                            put(target, icon.toBitmap(iconSize, iconSize).asImageBitmap())
                        } catch (_: PackageManager.NameNotFoundException) {
                            // 应用可能在查询期间卸载，界面继续使用默认图标。
                        }
                    }
                }
            }
        }
    }

    private fun toggleCategory(category: SearchCategory) {
        collapsedCategories = if (category in collapsedCategories) {
            collapsedCategories - category
        } else {
            collapsedCategories + category
        }
        layoutPreferences.edit()
            .putStringSet(COLLAPSED_CATEGORIES, collapsedCategories.map { it.name }.toSet())
            .apply()
    }

    private fun handleLaunchFailure(target: SearchTarget) {
        SearchAccessibilityService.cancelSearch()
        dismissPermission()
        showUnavailable(target)
    }

    private fun showPermission(target: SearchTarget?) {
        pendingTarget = target
        resetConnectionCheck("permission_prompt")
        permissionVisible = true
    }

    private fun retryConnection() {
        resetConnectionCheck("connection_retry")
    }

    private fun resetConnectionCheck(event: String) {
        refreshAssistState(event)
        assistTimedOut = false
        connectionAttempt++
    }

    private fun refreshAssistState(event: String) {
        assistReady = SearchAccessibilityService.isReady()
        assistEnabled = SearchAccessibilityService.isEnabled(this)
        SearchAccessibilityService.logPermissionState(this, event, assistEnabled, assistReady)
    }

    private fun dismissPermission() {
        permissionVisible = false
        pendingTarget = null
        requestedSettings = false
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            requestedSettings = true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.search_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showUnavailable(target: SearchTarget) {
        Toast.makeText(this, getString(R.string.search_unavailable, getString(target.label)),
            Toast.LENGTH_LONG).show()
    }

    companion object {
        internal const val EXTRA_QUERY = "com.example.floatingclock.SEARCH_QUERY"
        private const val KEYWORD = "keyword"
        private const val TARGET = "target"
        private const val PERMISSION_VISIBLE = "permissionVisible"
        private const val REQUESTED_SETTINGS = "requestedSettings"
        private const val LAYOUT_PREFERENCES = "search_layout"
        private const val COLLAPSED_CATEGORIES = "collapsed_categories"
        private const val CONNECTION_CHECK_COUNT = 16
        private const val CONNECTION_CHECK_INTERVAL_MS = 250L
    }
}

@Composable
private fun SearchScreen(
    keyword: String,
    inputError: Boolean,
    autoFocus: Boolean,
    collapsedCategories: Set<SearchCategory>,
    appIcons: Map<SearchTarget, ImageBitmap>,
    onKeywordChange: (String) -> Unit,
    onTarget: (SearchTarget) -> Unit,
    onToggleCategory: (SearchCategory) -> Unit,
    onBack: () -> Unit,
    onAssistSettings: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var initiallyFocused by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocus, windowFocused) {
        if (autoFocus && windowFocused && !initiallyFocused) {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
            initiallyFocused = true
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_tool_back), stringResource(R.string.search_back))
                }
                Text(stringResource(R.string.search_tool), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focusRequester),
                label = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_tool_search), null) },
                isError = inputError,
                supportingText = if (inputError) {
                    { Text(stringResource(R.string.search_empty)) }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight).padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    for (category in SearchCategory.entries) {
                        SearchGroup(
                            category = category,
                            expanded = category !in collapsedCategories,
                            appIcons = appIcons,
                            onToggle = { onToggleCategory(category) },
                            onTarget = onTarget,
                        )
                    }
                    TextButton(onClick = onAssistSettings) {
                        Text(stringResource(R.string.search_assist_settings))
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().pointerInput(keyboard, focusManager) {
                            val threshold = 40.dp.toPx()
                            var displacement = 0f
                            detectVerticalDragGestures(
                                onDragStart = { displacement = 0f },
                                onDragEnd = { if (displacement >= threshold) dismissKeyboard() },
                                onVerticalDrag = { _, delta -> displacement += delta },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchGroup(
    category: SearchCategory,
    expanded: Boolean,
    appIcons: Map<SearchTarget, ImageBitmap>,
    onToggle: () -> Unit,
    onTarget: (SearchTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupState = stringResource(
        if (expanded) R.string.search_group_expanded else R.string.search_group_collapsed,
    )
    Column(modifier.fillMaxWidth()) {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().semantics {
                heading()
                stateDescription = groupState
            },
            color = androidx.compose.ui.graphics.Color.Transparent,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(category.label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    painterResource(R.drawable.ic_tool_expand),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                )
            }
        }
        if (expanded) {
            for (row in category.targets.chunked(SEARCH_GRID_COLUMNS)) {
                Row(Modifier.fillMaxWidth()) {
                    for (target in row) {
                        SearchTile(target, appIcons[target], onTarget, Modifier.weight(1f))
                    }
                    repeat(SEARCH_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SearchTile(
    target: SearchTarget,
    icon: ImageBitmap?,
    onTarget: (SearchTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onTarget(target) },
        modifier = modifier.heightIn(min = 64.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(SEARCH_ICON_SIZE_DP.dp))
            } else {
                Surface(
                    modifier = Modifier.size(SEARCH_ICON_SIZE_DP.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_tool_search), null, Modifier.size(18.dp))
                    }
                }
            }
            Text(
                stringResource(target.label),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchPermissionDialog(
    target: SearchTarget?,
    ready: Boolean,
    enabled: Boolean,
    timedOut: Boolean,
    requestedSettings: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(stringResource(when {
                ready -> R.string.search_permission_ready
                enabled && timedOut -> R.string.search_connection_unavailable
                enabled -> R.string.search_connecting_title
                else -> R.string.search_permission_title
            }))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                target?.let { Text(stringResource(it.label), fontWeight = FontWeight.Bold) }
                Text(stringResource(when {
                    enabled && !ready && timedOut -> R.string.search_connection_unavailable_body
                    enabled && !ready -> R.string.search_connecting_body
                    else -> R.string.search_assist_description
                }))
                if (requestedSettings && !enabled) Text(stringResource(R.string.search_permission_waiting))
            }
        },
        confirmButton = {
            if (ready || !enabled || timedOut) TextButton(onClick = when {
                ready -> onContinue
                enabled -> onRetry
                else -> onSettings
            }) {
                Text(stringResource(when {
                    !ready && enabled -> R.string.search_connection_retry
                    !ready -> R.string.search_open_settings
                    target != null -> R.string.search_continue
                    else -> android.R.string.ok
                }))
            }
        },
        dismissButton = {
            TextButton(onClick = if (target != null) onManual else onDismiss) {
                Text(stringResource(if (target != null) R.string.search_manual else R.string.search_cancel))
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SearchPreview() {
    ToolboxTheme {
        SearchScreen(
            keyword = "无线耳机",
            inputError = false,
            autoFocus = false,
            collapsedCategories = emptySet(),
            appIcons = emptyMap(),
            onKeywordChange = {},
            onTarget = {},
            onToggleCategory = {},
            onBack = {},
            onAssistSettings = {},
        )
    }
}
