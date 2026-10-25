package com.vitmap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitmap.app.data.AppSettings
import com.vitmap.app.nav.AppNavHost
import com.vitmap.app.nav.ToolRoute
import com.vitmap.app.ui.theme.VitmapTheme

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<ToolRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink = routeFor(intent)
        setContent {
            val settings by vitmap.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            VitmapTheme(mode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppNavHost(deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { deepLink = it }
    }

    /** Links open in AllBrowser; shared or selected text opens in the translator. */
    private fun routeFor(intent: Intent?): ToolRoute? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { ToolRoute("browser", it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                if (text.trim().startsWith("http://") || text.trim().startsWith("https://")) ToolRoute("browser", text.trim())
                else ToolRoute("translate", text)
            }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { ToolRoute("translate", it) }
            else -> null
        }
    }
}
