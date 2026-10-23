package local.ultiworldtv.ui

import android.content.SharedPreferences
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.Library
import local.ultiworldtv.data.Scores
import local.ultiworldtv.data.UltiworldRepository

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
    var hideScores by mutableStateOf(prefs.getBoolean(KEY_HIDE_SCORES, false))
        private set

    /** The card that had focus last, so it gets focus back after watching something. */
    var focusedKey: String? = null

    val listState = LazyListState()
    private val rowStates = mutableMapOf<String, LazyListState>()

    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }

    fun toggleHideScores() {
        hideScores = !hideScores
        prefs.edit().putBoolean(KEY_HIDE_SCORES, hideScores).apply()
    }

    /** @throws ChallengeRequiredException so the caller can put Cloudflare's check on screen. */
    suspend fun refreshLibrary() {
        try {
            library = repo.library()
            error = null
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
        } catch (e: ChallengeRequiredException) {
            throw e
        } catch (e: Exception) {
            // Scores are extra; the library still works without them.
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
        error = null
        focusedKey = null
    }

    private companion object {
        const val KEY_HIDE_SCORES = "hide_scores"
    }
}
