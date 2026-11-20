package com.hyperos.xj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.hyperos.xj.runtime.AppVisibility
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hyperos.xj.runtime.AndroidBackgroundPicker
import com.hyperos.xj.runtime.AndroidDirectoryPicker
import com.hyperos.xj.runtime.AndroidLaunchPermissions
import com.hyperos.xj.runtime.AndroidProblemImages

class MainActivity : ComponentActivity() {
    private var resumed = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        AppVisibility.visible.value = resumed &&
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateVisibility()
    }

    override fun onPause() {
        resumed = false
        updateVisibility()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 页面内容要能滑到系统栏后面，必须关掉 decorFitsSystemWindows；深色背景配浅色系统图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        AndroidBackgroundPicker.register(this)
        AndroidDirectoryPicker.register(this)
        AndroidLaunchPermissions.register(this)
        AndroidProblemImages.register(this)
        setContent {
            App()
        }
    }
}
