package dev.travmonkey.jpdb.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiError(val code: String, message: String? = null) : Exception(message ?: code)
class AuthError(message: String) : Exception(message)
class HttpStatusError(val status: Int) : IOException("HTTP $status")

/** A fetched web page; `isLogin` is true when jpdb redirected us to the login page. */
class Page(val html: String, val isLogin: Boolean)

/**
 * Thin HTTP layer: the official Bearer-key API plus the cookie-authenticated web endpoints.
 * Cookies come from the client's CookieJar (the WebView's CookieManager in the app).
 */
class JpdbApi(
    private val client: OkHttpClient,
    private val base: String = "https://jpdb.io",
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun call(path: String, key: String, body: JsonObject): JsonObject {
        if (key.isBlank()) throw ApiError("missing_key", "Set your jpdb API key first (bottom of jpdb.io/settings).")
        var attempt = 0
        while (true) {
            val req = Request.Builder()
                .url("$base/api/v1/$path")
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val (code, text) = execute(req)
            if (code == 429 && attempt < 5) {
                sleep(1000L shl attempt); attempt++; continue
            }
            val data = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val err = (data["error"] as? JsonPrimitive)?.contentOrNull
            if (code !in 200..299 || err != null) {
                throw ApiError(err ?: "http_$code", (data["error_message"] as? JsonPrimitive)?.contentOrNull)
            }
            return data
        }
    }

    suspend fun getPage(path: String): Page = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(base + path).build()).execute().use { res ->
            if (res.request.url.encodedPath.startsWith("/login")) return@use Page("", isLogin = true)
            if (res.code >= 400) throw HttpStatusError(res.code)
            Page(res.body?.string().orEmpty(), isLogin = false)
        }
    }

    suspend fun getReviewPage(vid: Long, sid: Long) = getPage("/review?c=vf%2C$vid%2C$sid")

    suspend fun postReview(vid: Long, sid: Long, r: Int, g: String) {
        val body = FormBody.Builder().add("c", "vf,$vid,$sid").add("r", r.toString()).add("g", g).build()
        val (code, _) = execute(Request.Builder().url("$base/review").post(body).build())
        if (code >= 400) throw HttpStatusError(code)
    }

    private suspend fun execute(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
