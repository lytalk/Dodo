package com.lytalk.dodo.miui

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.lytalk.dodo.a11y.AutoAccessibilityService

object SystemPermissions {
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutoAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun exactAlarmAllowed(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun overlayAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ignoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun openAccessibility(context: Context) = start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openExactAlarm(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}")),
    )

    fun openOverlay(context: Context) = start(
        context,
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ),
    )

    fun openAppDetails(context: Context) = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }
}

object MiuiIntents {
    fun openAutostart(context: Context) {
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        if (!tryStart(context, intent)) SystemPermissions.openAppDetails(context)
    }

    fun openPermissionEditor(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", context.packageName)
        if (tryStart(context, editor)) return
        val fallback = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ),
        ).putExtra("extra_pkgname", context.packageName)
        if (!tryStart(context, fallback)) SystemPermissions.openAppDetails(context)
    }

    fun openBattery(context: Context) {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val hidden = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        )
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", label)
        if (tryStart(context, hidden)) return
        val standard = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (!tryStart(context, standard)) SystemPermissions.openAppDetails(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
