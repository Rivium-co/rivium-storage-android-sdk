package co.rivium.storage

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the signed Rivium user token the SDK sends as `x-user-token`.
 *
 * Every file request carries it: bucket rules match `{userId}` against the
 * signed-in user it names. Sign users in with Rivium Auth (its token is this
 * token), or let your backend mint one (`POST https://auth.rivium.co/users/token`,
 * with the server secret), and hand it to the SDK here.
 *
 * Supply a [provider] so the SDK can fetch a token by itself: before the first
 * request, when the one it holds is close to expiry, and when the server
 * rejects it as expired or invalid. Without a provider, call [set] again when
 * you refresh.
 *
 * The same API as `userTokens` in the Rivium Sync Android SDK.
 */
class UserTokenStore internal constructor() {
    private val token = AtomicReference<String?>(null)
    private val expiresAtSeconds = AtomicReference(0L)

    /** True once the current state is known: a token was set or the provider answered (even with null). */
    @Volatile
    private var resolved = false

    /** Told after the token state changed, so file requests waiting for a token ask again. */
    @Volatile
    internal var onChange: (() -> Unit)? = null

    /**
     * Fetches a token for the signed-in user, called on a background thread.
     * Return null when no one is signed in. Concurrent requests share one call.
     */
    @Volatile
    var provider: (() -> String?)? = null
        set(value) {
            field = value
            if (value != null) invalidate()
        }

    /** Replace the current token. Pass null to stop sending one (sign out). Safe to call from any thread. */
    fun set(newToken: String?) {
        synchronized(this) {
            store(newToken)
            resolved = true
        }
        onChange?.invoke()
    }

    /**
     * For apps that use a [provider]: call this when the user signs in or out.
     * The provider is asked again before the next request.
     */
    fun refresh() {
        invalidate()
    }

    /** Forget the current token so the next request asks [provider] for another. */
    fun invalidate() {
        synchronized(this) {
            token.set(null)
            expiresAtSeconds.set(0L)
            resolved = false
        }
        onChange?.invoke()
    }

    /** The token currently held, without asking the provider. */
    val value: String? get() = token.get()

    private fun store(newToken: String?) {
        token.set(newToken)
        expiresAtSeconds.set(newToken?.let { expiryOf(it) } ?: 0L)
    }

    private fun isFresh(): Boolean {
        if (!resolved) return false
        val held = token.get() ?: return true // Signed out: nothing to refresh until refresh()/invalidate().
        val exp = expiresAtSeconds.get()
        if (exp == 0L) return held.isNotEmpty() // Unreadable expiry: rely on the server's 401.
        return exp - REFRESH_SKEW_SECONDS > System.currentTimeMillis() / 1000
    }

    /**
     * The token to send, asking [provider] when there is none yet or the held
     * one is within [REFRESH_SKEW_SECONDS] of expiry. Blocking: call off the
     * main thread.
     */
    internal fun current(): String? {
        if (isFresh()) return token.get()
        val fetch = provider ?: return token.get() // No provider: whatever was set, even if stale.

        var fetched = false
        val result = synchronized(this) {
            // Another thread may have fetched while this one waited.
            if (isFresh()) {
                token.get()
            } else {
                val latest = token.get()
                try {
                    val fresh = fetch()
                    store(fresh)
                    resolved = true
                    fetched = fresh != null
                    fresh
                } catch (e: Exception) {
                    // Let the server decide; the old token may still work.
                    latest
                }
            }
        }
        if (fetched) onChange?.invoke()
        return result
    }

    /**
     * After the server refused [rejected]: ask the provider for another token,
     * unless another thread already replaced it. Returns the token to retry
     * with, or null when there is none (or it is the same one).
     */
    internal fun renewAfterRejection(rejected: String?): String? {
        val fetch = provider ?: return null
        var fetched = false
        val result = synchronized(this) {
            val held = token.get()
            if (resolved && held != null && held != rejected) {
                held
            } else {
                try {
                    val fresh = fetch()
                    store(fresh)
                    resolved = true
                    fetched = fresh != null && fresh != rejected
                    fresh?.takeIf { it != rejected }
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (fetched) onChange?.invoke()
        return result
    }

    /** Expiry (`exp`) from the JWT payload, or 0 when it cannot be read. */
    private fun expiryOf(jwt: String): Long {
        val payload = jwt.split(".").getOrNull(1) ?: return 0L
        val decoded = base64UrlDecode(payload) ?: return 0L
        return Regex("\"exp\"\\s*:\\s*(\\d+)").find(decoded)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    internal companion object {
        /** Refresh this many seconds before the token actually expires. */
        const val REFRESH_SKEW_SECONDS = 30L

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Base64url (no padding) decode; works on minSdk 16 and in JVM unit tests. */
        fun base64UrlDecode(input: String): String? {
            val clean = input.trimEnd('=').replace('+', '-').replace('/', '_')
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in clean) {
                val v = ALPHABET.indexOf(c)
                if (v < 0) return null
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
