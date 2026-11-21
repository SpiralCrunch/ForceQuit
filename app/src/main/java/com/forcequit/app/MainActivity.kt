package com.forcequit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    var confirmDisable by remember { mutableStateOf<AppEntry?>(null) }
    var whitelist by remember { mutableStateOf(repo.whitelist()) }
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    // Apps force stopped since ForceQuit was opened, so a rescan can flag any that started up again.
    var lastStopped by remember { mutableStateOf(emptySet<String>()) }

    fun refresh(onDone: (List<AppEntry>) -> Unit = {}) {
        serviceEnabled = isServiceEnabled(context)
        scope.launch {
            scanning = true
            val loaded = withContext(Dispatchers.IO) { repo.installedApps() }
            apps = loaded
            scanning = false
            onDone(loaded)
        }
    }

    fun rescan() = refresh { loaded ->
        val running = loaded.toQuit(whitelist)
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
            // Added to, not replaced, so stopping one service doesn't clear the notes from the last Force quit.
            lastStopped = lastStopped + finished.results.filter { it.outcome.succeeded }.map { it.packageName }
        }
        refresh()
    }

    val (enabledApps, disabled) = apps.orEmpty().partition { it.isEnabled }
    val (appsWithIcon, services) = enabledApps.partition { it.hasIcon }
    val toQuit = apps.orEmpty().toQuit(whitelist)
    // Count only whitelisted apps that are listed, not ones since uninstalled or disabled, or imported from another phone.
    val whitelistedCount = appsWithIcon.count { it.packageName in whitelist }
    val busy = runState is RunState.Running

    // One search for every tab. Each list, and the Force quit button, only ever covers the apps it shows.
    val q = query.trim()
    val searching = q.isNotEmpty()
    fun List<AppEntry>.matching() =
        if (!searching) this
        else filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    val shownToQuit = toQuit.matching()
    val shownWhitelist = appsWithIcon.matching().sortedByDescending { it.packageName in whitelist }
    val shownServices = services.matching()
    val shownDisabled = disabled.matching()

    fun run(packages: List<String>, action: Action) {
        if (!ForceStopController.start(packages, action)) refresh()
    }

    confirmDisable?.let { app ->
        // A service has no icon to lose, but other apps and phone features may quietly depend on it.
        val service = !app.hasIcon
        val warningIcon: (@Composable () -> Unit)? =
            if (service) ({ Text("⚠", style = MaterialTheme.typography.headlineMedium) }) else null
        AlertDialog(
            onDismissRequest = { confirmDisable = null },
            icon = warningIcon,
            title = { Text("Disable ${app.label}?") },
            text = {
                Text(
                    if (service) {
                        "This is a background service, not an app you open. Other apps and phone features may " +
                            "rely on it and can stop working without any warning. Only disable it if you know " +
                            "what it does. You can turn it back on from the Disabled tab."
                    } else {
                        "It will be stopped and won't be able to run again, and its icon will disappear, " +
                            "until you turn it back on from the Disabled tab."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = null
                    run(listOf(app.packageName), Action.DISABLE)
                }) {
                    if (service) Text("Disable anyway", color = MaterialTheme.colorScheme.error) else Text("Disable")
                }
            },
            dismissButton = {
                Row {
                    // The warning says to disable it only if you know what it does, so offer to find out.
                    if (service) {
                        TextButton(onClick = {
                            confirmDisable = null
                            lookUp(context, app)
                        }) { Text("Look it up") }
                    }
                    TextButton(onClick = { confirmDisable = null }) { Text("Cancel") }
                }
            },
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
                        onClick = { run(shownToQuit.map { it.packageName }, Action.FORCE_STOP) },
                        enabled = serviceEnabled && shownToQuit.isNotEmpty() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Text(
                            if (shownToQuit.isEmpty()) "Nothing to quit" else "Force quit ${shownToQuit.size}",
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

            val focusManager = LocalFocusManager.current
            val clearSearch: (@Composable () -> Unit)? =
                if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Text("✕") } }) else null
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                trailingIcon = clearSearch,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            // Four "Name (count)" labels don't fit across a phone, so each tab shows its count under its name.
            // While searching, each count is how many apps in that tab match, so you can see where an app is.
            TabRow(selectedTabIndex = tab) {
                listOf(
                    "To quit" to shownToQuit.size,
                    "Whitelist" to if (searching) shownWhitelist.size else whitelistedCount,
                    "Services" to if (searching) shownServices.size else services.count { it.isActive },
                    "Disabled" to shownDisabled.size,
                ).forEachIndexed { i, (label, count) ->
                    Tab(selected = tab == i, onClick = { tab = i }) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$count", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            when {
                apps == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
                tab == 0 -> RunningList(
                    shownToQuit,
                    lastStopped,
                    searching,
                    canAct = serviceEnabled && !busy,
                    onKeep = { toggle(it, true) },
                    onDisable = { confirmDisable = it },
                )
                tab == 1 -> WhitelistEditor(shownWhitelist, whitelist, searching, ::toggle)
                tab == 2 -> ServicesList(
                    shownServices,
                    lastStopped,
                    searching,
                    canAct = serviceEnabled && !busy,
                    onForceStop = { run(listOf(it.packageName), Action.FORCE_STOP) },
                    onDisable = { confirmDisable = it },
                )
                else -> DisabledList(shownDisabled, searching, canAct = serviceEnabled && !busy) {
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
    searching: Boolean,
    canAct: Boolean,
    onKeep: (String) -> Unit,
    onDisable: (AppEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Running apps that aren't whitelisted. Switch on \"Keep\" to move one to the whitelist, or tap its " +
                    "name to look it up. Preinstalled apps that keep coming back can be disabled. Apps with no " +
                    "icon are under Services.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (searching && toQuit.isEmpty()) item { NoMatches() }
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

/** Running apps Force quit stops: ones with an app icon that aren't whitelisted. */
private fun List<AppEntry>.toQuit(whitelist: Set<String>) =
    filter { it.hasIcon && it.isActive && it.packageName !in whitelist }

/** Apps with no app icon. Force quit leaves them alone, but each can be stopped or disabled on its own. */
@Composable
private fun ServicesList(
    services: List<AppEntry>,
    lastStopped: Set<String>,
    searching: Boolean,
    canAct: Boolean,
    onForceStop: (AppEntry) -> Unit,
    onDisable: (AppEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Apps with no app icon: system services, Google and phone maker components, and plugins. " +
                    "Force quit leaves these alone. You can stop one here, or disable a preinstalled one, " +
                    "but other apps or phone features may rely on it. Tap a name to look it up first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (searching && services.isEmpty()) item { NoMatches() }
        // Kept in name order, running or not, so a service you stop stays where it was and shows "Not running".
        items(services, key = { it.packageName }) { app ->
            val note = if (app.isActive && app.packageName in lastStopped) "Reopened after force quit" else null
            AppRow(app, note = note) {
                if (app.canDisable) {
                    TextButton(onClick = { onDisable(app) }, enabled = canAct) { Text("Disable") }
                }
                if (app.isActive) {
                    TextButton(onClick = { onForceStop(app) }, enabled = canAct) { Text("Force stop") }
                } else {
                    Text(
                        "Not running",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

/** Shown in a tab where the search matches nothing; the tab counts show where the matches are. */
@Composable
private fun NoMatches() {
    Text(
        "No apps in this tab match your search.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DisabledList(disabled: List<AppEntry>, searching: Boolean, canAct: Boolean, onEnable: (AppEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                if (disabled.isEmpty() && !searching) "No disabled apps."
                else "Disabled apps, whether ForceQuit or something else disabled them. They can't run until you enable them again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (searching && disabled.isEmpty()) item { NoMatches() }
        items(disabled, key = { it.packageName }) { app ->
            // An app you installed can only have been disabled with adb or a tool like it; Settings has no Enable button for it.
            AppRow(app, note = if (app.canDisable) null else "Can't be re-enabled from Settings") {
                if (app.canDisable) {
                    OutlinedButton(onClick = { onEnable(app) }, enabled = canAct) { Text("Enable") }
                }
            }
        }
    }
}

/** Every app with an icon, whitelisted ones first. The search box above the tabs filters it. */
@Composable
private fun WhitelistEditor(apps: List<AppEntry>, whitelist: Set<String>, searching: Boolean, onToggle: (String, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Every app with an icon. Tick the ones Force quit should never touch; they're listed first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (searching && apps.isEmpty()) item { NoMatches() }
        items(apps, key = { it.packageName }) { app ->
            // Only the checkbox toggles: tapping the name looks the app up, as in the other tabs.
            AppRow(app) {
                Checkbox(checked = app.packageName in whitelist, onCheckedChange = { onToggle(app.packageName, it) })
            }
        }
    }
}

/** Tapping the app's icon or name looks it up (see [lookUp]); [trailing] holds the row's own buttons. */
@Composable
private fun AppRow(app: AppEntry, note: String? = null, trailing: @Composable () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Look up") { lookUp(context, app) }
                .padding(vertical = 6.dp),
        ) {
            AppIcon(app.packageName)
            Spacer(Modifier.width(12.dp))
            Column {
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
        }
        trailing()
    }
}

/**
 * Opens the share sheet with a question about [app], so you can pick a browser to search it, an AI app to
 * answer it, or anything else that takes text.
 */
private fun lookUp(context: Context, app: AppEntry) {
    val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
    val ask = when {
        !app.isEnabled -> "Is it safe to keep it disabled?"
        app.canDisable -> "Is it safe to force stop or disable it?"
        else -> "Is it safe to force stop it?"
    }
    val question = "What is the Android app \"${app.label}\" (${app.packageName}) on a $maker phone? $ask"
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, question)
        .putExtra(Intent.EXTRA_TITLE, "Look up ${app.label}") // the share sheet shows this above the text
    context.startActivity(Intent.createChooser(send, "Look up ${app.label}"))
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
