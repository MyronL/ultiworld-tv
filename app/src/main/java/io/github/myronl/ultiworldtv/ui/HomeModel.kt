package io.github.myronl.ultiworldtv.ui

import android.content.SharedPreferences
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.myronl.ultiworldtv.bridge.ChallengeRequiredException
import io.github.myronl.ultiworldtv.data.Library
import io.github.myronl.ultiworldtv.data.Scores
import io.github.myronl.ultiworldtv.data.UltiworldRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * The home screen's data and scroll position. Owned by the activity, so coming back from a video
 * shows the same rows, scrolled to the same place, while they refresh in the background.
 */
class HomeModel(private val repo: UltiworldRepository, private val prefs: SharedPreferences) {
    var library by mutableStateOf<Library?>(null)
        private set
    var scores by mutableStateOf<Scores?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Whether the first scores request has finished, either way. Home waits for it so rows don't shift in later. */
    var scoresTried by mutableStateOf(false)
        private set
    var hideScores by mutableStateOf(prefs.getBoolean(KEY_HIDE_SCORES, false))
        private set

    /** The card that had focus last, so it gets focus back after watching something. */
    var focusedKey: String? = null

    val listState = LazyListState()
    private val rowStates = mutableMapOf<String, LazyListState>()

    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }

    fun toggleHideScores() {
        hideScores = !hideScores
        prefs.edit { putBoolean(KEY_HIDE_SCORES, hideScores) }
    }

    /** @throws ChallengeRequiredException so the caller can put Cloudflare's check on screen. */
    suspend fun refreshLibrary() {
        try {
            library = repo.library()
            error = null
        } catch (e: CancellationException) {
            // Leaving the home screen isn't an error.
            throw e
        } catch (e: ChallengeRequiredException) {
            throw e
        } catch (e: Exception) {
            // Keep showing what we had; only an empty screen needs the error.
            if (library == null) error = e.message ?: "Couldn't reach Ultiworld"
        }
    }

    suspend fun refreshScores() {
        try {
            scores = repo.scores()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            throw e
        } catch (e: Exception) {
            // Scores are extra; the library still works without them.
        } finally {
            scoresTried = true
        }
    }

    /** Bumped by [retry]; the home screen restarts its library loop when it changes. */
    var reloadToken by mutableIntStateOf(0)
        private set

    fun retry() {
        error = null
        reloadToken++
    }

    fun clear() {
        library = null
        scores = null
        scoresTried = false
        error = null
        focusedKey = null
    }

    private companion object {
        const val KEY_HIDE_SCORES = "hide_scores"
    }
}
