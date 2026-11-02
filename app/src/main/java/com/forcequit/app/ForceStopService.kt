package com.forcequit.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Force-stops, disables or enables apps by opening each one's App info page in Settings and
 * pressing the button (then the confirm dialog), the same way you would by hand.
 * Idle until [start] is called.
 */
class ForceStopService : AccessibilityService() {

    private val scope = MainScope()
    private var job: Job? = null
    private var overlay: View? = null
    private var overlayText: TextView? = null
    private lateinit var repo: AppRepository

    override fun onServiceConnected() {
        repo = AppRepository(this)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // We poll the window tree instead of reacting to events; it's simpler and copes with
    // Settings pages that load in several steps.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun start(packages: List<String>, action: Action) {
        if (job?.isActive == true || packages.isEmpty()) return
        job = scope.launch { runAll(packages, action) }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runAll(packages: List<String>, action: Action) {
        val results = mutableListOf<StopResult>()
        var cancelled = false
        showOverlay()
        try {
            for ((i, pkg) in packages.withIndex()) {
                val label = repo.label(pkg)
                ForceStopController.update(RunState.Running(action, i + 1, packages.size, label))
                overlayText?.text = "${action.verbing} ${i + 1} of ${packages.size}\n$label"
                val outcome = runOne(pkg, label, action)
                if (outcome.succeeded) {
                    when (action) {
                        Action.DISABLE -> repo.rememberDisabled(pkg)
                        Action.ENABLE -> repo.forgetDisabled(pkg)
                        Action.FORCE_STOP -> {}
                    }
                }
                results += StopResult(pkg, label, outcome)
            }
        } catch (e: CancellationException) {
            cancelled = true
        } finally {
            removeOverlay()
            ForceStopController.update(RunState.Finished(action, results, cancelled))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    /** Whether [action] has taken effect on [pkg], according to the package manager. */
    private fun isDone(pkg: String, action: Action): Boolean = when (action) {
        Action.FORCE_STOP -> repo.isStopped(pkg)
        Action.DISABLE -> !repo.isEnabled(pkg)
        Action.ENABLE -> repo.isEnabled(pkg)
    }

    private suspend fun runOne(pkg: String, label: String, action: Action): Outcome {
        if (isDone(pkg, action)) return Outcome.ALREADY_DONE
        val buttonLabels = buttonLabels.getValue(action)

        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
        )
        delay(PAGE_SETTLE_MS)

        // Prefer a page that shows this app's name, so we never press the button on a stale page.
        // If the name never appears (e.g. it's truncated), fall back to just the button.
        val button = waitFor(PAGE_TIMEOUT_MS) { findButton(buttonLabels)?.takeIf { pageShows(label) } }
            ?: findButton(buttonLabels)
            ?: return Outcome.BUTTON_NOT_FOUND

        if (!button.isEnabled) {
            return if (action == Action.FORCE_STOP) Outcome.ALREADY_DONE else Outcome.BUTTON_GREYED_OUT
        }
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Enabling happens straight away; force stop and disable ask "Are you sure?" first.
        if (action != Action.ENABLE) {
            val confirm = waitFor(DIALOG_TIMEOUT_MS) { findConfirmButton(confirmLabels.getValue(action), exclude = button) }
                ?: return Outcome.CONFIRM_NOT_FOUND
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        return if (waitFor(VERIFY_TIMEOUT_MS) { isDone(pkg, action).takeIf { it } } == true) {
            Outcome.DONE
        } else {
            Outcome.NOT_VERIFIED
        }
    }

    // ---- Finding things on screen ----
    // We walk the node tree ourselves rather than using findAccessibilityNodeInfosByText, because
    // that returns nothing for Settings pages built with Jetpack Compose.

    /** Settings windows, topmost first (so a dialog comes before the page under it). */
    private fun settingsRoots(): List<AccessibilityNodeInfo> =
        windows.mapNotNull { it.root }.filter { it.packageName == AppRepository.SETTINGS_PACKAGE }

    private fun findNode(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun AccessibilityNodeInfo.hasText(candidates: List<String>): Boolean {
        val t = (text ?: contentDescription)?.toString()?.trim() ?: return false
        return candidates.any { t.equals(it, ignoreCase = true) }
    }

    private fun findButton(labels: List<String>): AccessibilityNodeInfo? =
        settingsRoots().firstNotNullOfOrNull { root -> findNode(root) { it.hasText(labels) } }
            ?.let(::clickable)

    /** The positive button of the confirm dialog, which may repeat the button's own wording on some phones. */
    private fun findConfirmButton(labels: List<String>, exclude: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        settingsRoots().firstNotNullOfOrNull { root ->
            findNode(root) { it.viewIdResourceName == "android:id/button1" }
                ?: findNode(root) { it.hasText(labels) && clickable(it) != exclude }
        }?.let(::clickable)

    private fun pageShows(label: String): Boolean =
        settingsRoots().any { root -> findNode(root) { it.hasText(listOf(label)) } != null }

    /** Labels are often a text node inside a clickable container; click the container. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return current!!
            current = current?.parent
        }
        return node
    }

    private fun settingsString(name: String): String? = try {
        val res = packageManager.getResourcesForApplication(AppRepository.SETTINGS_PACKAGE)
        val id = res.getIdentifier(name, "string", AppRepository.SETTINGS_PACKAGE)
        if (id != 0) res.getString(id) else null
    } catch (e: Exception) {
        null
    }

    /** Button wording in the phone's own language, read from the Settings app, plus English fallbacks. */
    private val buttonLabels: Map<Action, List<String>> by lazy {
        mapOf(
            Action.FORCE_STOP to labels("force_stop", "Force stop", "Force close"),
            Action.DISABLE to labels("disable_text", "Disable", "Turn off"),
            Action.ENABLE to labels("enable_text", "Enable", "Turn on"),
        )
    }

    private val confirmLabels: Map<Action, List<String>> by lazy {
        val ok = listOfNotNull(settingsString("dlg_ok"), getString(android.R.string.ok), "OK")
        mapOf(
            Action.FORCE_STOP to ok + buttonLabels.getValue(Action.FORCE_STOP),
            Action.DISABLE to labels("app_disable_dlg_positive", "Disable app") + ok + buttonLabels.getValue(Action.DISABLE),
        )
    }

    private fun labels(settingsName: String, vararg fallbacks: String): List<String> =
        (listOfNotNull(settingsString(settingsName)) + fallbacks).distinct()

    private suspend fun <T> waitFor(timeoutMs: Long, find: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            find()?.let { return it }
            delay(POLL_MS)
        }
        return find()
    }

    // ---- Progress overlay ----

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val cancelButton = Button(this).apply {
            this.text = "Cancel"
            setOnClickListener { cancel() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 33, 33, 33))
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(text)
            addView(cancelButton)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        wm.addView(layout, params)
        overlay = layout
        overlayText = text
    }

    private fun removeOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        overlayText = null
    }

    companion object {
        @Volatile
        var instance: ForceStopService? = null
            private set

        private const val PAGE_SETTLE_MS = 400L
        private const val PAGE_TIMEOUT_MS = 4000L
        private const val DIALOG_TIMEOUT_MS = 3000L
        private const val VERIFY_TIMEOUT_MS = 2000L
        private const val POLL_MS = 150L
    }
}
