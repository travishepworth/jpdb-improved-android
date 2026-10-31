package dev.travmonkey.jpdb

import android.app.Application
import android.util.Log
import android.webkit.WebSettings
import dev.travmonkey.jpdb.data.FlushResult
import dev.travmonkey.jpdb.data.JpdbApi
import dev.travmonkey.jpdb.data.JpdbDb
import dev.travmonkey.jpdb.data.JpdbRepository
import dev.travmonkey.jpdb.data.PrefsConfigStore
import dev.travmonkey.jpdb.data.RoomStore
import dev.travmonkey.jpdb.net.Connectivity
import dev.travmonkey.jpdb.net.WebViewCookieJar
import dev.travmonkey.jpdb.work.OutboxWorker
import dev.travmonkey.jpdb.work.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var configStore: PrefsConfigStore
    lateinit var connectivity: Connectivity
    lateinit var repo: JpdbRepository
    // A failed background job must never take the whole app down with it.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("jpdb", "background job failed", e) })

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        configStore = PrefsConfigStore(this)
        connectivity = Connectivity(this)
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
        val client = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .retryOnConnectionFailure(false) // never silently re-send a review POST; the outbox handles retries
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (userAgent == null) req else req.newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
        repo = JpdbRepository(
            api = JpdbApi(client),
            store = RoomStore(JpdbDb.open(this)),
            settings = configStore,
            isOnline = { connectivity.online.value },
            log = { Log.i("jpdb", it) },
        )
        Notifications.createChannel(this)
        SyncWorker.schedule(this, configStore.config.value.syncHours)

        scope.launch { for (k in kicks) flushLoop() }
        scope.launch { connectivity.online.collect { if (it) kickFlush() } }
    }

    /** Upload pending grades now (in-process, with backoff), with a WorkManager job as backstop if we get killed. */
    fun kickFlush() {
        kicks.trySend(Unit)
        if (repo.state.value.outbox > 0) OutboxWorker.enqueue(this)
    }

    /** Refresh the due queue if it's older than [staleMs] and we're online. */
    fun maybeSync(staleMs: Long, force: Boolean = false) {
        val s = repo.state.value
        if (!connectivity.online.value || s.syncing) return
        if (!force && System.currentTimeMillis() - s.lastSync < staleMs) return
        scope.launch { runCatching { repo.sync() }.onFailure { Log.w("jpdb", "sync failed", it) } }
    }

    private suspend fun flushLoop() {
        var backoff = 2000L
        while (true) {
            val result = try {
                repo.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("jpdb", "flush failed", e)
                FlushResult.Retry
            }
            when (result) {
                FlushResult.Done, FlushResult.Offline, FlushResult.Auth -> return
                FlushResult.Retry -> {
                    // a new kick (e.g. network came back) retries right away
                    withTimeoutOrNull(backoff) { kicks.receive() }
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        }
    }
}
