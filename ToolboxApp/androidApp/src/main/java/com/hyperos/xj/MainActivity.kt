package com.hyperos.xj

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
    override fun onCreate(savedInstanceState: Bundle?) {
        // 页面内容要能滑到系统栏后面，必须关掉 decorFitsSystemWindows；深色背景配浅色系统图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        AndroidBackgroundPicker.register(this)
        AndroidDirectoryPicker.register(this)
        AndroidLaunchPermissions.register(this)
        AndroidProblemImages.register(this)
        setContent {
            App()
        }
    }
}
