package io.github.lsp1.quietnotify.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import io.github.lsp1.quietnotify.Config
import io.github.lsp1.quietnotify.QuietNotifyApp
import io.github.lsp1.quietnotify.data.ConfigRepository
import io.github.lsp1.quietnotify.data.InstalledApp
import io.github.lsp1.quietnotify.data.InstalledAppsRepository

@Composable
fun QuietNotifyRoot(app: QuietNotifyApp) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors) {
        val service by app.service.collectAsState()
        QuietNotifyScreen(service)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietNotifyScreen(service: XposedService?) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository() }
    val appsRepository = remember { InstalledAppsRepository(context.applicationContext) }
    val rulesState by configRepository.state.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(service) { configRepository.connect(service) }
    LaunchedEffect(Unit) { apps = appsRepository.load() }

    val hasSystemScope = runCatching { service?.scope?.contains("system") == true }.getOrDefault(false)
    val hasSystemUiScope = runCatching {
        service?.scope?.contains("com.android.systemui") == true
    }.getOrDefault(false)
    val systemServerLoaded = runCatching {
        service?.runningTargets?.any { target ->
            target.processName == "system_server" || target.processName == "system"
        } == true
    }.getOrDefault(false)
    val systemUiLoaded = runCatching {
        service?.runningTargets?.any { target ->
            target.processName == "com.android.systemui"
        } == true
    }.getOrDefault(false)
    val filtered = apps.orEmpty().filter {
        (showSystem || !it.isSystem) &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
    }.sortedWith(compareByDescending<InstalledApp> { rulesState.rules.containsKey(it.packageName) }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("通知静默窗口", fontWeight = FontWeight.Bold)
                        Text("第一次响，窗口内安静", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    connected = service != null,
                    hasSystemScope = hasSystemScope,
                    hasSystemUiScope = hasSystemUiScope,
                    systemServerLoaded = systemServerLoaded,
                    systemUiLoaded = systemUiLoaded,
                    enabled = rulesState.enabled,
                    selectedCount = rulesState.rules.size,
                    onEnabledChange = configRepository::setEnabled,
                )
            }
            item {
                FeatureCard(
                    headsUpEnabled = rulesState.headsUpEnabled,
                    soundEnabled = rulesState.soundEnabled,
                    connected = service != null,
                    onHeadsUpEnabledChange = configRepository::setHeadsUpEnabled,
                    onSoundEnabledChange = configRepository::setSoundEnabled,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apps != null,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text("显示系统应用")
                    Spacer(Modifier.weight(1f))
                    Text("已选择 ${rulesState.rules.size}", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (apps == null) {
                item {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (filtered.isEmpty()) {
                item { EmptyState() }
            } else {
                items(filtered, key = { it.packageName }) { item ->
                    val duration = rulesState.rules[item.packageName]
                    AppRow(
                        app = item,
                        duration = duration,
                        enabled = service != null,
                        onToggle = { checked ->
                            if (checked) editing = item else configRepository.setRule(item.packageName, null)
                        },
                        onEdit = { editing = item },
                    )
                }
            }
            item {
                Text(
                    "使用说明：在 LSPosed 中启用模块并确认作用域包含“系统框架 (system)”和“系统界面 (com.android.systemui)”，然后重启设备。模块不注入所选应用。",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { app ->
        DurationDialog(
            appName = app.label,
            initialMs = rulesState.rules[app.packageName] ?: Config.DEFAULT_DURATION_MS,
            onDismiss = { editing = null },
            onConfirm = {
                configRepository.setRule(app.packageName, it)
                editing = null
            },
        )
    }
}

@Composable
private fun StatusCard(
    connected: Boolean,
    hasSystemScope: Boolean,
    hasSystemUiScope: Boolean,
    systemServerLoaded: Boolean,
    systemUiLoaded: Boolean,
    enabled: Boolean,
    selectedCount: Int,
    onEnabledChange: (Boolean) -> Unit,
) {
    val ready = connected && hasSystemScope && hasSystemUiScope && systemServerLoaded && systemUiLoaded
    val accent = if (ready) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
                Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                    Text(
                        when {
                            !connected -> "等待 LSPosed 服务"
                            !hasSystemScope -> "尚未启用 system 作用域"
                            !hasSystemUiScope -> "尚未启用 SystemUI 作用域"
                            !systemServerLoaded -> "模块未加载到 system_server"
                            !systemUiLoaded -> "模块未加载到 SystemUI"
                            else -> "模块已连接"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("API 102 · 双进程 · $selectedCount 个应用", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = connected)
            }
            Text(
                "亮屏解锁时首次通知可顶部弹出，窗口内后续通知仍全部保留在通知中心。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FeatureCard(
    headsUpEnabled: Boolean,
    soundEnabled: Boolean,
    connected: Boolean,
    onHeadsUpEnabledChange: (Boolean) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureSwitch(
                title = "限制顶部通知",
                description = "仅在亮屏且已解锁时生效，不影响通知中心",
                checked = headsUpEnabled,
                enabled = connected,
                onCheckedChange = onHeadsUpEnabledChange,
            )
            FeatureSwitch(
                title = "限制声音和振动",
                description = "窗口内后续通知移除系统通知声音和振动",
                checked = soundEnabled,
                enabled = connected,
                onCheckedChange = onSoundEnabledChange,
            )
        }
    }
}

@Composable
private fun FeatureSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    duration: Long?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        onClick = { if (duration != null && enabled) onEdit() },
        shape = RoundedCornerShape(20.dp),
        color = if (duration != null) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DrawableImage(app.icon, app.label)
            Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (duration != null) Text(Config.formatDuration(duration), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Switch(checked = duration != null, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

@Composable
private fun DrawableImage(drawable: Drawable, description: String) {
    val painter = remember(drawable) { BitmapPainter(drawable.toBitmap(96, 96).asImageBitmap()) }
    Image(painter, description, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class DurationUnit(val label: String, val multiplier: Long) {
    SECOND("秒", 1_000L), MINUTE("分钟", 60_000L), HOUR("小时", 3_600_000L)
}

@Composable
private fun DurationDialog(appName: String, initialMs: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val initialUnit = when {
        initialMs % 3_600_000L == 0L -> DurationUnit.HOUR
        initialMs % 60_000L == 0L -> DurationUnit.MINUTE
        else -> DurationUnit.SECOND
    }
    var unit by remember { mutableStateOf(initialUnit) }
    var value by remember { mutableStateOf((initialMs / initialUnit.multiplier).toString()) }
    val millis = value.toLongOrNull()?.let { number ->
        if (number > 0 && number <= Long.MAX_VALUE / unit.multiplier) number * unit.multiplier else null
    }
    val valid = millis != null && millis in Config.MIN_DURATION_MS..Config.MAX_DURATION_MS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置静音窗口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(appName, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    label = { Text("时长") },
                    supportingText = { Text("允许 1 秒至 24 小时") },
                    isError = value.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DurationUnit.entries.forEach { candidate ->
                        TextButton(onClick = { unit = candidate }) {
                            Text(if (unit == candidate) "[${candidate.label}]" else candidate.label)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(30_000L, 60_000L, 300_000L, 600_000L).forEach { preset ->
                        TextButton(onClick = {
                            unit = if (preset < 60_000L) DurationUnit.SECOND else DurationUnit.MINUTE
                            value = (preset / unit.multiplier).toString()
                        }) { Text(Config.formatDuration(preset)) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(millis!!) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
