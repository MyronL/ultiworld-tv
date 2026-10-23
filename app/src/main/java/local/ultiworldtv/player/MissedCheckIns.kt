package local.ultiworldtv.player

/**
 * Counts device-limit check-ins that didn't go through, in a row. The site only enforces its device limit
 * through these check-ins, so a TV that can't send them must not keep playing as if it had: one or two
 * misses are a network blip, [limit] in a row means the limit isn't being checked at all.
 */
internal class MissedCheckIns(private val limit: Int = 3) {
    private var inARow = 0

    /** Records one check-in and returns whether playback should stop because too many in a row failed. */
    fun record(checkedIn: Boolean): Boolean {
        inARow = if (checkedIn) 0 else inARow + 1
        return inARow >= limit
    }
}
