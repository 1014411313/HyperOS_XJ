package com.hyperos.xj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hyperos.xj.runtime.AndroidBackgroundPicker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidBackgroundPicker.register(this)
        setContent {
            App()
        }
    }
}
