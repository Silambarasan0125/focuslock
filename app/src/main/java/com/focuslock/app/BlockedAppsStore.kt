package com.focuslock.app

import android.content.Context
import android.content.Intent

data class SelectableApp(
    val packageName: String,
    val label: String
)

object BlockedAppsStore {

    private const val PREFERENCES_NAME = "blocked_apps_preferences"
    private const val KEY_SELECTED_PACKAGES = "selected_packages"

    val defaultPackages = linkedSetOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.reddit.frontpage"
    )

    val paymentSafePackages = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp"
    )

    fun getSelectedPackages(context: Context): Set<String> {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getStringSet(KEY_SELECTED_PACKAGES, null)?.toSet() ?: defaultPackages
    }

    fun saveSelectedPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED_PACKAGES, packages.toSet())
            .apply()
    }

    fun getAppLabel(context: Context, packageName: String): String {
        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    fun getSelectableApps(context: Context): List<SelectableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val excludedPackages = paymentSafePackages + context.packageName

        return context.packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName in excludedPackages) return@mapNotNull null

                SelectableApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(context.packageManager).toString()
                )
            }
            .distinctBy(SelectableApp::packageName)
            .sortedBy { app -> app.label.lowercase() }
            .toList()
    }
}
