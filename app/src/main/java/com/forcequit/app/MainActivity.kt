package com.forcequit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ForceQuitScreen()
            }
        }
    }
}

@Composable
private fun ForceQuitScreen() {
    val context = LocalContext.current
    val repo = remember { AppRepository(context) }
    val scope = rememberCoroutineScope()
    val runState by ForceStopController.state.collectAsState()

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var disabled by remember { mutableStateOf(emptyList<AppEntry>()) }
    var confirmDisable by remember { mutableStateOf<AppEntry?>(null) }
    var whitelist by remember { mutableStateOf(repo.whitelist()) }
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    // Apps the last run force stopped, so a rescan can flag any that started up again.
    var lastStopped by remember { mutableStateOf(emptySet<String>()) }

    fun refresh(onDone: (List<AppEntry>) -> Unit = {}) {
        serviceEnabled = isServiceEnabled(context)
        scope.launch {
            scanning = true
            val loaded = withContext(Dispatchers.IO) { repo.installedApps() }
            disabled = withContext(Dispatchers.IO) { repo.disabledApps() }
            apps = loaded
            scanning = false
            onDone(loaded)
        }
    }

    fun rescan() = refresh { loaded ->
        val running = loaded.filter { it.isActive && it.packageName !in whitelist }
        val reopened = running.count { it.packageName in lastStopped }
        val message = when {
            running.isEmpty() -> "Rescanned: nothing running"
            reopened > 0 -> "Rescanned: ${running.size} running, $reopened reopened after force quit"
            else -> "Rescanned: ${running.size} running"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun toggle(pkg: String, keep: Boolean) {
        repo.setWhitelisted(pkg, keep)
        whitelist = repo.whitelist()
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(repo.exportSettings().toByteArray()) }
                }
            }
            toast(if (result.isSuccess) "Settings exported" else "Export failed: ${result.exceptionOrNull()?.message}")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    repo.importSettings(json)
                }
            }
            result.onSuccess { (kept, disabledCount) ->
                whitelist = repo.whitelist()
                refresh()
                toast("Imported $kept whitelisted and $disabledCount disabled app(s)")
            }.onFailure { toast("Import failed: ${it.message}") }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(runState) {
        val finished = runState as? RunState.Finished ?: return@LaunchedEffect
        if (finished.action == Action.FORCE_STOP) {
            lastStopped = finished.results.filter { it.outcome.succeeded }.map { it.packageName }.toSet()
        }
        refresh()
    }

    val toQuit = apps.orEmpty().filter { it.isActive && it.packageName !in whitelist }
    val busy = runState is RunState.Running

    fun run(packages: List<String>, action: Action) {
        if (!ForceStopController.start(packages, action)) refresh()
    }

    confirmDisable?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmDisable = null },
            title = { Text("Disable ${app.label}?") },
            text = {
                Text(
                    "It will be stopped and won't be able to run again, and its icon will disappear, " +
                        "until you turn it back on from the Disabled tab."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = null
                    run(listOf(app.packageName), Action.DISABLE)
                }) { Text("Disable") }
            },
            dismissButton = { TextButton(onClick = { confirmDisable = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        bottomBar = {
            if (tab == 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .height(56.dp),
                ) {
                    OutlinedButton(
                        onClick = ::rescan,
                        enabled = !scanning && !busy,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Text(if (scanning) "Scanning…" else "Rescan", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = { run(toQuit.map { it.packageName }, Action.FORCE_STOP) },
                        enabled = serviceEnabled && toQuit.isNotEmpty() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Text(
                            if (toQuit.isEmpty()) "Nothing to quit" else "Force quit ${toQuit.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
            ) {
                Text("ForceQuit", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { exportLauncher.launch("forcequit-settings.json") }) { Text("Export") }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text("Import")
                }
            }
            if (!serviceEnabled) SetupCard()
            (runState as? RunState.Finished)?.let { ResultsCard(it) }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("To quit (${toQuit.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Whitelist (${whitelist.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Disabled (${disabled.size})") })
            }

            when {
                apps == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
                tab == 0 -> RunningList(
                    toQuit,
                    lastStopped,
                    canAct = serviceEnabled && !busy,
                    onKeep = { toggle(it, true) },
                    onDisable = { confirmDisable = it },
                )
                tab == 1 -> WhitelistEditor(apps!!, whitelist, ::toggle)
                else -> DisabledList(disabled, canAct = serviceEnabled && !busy) {
                    run(listOf(it.packageName), Action.ENABLE)
                }
            }
        }
    }
}

