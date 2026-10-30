package dev.travmonkey.jpdb.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.travmonkey.jpdb.App
import dev.travmonkey.jpdb.data.AuthError
import dev.travmonkey.jpdb.data.Card
import dev.travmonkey.jpdb.data.Config
import dev.travmonkey.jpdb.work.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { Web, Review, Settings }

/** State of the website's current main-frame load, for the "jpdb is slow" banner. */
enum class Load { Loading, Ok, Slow, Error, Dismissed }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val repo = app.repo
    val config = app.configStore.config
    val online = app.connectivity.online

    private val _screen = MutableStateFlow(Screen.Web)
    val screen = _screen.asStateFlow()
    private val _load = MutableStateFlow(Load.Loading)
    val load = _load.asStateFlow()

    /** Key of the card whose answer is showing; tied to the card so a resync reordering the queue hides it again. */
    var revealedKey by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)
    var settingsMessage by mutableStateOf<String?>(null)

    private var settingsReturn = Screen.Web
    private var slowTimer: Job? = null

    init {
        viewModelScope.launch {
            // No network at all: skip the website and go straight to offline review.
            online.collect { on -> if (!on && _screen.value == Screen.Web) openReview() }
        }
    }

    fun openReview() {
        _screen.value = Screen.Review
        app.kickFlush()
        app.maybeSync(staleMs = 10 * 60_000L, force = repo.state.value.queue.isEmpty() && repo.state.value.lastSync == 0L)
    }

    fun openWeb() { _screen.value = Screen.Web }

    fun openSettings() {
        settingsReturn = _screen.value.takeIf { it != Screen.Settings } ?: Screen.Web
        settingsMessage = null
        _screen.value = Screen.Settings
    }

    fun closeSettings() { _screen.value = settingsReturn }

    fun onForeground() {
        app.kickFlush()
        app.maybeSync(staleMs = 10 * 60_000L)
    }

    // ---------------------------------------------------------------- website ---

    fun onPageStarted() {
        _load.value = Load.Loading
        slowTimer?.cancel()
        slowTimer = viewModelScope.launch {
            delay(config.value.slowMs)
            if (_load.value == Load.Loading) _load.value = Load.Slow
        }
    }

    fun onPageVisible() {
        slowTimer?.cancel()
        if (_load.value != Load.Error) _load.value = Load.Ok
    }

    fun onPageError() {
        slowTimer?.cancel()
        _load.value = Load.Error
    }

    fun dismissBanner() { _load.value = Load.Dismissed }

    /** Messages from assets/bridge.js. */
    fun onBridgeMessage(data: String?) {
        val msg = runCatching { Json.parseToJsonElement(data ?: return).jsonObject }.getOrNull() ?: return
        if (msg["type"]?.jsonPrimitive?.content != "reviewed") return
        val parts = msg["c"]?.jsonPrimitive?.content?.split(',') ?: return
        val vid = parts.getOrNull(1)?.toLongOrNull() ?: return
        val sid = parts.getOrNull(2)?.toLongOrNull() ?: return
        viewModelScope.launch { repo.markReviewedElsewhere(vid, sid) }
    }

    // ----------------------------------------------------------------- review ---

    fun reveal(card: Card) { revealedKey = card.key }

    fun grade(card: Card, grade: String) {
        revealedKey = null
        viewModelScope.launch {
            repo.grade(card, grade)
            app.kickFlush()
        }
    }

    fun undo() {
        viewModelScope.launch { repo.undo()?.let { revealedKey = it.key } }
    }

    fun sync(forceDecks: Boolean = false) {
        viewModelScope.launch {
            runCatching { repo.sync(forceDecks) }
            app.kickFlush()
        }
    }

    // --------------------------------------------------------------- settings ---

    fun updateConfig(f: (Config) -> Config) {
        val before = config.value.syncHours
        app.configStore.update(f)
        if (config.value.syncHours != before) SyncWorker.schedule(app, config.value.syncHours)
    }

    fun fetchApiKey() {
        settingsMessage = "Reading jpdb.io/settings…"
        viewModelScope.launch {
            settingsMessage = try {
                if (repo.fetchApiKey() != null) "Found your API key." else "Couldn't find the key on jpdb.io/settings — paste it manually."
            } catch (e: AuthError) {
                "Log in to jpdb on the website first."
            } catch (e: Exception) {
                "Failed: ${e.message ?: e}"
            }
        }
    }
}
