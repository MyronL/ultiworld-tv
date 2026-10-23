package local.ultiworldtv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.UltiworldRepository
import local.ultiworldtv.data.VideoCard

/**
 * The search screen's query and results. Owned by the activity, so coming back from a video shows the same
 * results with the same one focused.
 */
class SearchModel(private val repo: UltiworldRepository) {
    var query by mutableStateOf("")

    /** Results for [resultsFor]; null before the first search. */
    var results by mutableStateOf<List<VideoCard>?>(null)
        private set
    var resultsFor by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The result that was opened last, so it gets focus back after watching it. */
    var focusedIndex: Int? = null

    /** @throws ChallengeRequiredException so the caller can put Cloudflare's check on screen. */
    suspend fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) {
            results = null
            resultsFor = null
            error = null
            return
        }
        if (text == resultsFor && error == null) return
        searching = true
        try {
            results = repo.search(text)
            resultsFor = text
            error = null
            focusedIndex = null
        } catch (e: CancellationException) {
            // A newer query took over.
            throw e
        } catch (e: ChallengeRequiredException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't search Ultiworld"
        } finally {
            searching = false
        }
    }

    fun clear() {
        query = ""
        results = null
        resultsFor = null
        error = null
        focusedIndex = null
    }
}
