package io.github.myronl.ultiworldtv.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.myronl.ultiworldtv.data.LoginResult
import io.github.myronl.ultiworldtv.data.UltiworldRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One login at a time, whether it was typed on the TV or sent from a phone, and what the screen should
 * say about it.
 */
class LoginModel(private val repo: UltiworldRepository) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Set when the last login came from the phone, so the TV can say so. */
    var fromPhone by mutableStateOf(false)
        private set

    private val lock = Mutex()

    suspend fun logIn(username: String, password: String, viaPhone: Boolean): LoginResult {
        if (lock.isLocked) return LoginResult.Failed("The TV is already logging in. Try again in a moment.")
        return lock.withLock {
            busy = true
            fromPhone = viaPhone
            error = null
            try {
                repo.logIn(username, password).also { if (it is LoginResult.Failed) error = it.message }
            } finally {
                busy = false
            }
        }
    }

    fun reset() {
        error = null
        fromPhone = false
    }
}