@Composable
private fun RunningList(
    toQuit: List<AppEntry>,
    lastStopped: Set<String>,
    canAct: Boolean,
    onKeep: (String) -> Unit,
    onDisable: (AppEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Running apps that aren't whitelisted. Switch on \"Keep\" to move one to the whitelist. " +
                    "Preinstalled apps that keep coming back can be disabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(toQuit, key = { it.packageName }) { app ->
            val note = if (app.packageName in lastStopped) "Reopened after force quit" else null
            AppRow(app, note = note) {
                if (app.canDisable) {
                    TextButton(onClick = { onDisable(app) }, enabled = canAct) { Text("Disable") }
                }
                Switch(checked = false, onCheckedChange = { onKeep(app.packageName) })
            }
        }
    }
}

@Composable
private fun DisabledList(disabled: List<AppEntry>, canAct: Boolean, onEnable: (AppEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                if (disabled.isEmpty()) "No apps disabled by ForceQuit."
                else "Apps ForceQuit has disabled. They can't run until you enable them again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(disabled, key = { it.packageName }) { app ->
            AppRow(app) {
                OutlinedButton(onClick = { onEnable(app) }, enabled = canAct) { Text("Enable") }
            }
        }
    }
}

@Composable
private fun WhitelistEditor(apps: List<AppEntry>, whitelist: Set<String>, onToggle: (String, Boolean) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedByDescending { it.packageName in whitelist }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                val checked = app.packageName in whitelist
                AppRow(app, onClick = { onToggle(app.packageName, !checked) }) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName, it) })
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    onClick: (() -> Unit)? = null,
    note: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName + if (app.isSystem) " · system" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        trailing()
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                    .loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Turn on the accessibility service", fontWeight = FontWeight.Bold)
            Text(
                "ForceQuit presses \"Force stop\" in Settings for you, which needs accessibility access. " +
                    "Settings → Accessibility → Downloaded apps → ForceQuit → On.\n\n" +
                    "If the switch is greyed out (\"Restricted setting\"): open App info for ForceQuit, " +
                    "tap ⋮ in the top corner → \"Allow restricted settings\", then try again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Accessibility") }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("App info") }
            }
        }
    }
}

@Composable
private fun ResultsCard(finished: RunState.Finished) {
    val (succeeded, failed) = finished.results.partition { it.outcome.succeeded }
    val verb = finished.action.pastTense
    val button = when (finished.action) {
        Action.FORCE_STOP -> "Force stop"
        Action.DISABLE -> "Disable"
        Action.ENABLE -> "Enable"
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                (if (finished.cancelled) "Cancelled. " else "Done. ") + when {
                    finished.action == Action.FORCE_STOP -> "${succeeded.size} app(s) $verb."
                    succeeded.size == 1 -> "${succeeded[0].label} $verb."
                    else -> "${succeeded.size} app(s) $verb."
                },
                fontWeight = FontWeight.Bold,
            )
            failed.forEach { r ->
                Text(
                    "${r.label}: " + when (r.outcome) {
                        Outcome.BUTTON_NOT_FOUND -> "couldn't find the $button button"
                        Outcome.BUTTON_GREYED_OUT -> "the $button button is greyed out, so Android won't allow it"
                        Outcome.CONFIRM_NOT_FOUND -> "couldn't find the confirm button"
                        else -> "pressed $button but Android says it didn't take effect"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { ForceStopController.dismissResults() }) { Text("Dismiss") }
        }
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    if (ForceStopService.instance != null) return true
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(context, ForceStopService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}
