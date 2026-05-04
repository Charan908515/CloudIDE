package com.cloudide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cloudide.android.ui.navigation.CloudIdeNavHost
import com.cloudide.android.ui.theme.CloudIdeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext as CloudIdeApp
        setContent {
            CloudIdeTheme {
                CloudIdeNavHost(app = app)
            }
        }
    }
}
