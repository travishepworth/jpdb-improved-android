package dev.travmonkey.jpdb.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.travmonkey.jpdb.data.Net

const val JPDB_HOME = "https://jpdb.io/"
private const val ORIGIN = "https://jpdb.io"

/** Builds the long-lived WebView that shows the real jpdb.io. */
@SuppressLint("SetJavaScriptEnabled")
fun createJpdbWebView(context: Context, vm: MainViewModel): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false // jpdb plays word audio
    CookieManager.getInstance().setAcceptCookie(true)

    val bridge = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
    val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (startScript) WebViewCompat.addDocumentStartJavaScript(this, bridge, setOf(ORIGIN))
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, "jpdbApp", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) vm.onBridgeMessage(message.data)
        }
    }

    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == "jpdb.io") return false
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = vm.onPageStarted()

        override fun onPageCommitVisible(view: WebView, url: String?) = vm.onPageVisible()

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush() // so background uploads see the current session
            if (!startScript && url?.startsWith(ORIGIN) == true) view.evaluateJavascript(bridge, null)
            vm.onPageVisible()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            vm.canGoBack = view.canGoBack()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) vm.onPageError()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) vm.onPageError()
        }
    }
}

fun WebView.loadIntentUrl(intent: Intent?): Boolean {
    val url = intent?.data?.takeIf { it.scheme == "https" && it.host == "jpdb.io" } ?: return false
    loadUrl(url.toString())
    return true
}

@Composable
fun WebScreen(vm: MainViewModel, webView: WebView) {
    val load by vm.load.collectAsState()
    val online by vm.online.collectAsState()
    val repo by vm.repo.state.collectAsState()

    BackHandler(enabled = vm.canGoBack) { webView.goBack() }
    // Coming back online after a failed load: try again.
    LaunchedEffect(online) { if (online && load == Load.Error) webView.reload() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val problem = load == Load.Slow || load == Load.Error || !online
        Surface(
            color = if (problem) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (problem) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (!online) Net.Offline else repo.net)
                Text(
                    when {
                        !online -> "No connection"
                        load == Load.Error -> "jpdb didn't load"
                        load == Load.Slow -> "jpdb is slow to load"
                        else -> "${repo.queue.size} ready offline" + if (repo.outbox > 0) " · ${repo.outbox} to upload" else ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (load == Load.Slow) TextButton(onClick = vm::dismissBanner) { Text("Wait") }
                TextButton(onClick = vm::openReview) { Text("⚡ Review offline (${repo.queue.size})") }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
fun StatusDot(net: Net, modifier: Modifier = Modifier) {
    val color = when (net) {
        Net.Ok -> Color(0xFF3FB950)
        Net.Offline -> MaterialTheme.colorScheme.error
        Net.Retrying -> Color(0xFFD29922)
    }
    Box(modifier.size(9.dp).background(color, CircleShape))
}
