package dev.travmonkey.jpdb

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.travmonkey.jpdb.ui.JPDB_HOME
import dev.travmonkey.jpdb.ui.JpdbTheme
import dev.travmonkey.jpdb.ui.MainViewModel
import dev.travmonkey.jpdb.ui.ReviewScreen
import dev.travmonkey.jpdb.ui.Screen
import dev.travmonkey.jpdb.ui.SettingsScreen
import dev.travmonkey.jpdb.ui.WebScreen
import dev.travmonkey.jpdb.ui.createJpdbWebView
import dev.travmonkey.jpdb.ui.loadIntentUrl

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var webView: WebView

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        webView = createJpdbWebView(this, vm)
        val restored = savedInstanceState?.getBundle(WEB_STATE)?.let { webView.restoreState(it) } != null
        if (!webView.loadIntentUrl(intent) && !restored) webView.loadUrl(JPDB_HOME)

        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            JpdbTheme {
                val screen by vm.screen.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    // The website stays composed (and keeps loading) underneath the offline screens.
                    WebScreen(vm, webView)
                    if (screen != Screen.Web) {
                        Surface(Modifier.fillMaxSize()) {
                            when (screen) {
                                Screen.Review -> ReviewScreen(vm)
                                Screen.Settings -> SettingsScreen(vm)
                                Screen.Web -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (webView.loadIntentUrl(intent)) vm.openWeb()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        vm.onForeground()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(WEB_STATE, Bundle().also { webView.saveState(it) })
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val WEB_STATE = "web"
    }
}
