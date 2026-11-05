package com.shibi.orbis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shibi.orbis.ui.OrbisRoot
import com.shibi.orbis.ui.theme.OrbisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OrbisApp
        setContent {
            OrbisTheme {
                OrbisRoot(container = app.container)
            }
        }
    }
}