package com.forcequit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

/**
 * A name without the invisible text-direction marks some apps pad it with. Google's Android Developer
 * Verifier has over a hundred, which push its name off to one side and get it cut short.
 */
fun cleanLabel(label: CharSequence): String = label.toString().trim { it.isWhitespace() || it in DIRECTION_MARKS }

private const val DIRECTION_MARKS = "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** False when the app is disabled (by ForceQuit, in Settings or any other way), so it can't run at all. */
    val isEnabled: Boolean,
    /**
     * False when Android has the app in the "stopped" state, i.e. it hasn't run since its last force stop,
     * and always false for a disabled app.
     */
    val isActive: Boolean,
    /**
     * Shows an icon in the app drawer. Apps without one are background services (system services, phone
     * maker and Google components, plugins): they're listed under Services, and Force quit leaves them alone.
     */
    val hasIcon: Boolean,
) {
    /**
     * Settings only has Disable and Enable buttons for preinstalled apps; apps you installed can only be
     * uninstalled.
     */
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

    /**
     * The apps Settings → Apps lists (with "Show system" off): every app with a launcher icon, even a
     * hidden one, every app you installed, and every preinstalled app that has been updated. Disabled apps
     * are included with [AppEntry.isEnabled] false, and so is any preinstalled system service a user has
     * disabled. Protected apps are left out, and so are apps Settings has no buttons for. Sorted by name.
     *
     * Also forgets apps ForceQuit disabled that have since been re-enabled some other way, or uninstalled.
     */
    fun installedApps(): List<AppEntry> {
        val protected = protectedPackages()
        val remembered = prefs.getStringSet(KEY_DISABLED, emptySet())!!
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // MATCH_DISABLED_COMPONENTS: without it, disabled apps and apps that hide their icon aren't returned.
        val withIcon = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapTo(HashSet()) { it.activityInfo.packageName }
        // Just the icons the app drawer actually shows, including deep-sleeping apps'.
        val withVisibleIcon = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)
            .mapTo(HashSet()) { it.activityInfo.packageName }

        fun isListed(info: ApplicationInfo): Boolean {
            if (info.packageName in protected || info.flags and ApplicationInfo.FLAG_PERSISTENT != 0) return false
            if (isInSystemModule(info)) return false
            // What Settings → Apps shows. Anything else preinstalled is a system service.
            val inSettingsList = info.packageName in withIcon ||
                info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (info.enabled) return inSettingsList
            // Disabled apps are listed only if someone disabled them, not if they ship disabled (like the
            // Linux Terminal, which has no Enable button in Settings).
            if (info.packageName in remembered) return true
            return when (enabledSetting(info.packageName)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true // Settings, `pm disable-user`, debloat tools
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> inSettingsList // the phone maker, or a root tool
                else -> false
            }
        }

        val collator = Collator.getInstance() // sorts "Éclair" next to "Eclipse", not after "Zoom"
        val apps = pm.getInstalledApplications(MATCH_FLAGS)
            .filter(::isListed)
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = cleanLabel(info.loadLabel(pm)),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    isEnabled = info.enabled,
                    isActive = info.enabled && info.flags and ApplicationInfo.FLAG_STOPPED == 0,
                    hasIcon = info.packageName in withVisibleIcon,
                )
            }
            .sortedWith(compareBy(collator) { it.label })

        val stillDisabled = remembered intersect apps.filter { !it.isEnabled }.mapTo(HashSet()) { it.packageName }
        if (stillDisabled.size != remembered.size) prefs.edit().putStringSet(KEY_DISABLED, stillDisabled).apply()
        return apps
    }

    /**
     * Part of an Android system module, i.e. inside a module's APEX, like Wireless Emergency Alerts.
     * Settings shows no Force stop, Disable or Enable button for these, so ForceQuit can't act on them.
     */
    private fun isInSystemModule(info: ApplicationInfo): Boolean {
        if (info.sourceDir?.startsWith("/apex/") != true) return false
        // Android 14 and older have no API to tell which APEX an app is in; Settings treats them all as modules.
        if (Build.VERSION.SDK_INT < 35) return true
        // Not every APEX is a module: Google Play services can live in one and still has a Force stop button.
        val apex = try {
            pm.getPackageInfo(info.packageName, 0).apexPackageName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return false
        return try {
            pm.getModuleInfo(apex, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** One of PackageManager.COMPONENT_ENABLED_STATE_*, or -1 if the app is gone. */
    private fun enabledSetting(packageName: String): Int = try {
        pm.getApplicationEnabledSetting(packageName)
    } catch (e: IllegalArgumentException) {
        -1
    }

    fun isStopped(packageName: String): Boolean =
        appInfo(packageName)?.let { it.flags and ApplicationInfo.FLAG_STOPPED != 0 } ?: true

    fun isEnabled(packageName: String): Boolean = appInfo(packageName)?.enabled ?: false

    fun label(packageName: String): String = appInfo(packageName)?.loadLabel(pm)?.let(::cleanLabel) ?: packageName

    /** Also finds disabled apps. */
    fun appInfo(packageName: String): ApplicationInfo? = try {
        pm.getApplicationInfo(packageName, MATCH_FLAGS)
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

        /**
         * Finds disabled apps too, and counts "disabled until used" apps as enabled: that's how Samsung puts
         * unused apps into deep sleep, and they are ordinary apps that run again as soon as you open them.
         */
        private const val MATCH_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_DISABLED = "disabled_by_us"
    }
}
