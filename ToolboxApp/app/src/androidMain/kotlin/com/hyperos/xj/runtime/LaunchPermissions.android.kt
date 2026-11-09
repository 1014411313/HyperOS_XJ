package com.hyperos.xj.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/** MainActivity 持有 Activity，供权限申请与跳系统设置使用。 */
object AndroidLaunchPermissions {
    private var activity: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<String>? = null

    fun register(target: ComponentActivity) {
        activity = target
        launcher = target.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    }

    fun requestNotifications() = launcher?.launch(Manifest.permission.POST_NOTIFICATIONS)

    fun currentActivity(): ComponentActivity? = activity
}

private val appContext: Context? by lazy {
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}

actual fun notificationsGranted(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val ctx = appContext ?: return true
    return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

actual fun requestNotifications() {
    AndroidLaunchPermissions.requestNotifications()
}

actual fun batteryOptimizationIgnored(): Boolean {
    val ctx = appContext ?: return true
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

actual fun requestIgnoreBatteryOptimizations() {
    val ctx = appContext ?: return
    val action = if (batteryOptimizationIgnored()) {
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
    } else {
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    }
    runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            intent.data = Uri.parse("package:${ctx.packageName}")
        }
        (AndroidLaunchPermissions.currentActivity() ?: ctx).startActivity(intent)
    }
}

private const val LSPOSED_PACKAGE = "org.lsposed.manager"

actual fun isLsposedInstalled(): Boolean {
    val ctx = appContext ?: return false
    return runCatching { ctx.packageManager.getPackageInfo(LSPOSED_PACKAGE, 0) }.isSuccess
}

actual fun openLsposedManager(): Boolean {
    val ctx = appContext ?: return false
    val intent = ctx.packageManager.getLaunchIntentForPackage(LSPOSED_PACKAGE) ?: return false
    return runCatching {
        (AndroidLaunchPermissions.currentActivity() ?: ctx).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

private const val PREFS_NAME = "xj_launch"

actual fun loadLaunchFlags(): LaunchFlags {
    val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return LaunchFlags()
    return LaunchFlags(
        dismissed = prefs.getBoolean("dismissed", false),
        lsposedConfirmed = prefs.getBoolean("lsposed_confirmed", false),
    )
}

actual fun saveLaunchFlags(flags: LaunchFlags) {
    val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
    prefs.edit()
        .putBoolean("dismissed", flags.dismissed)
        .putBoolean("lsposed_confirmed", flags.lsposedConfirmed)
        .apply()
}

actual suspend fun writeDiagNote(text: String) {
    val ctx = appContext ?: return
    runCatching {
        val file = java.io.File(ctx.filesDir, "diag_note.txt")
        if (text.isBlank()) file.delete() else file.writeText(text)
    }
}
