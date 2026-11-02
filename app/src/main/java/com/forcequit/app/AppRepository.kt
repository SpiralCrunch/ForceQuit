package com.forcequit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** False when Android has the app in the "stopped" state, i.e. it hasn't run since its last force stop. */
    val isActive: Boolean,
) {
    /** Android only lets you disable preinstalled apps; apps you installed can only be uninstalled. */
    val canDisable: Boolean get() = isSystem
}

/**
 * Lists apps and stores the whitelist.
 *
 * "Running" here means the app is NOT in Android's stopped state. An app enters that state when it is
 * force-stopped (or freshly installed) and leaves it as soon as it runs again. Android 13 doesn't let
 * normal apps see other apps' processes, so this is the closest reliable signal: it lists every app
 * that has had a chance to run since it was last force-stopped.
 */
class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("forcequit", Context.MODE_PRIVATE)

    fun whitelist(): Set<String> = prefs.getStringSet(KEY_WHITELIST, emptySet())!!.toSet()

    fun setWhitelisted(packageName: String, whitelisted: Boolean) = updateSet(KEY_WHITELIST, packageName, whitelisted)

    fun rememberDisabled(packageName: String) = updateSet(KEY_DISABLED, packageName, true)

    fun forgetDisabled(packageName: String) = updateSet(KEY_DISABLED, packageName, false)

    /**
     * Apps ForceQuit has disabled and that are still disabled. Apps re-enabled some other way
     * (or uninstalled) are dropped from the list.
     */
    fun disabledApps(): List<AppEntry> {
        val remembered = prefs.getStringSet(KEY_DISABLED, emptySet())!!
        val entries = remembered.mapNotNull { pkg ->
            val info = appInfo(pkg)?.takeIf { !it.enabled } ?: return@mapNotNull null
            AppEntry(pkg, info.loadLabel(pm).toString(), isSystem = true, isActive = false)
        }
        if (entries.size != remembered.size) {
            prefs.edit().putStringSet(KEY_DISABLED, entries.map { it.packageName }.toSet()).apply()
        }
        return entries.sortedBy { it.label.lowercase() }
    }

    /** The whitelist and the disabled-apps list as a JSON backup file. */
    fun exportSettings(): String = JSONObject()
        .put("app", "ForceQuit")
        .put("format", 1)
        .put(KEY_WHITELIST, JSONArray(whitelist().sorted()))
        .put(KEY_DISABLED, JSONArray(prefs.getStringSet(KEY_DISABLED, emptySet())!!.sorted()))
        .toString(2)

    /**
     * Merges a backup made by [exportSettings] into the current settings (nothing is removed).
     * Returns how many whitelist and disabled entries the file had. Throws if it isn't a ForceQuit backup.
     */
    fun importSettings(json: String): Pair<Int, Int> {
        val root = JSONObject(json)
        require(root.optString("app") == "ForceQuit") { "Not a ForceQuit settings file" }
        fun read(key: String): Set<String> {
            val array = root.optJSONArray(key) ?: return emptySet()
            return (0 until array.length()).map { array.getString(it) }.toSet()
        }
        val whitelist = read(KEY_WHITELIST)
        val disabled = read(KEY_DISABLED)
        prefs.edit()
            .putStringSet(KEY_WHITELIST, whitelist() + whitelist)
            .putStringSet(KEY_DISABLED, prefs.getStringSet(KEY_DISABLED, emptySet())!! + disabled)
            .apply()
        return whitelist.size to disabled.size
    }

    private fun updateSet(key: String, packageName: String, add: Boolean) {
        val updated = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (add) updated += packageName else updated -= packageName
        prefs.edit().putStringSet(key, updated).apply()
    }

    /** Every app with a launcher icon, minus the protected ones. Sorted by name. */
    fun installedApps(): List<AppEntry> {
        val protected = protectedPackages()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName !in protected && it.flags and ApplicationInfo.FLAG_PERSISTENT == 0 }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    isActive = info.flags and ApplicationInfo.FLAG_STOPPED == 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun isStopped(packageName: String): Boolean =
        appInfo(packageName)?.let { it.flags and ApplicationInfo.FLAG_STOPPED != 0 } ?: true

    fun isEnabled(packageName: String): Boolean = appInfo(packageName)?.enabled ?: false

    fun label(packageName: String): String = appInfo(packageName)?.loadLabel(pm)?.toString() ?: packageName

    /** Also finds disabled apps. */
    fun appInfo(packageName: String): ApplicationInfo? = try {
        pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Apps that must never be force-stopped, whitelist or not. */
    private fun protectedPackages(): Set<String> {
        val set = mutableSetOf(
            context.packageName,
            SETTINGS_PACKAGE, // we drive the Settings app to do the force stops
            "com.android.systemui",
        )
        // Current home screen.
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName?.let { set += it }
        // Current keyboard.
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
            ?.let { set += it }
        return set
    }

    companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_DISABLED = "disabled_by_us"
    }
}
