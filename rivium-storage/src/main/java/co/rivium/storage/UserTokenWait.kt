package co.rivium.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Holds file requests while there is no user token ("awaiting user token").
 *
 * Every file request needs a signed Rivium user token. When the SDK has none
 * and the provider (if any) returns null - no one is signed in - the request
 * is not sent and does not fail: it waits here until the token state changes
 * ([wake]: `setUserToken`, `refreshUserToken`, a new provider, or a token the
 * provider handed out), then asks again. Waiting calls go on in the order they
 * started waiting.
 *
 * [onAwaiting] runs once each time the SDK starts waiting (no call was waiting
 * before). With [timeoutMillis], a call that waited that long fails with
 * `token_required`.
 */
internal class UserTokenWait(
    private val timeoutMillis: Long?,
    /** The token to send now, or null when none can be had. May block; must be main-safe. */
    private val fetch: suspend () -> String?
) {
    private val lock = Any()
    private var generation = 0L
    private val parked = ArrayList<CompletableDeferred<Unit>>()
    private var waiting = 0

    @Volatile
    var onAwaiting: (() -> Unit)? = null

    /** True while at least one call is waiting for a user token. */
    val isAwaiting: Boolean get() = synchronized(lock) { waiting > 0 }

    /** Changes each time the token state may have changed. */
    fun generation(): Long = synchronized(lock) { generation }

    /** The token state changed: every waiting call asks again, in the order it started waiting. */
    fun wake() {
        val toWake: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            generation++
            toWake = parked.toList()
            parked.clear()
        }
        toWake.forEach { it.complete(Unit) }
    }

    /**
     * A token to send. Without one, waits (see the class comment).
     *
     * @param changedSince when set, first wait until the token state changed
     *   after this [generation] (the server answered `token_required` to a
     *   request sent at that point).
     */
    suspend fun token(changedSince: Long? = null): String {
        var seen = changedSince ?: generation()
        if (changedSince == null) fetch()?.let { return it }

        enter()
        try {
            val waitLoop: suspend () -> String = {
                var result: String? = null
                while (result == null) {
                    val signal = CompletableDeferred<Unit>()
                    val park = synchronized(lock) {
                        if (generation == seen) {
                            parked.add(signal)
                            true
                        } else {
                            false
                        }
                    }
                    if (park) {
                        try {
                            signal.await()
                        } finally {
                            synchronized(lock) { parked.remove(signal) }
                        }
                    }
                    seen = generation()
                    result = fetch()
                }
                result
            }
            val limit = timeoutMillis ?: return waitLoop()
            return try {
                withTimeout(limit) { waitLoop() }
            } catch (e: TimeoutCancellationException) {
                throw RiviumStorageException.UserTokenRequiredException(
                    "No Rivium user token after waiting ${limit} ms (userTokenWaitTimeout). File requests need a " +
                        "signed-in user: call setUserToken(token) after sign-in, or give RiviumStorage a " +
                        "userTokenProvider and call refreshUserToken() when the user signs in."
                )
            }
        } finally {
            leave()
        }
    }

    private fun enter() {
        val first = synchronized(lock) { ++waiting == 1 }
        if (first) {
            try {
                onAwaiting?.invoke()
            } catch (_: Exception) {
                // An app callback must not break the request.
            }
        }
    }

    private fun leave() {
        synchronized(lock) { waiting-- }
    }
}
