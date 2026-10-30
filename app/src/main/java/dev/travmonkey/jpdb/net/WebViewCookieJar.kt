package dev.travmonkey.jpdb.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Shares the WebView's jpdb login with OkHttp, so background uploads use the same session. */
class WebViewCookieJar : CookieJar {
    private val cm get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) cm.setCookie(url.toString(), c.toString())
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cm.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
