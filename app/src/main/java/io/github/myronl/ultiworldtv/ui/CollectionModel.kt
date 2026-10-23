package io.github.myronl.ultiworldtv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.myronl.ultiworldtv.data.VideoCard

/**
 * The cards and focused card of each collection screen on the back stack. Owned by the activity, so coming
 * back from a video shows the same cards with the same one focused, while they refresh in the background.
 */
class CollectionModel {
    class Entry {
        var cards by mutableStateOf<List<VideoCard>?>(null)
        var error by mutableStateOf<String?>(null)
        var focusedIndex = 0

        /** Kept so the focused card is still scrolled into view, and can take focus, when the screen comes back. */
        val gridState = LazyGridState()
    }

    private val entries = mutableMapOf<String, Entry>()

    fun entry(url: String): Entry = entries.getOrPut(url) { Entry() }

    /** Forgets every collection that is no longer on the back stack. */
    fun keepOnly(urls: Collection<String>) {
        entries.keys.retainAll(urls.toSet())
    }
}
