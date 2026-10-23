package co.rivium.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class UserTokenTest {

    private class Seen(val request: Request, val body: String)

    private val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    private fun client(answer: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            seen.add(Seen(request, body))
            val (code, json) = answer(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

    private fun error401(code: String) = 401 to """{"statusCode":401,"code":"$code","message":"$code"}"""

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"a/b.txt","fileName":"b.txt","mimeType":"text/plain",
        |"size":3,"createdAt":"2026-01-01T00:00:00.000Z",
        |"updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    @Test
    fun sendsTokenFromProviderAndNeverUserId() = runBlocking<Unit> {
        val storage = RiviumStorage.builder("rv_live_x")
            .userTokenProvider { "tok-1" }
            .client(client { 200 to "[]" })
            .build()

        storage.listBuckets()

        val headers = seen.single().request.headers
        assertEquals("tok-1", headers["x-user-token"])
        assertEquals("rv_live_x", headers["x-api-key"])
        assertNull(headers["x-user-id"])
    }

    @Test
    fun listBucketsDoesNotWaitWithoutToken() = runBlocking<Unit> {
        val storage = RiviumStorage(apiKey = "rv_live_x", client = client { 200 to "[]" })

        withTimeout(2_000) { storage.listBuckets() }

        assertFalse(storage.isAwaitingUserToken)
        val headers = seen.single().request.headers
        assertNull(headers["x-user-token"])
        assertNull(headers["x-user-id"])
    }

    @Test
    fun blockingProviderLikeRiviumAuthIsAccepted() = runBlocking<Unit> {
        // RiviumAuth.shared.blockingTokenProvider is `() -> String?` (typealias RiviumAuthBlockingTokenProvider).
        val authProvider: () -> String? = { "auth-tok" }
        val storage = RiviumStorage.builder("rv_live_x")
            .userTokenProvider(authProvider)
            .client(client { 200 to fileJson })
            .build()

        storage.getFile("f1")

        assertEquals("auth-tok", seen.single().request.header("x-user-token"))
    }

    @Test
    fun fileCallWaitsUntilSetUserToken() = runBlocking<Unit> {
        val fired = AtomicInteger()
        val storage = RiviumStorage.builder("rv_live_x")
            .client(client { 200 to fileJson })
            .onAwaitingUserToken { fired.incrementAndGet() }
            .build()

        val calls = (1..3).map { async(Dispatchers.IO) { storage.getFile("f$it") } }
        awaitUntil { storage.isAwaitingUserToken }
        delay(100)
        assertTrue(seen.isEmpty()) // nothing sent while waiting
        assertEquals(1, fired.get()) // once, not once per call
        assertTrue(calls.none { it.isCompleted })

        storage.setUserToken("tok")
        calls.awaitAll()

        assertEquals(3, seen.size)
        assertTrue(seen.all { it.request.header("x-user-token") == "tok" && it.request.header("x-user-id") == null })
        assertFalse(storage.isAwaitingUserToken)
        assertEquals(1, fired.get())
    }

    @Test
    fun providerNullThenRefreshUserTokenContinues() = runBlocking<Unit> {
        var signedIn: String? = null
        val asked = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { 200 to """{"files":[],"hasMore":false}""" },
            userTokenProvider = { asked.incrementAndGet(); signedIn }
        )

        val call = async(Dispatchers.IO) { storage.listFiles("b1") }
        awaitUntil { storage.isAwaitingUserToken }
        assertTrue(seen.isEmpty())

        signedIn = "later"
        storage.refreshUserToken()
        call.await()

        assertEquals("later", seen.single().request.header("x-user-token"))
        assertFalse(storage.isAwaitingUserToken)
        assertTrue(asked.get() >= 2)
    }

    @Test
    fun waitTimeoutFailsWithTokenRequired() = runBlocking<Unit> {
        val storage = RiviumStorage.builder("rv_live_x")
            .client(client { 200 to fileJson })
            .userTokenWaitTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        try {
            storage.getFile("f1")
            fail("expected an exception")
        } catch (e: RiviumStorageException.UserTokenRequiredException) {
            assertEquals(StorageErrorCode.TOKEN_REQUIRED, e.code)
            assertEquals(401, e.statusCode)
        }
        assertTrue(seen.isEmpty())
        assertFalse(storage.isAwaitingUserToken)
    }

    @Test
    fun serverTokenRequiredParksThenContinues() = runBlocking<Unit> {
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            userToken = "first",
            client = client { req -> if (req.header("x-user-token") == "first") error401("token_required") else 200 to fileJson }
        )

        val call = async(Dispatchers.IO) { storage.getFile("f1") }
        awaitUntil { storage.isAwaitingUserToken }
        assertEquals(1, seen.size)

        storage.setUserToken("second")
        assertEquals("f1", call.await().id)
        assertEquals(listOf("first", "second"), seen.map { it.request.header("x-user-token") })
    }

    @Test
    fun expiredTokenAndProviderSignedOutWaits() = runBlocking<Unit> {
        // The provider gives "old" (refused as expired), then null (signed out), then "new" after sign-in.
        val answers = ArrayDeque(listOf<String?>("old", null))
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { req -> if (req.header("x-user-token") == "old") error401("token_expired") else 200 to fileJson },
            userTokenProvider = { synchronized(answers) { if (answers.isEmpty()) "new" else answers.removeFirst() } }
        )

        val call = async(Dispatchers.IO) { storage.getFile("f1") }
        awaitUntil { storage.isAwaitingUserToken }
        assertEquals(1, seen.size)

        storage.refreshUserToken()
        call.await()
        assertEquals(listOf("old", "new"), seen.map { it.request.header("x-user-token") })
    }

    @Test
    fun signingOutWhileWaitingKeepsWaiting() = runBlocking<Unit> {
        val storage = RiviumStorage(apiKey = "rv_live_x", client = client { 200 to fileJson })

        val call = async(Dispatchers.IO) { storage.download("f1") }
        awaitUntil { storage.isAwaitingUserToken }

        storage.setUserToken(null)
        delay(100)
        assertTrue(storage.isAwaitingUserToken)
        assertFalse(call.isCompleted)
        assertTrue(seen.isEmpty())

        storage.setUserToken("tok")
        call.await()
        assertEquals("tok", seen.single().request.header("x-user-token"))
    }

    @Test
    fun cancellingAWaitingCallStopsWaiting() = runBlocking<Unit> {
        val storage = RiviumStorage(apiKey = "rv_live_x", client = client { 200 to fileJson })

        val call = async(Dispatchers.IO) { storage.delete("f1") }
        awaitUntil { storage.isAwaitingUserToken }
        call.cancelAndJoin()

        assertFalse(storage.isAwaitingUserToken)
        storage.setUserToken("tok")
        delay(50)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun awaitingCallbackFiresAgainForANewWait() = runBlocking<Unit> {
        val fired = AtomicInteger()
        val storage = RiviumStorage(apiKey = "rv_live_x", client = client { 200 to fileJson })
        storage.onAwaitingUserToken = { fired.incrementAndGet() }

        val first = async(Dispatchers.IO) { storage.getFile("f1") }
        awaitUntil { storage.isAwaitingUserToken }
        storage.setUserToken("tok")
        first.await()
        assertEquals(1, fired.get())

        storage.setUserToken(null)
        val second = async(Dispatchers.IO) { storage.getFile("f1") }
        awaitUntil { storage.isAwaitingUserToken }
        assertEquals(2, fired.get())
        storage.setUserToken("tok2")
        second.await()
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun tokenExpiredRefreshesAndRetriesOnce() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { req -> if (req.header("x-user-token") == "old") error401("token_expired") else 200 to "[]" },
            userTokenProvider = { if (calls.incrementAndGet() == 1) "old" else "new" }
        )

        storage.listBuckets()
        assertEquals(listOf("old", "new"), seen.map { it.request.header("x-user-token") })
        assertEquals(2, calls.get())

        storage.listBuckets() // cached
        assertEquals("new", seen.last().request.header("x-user-token"))
        assertEquals(2, calls.get())
    }

    @Test
    fun retriesOnlyOnceThenThrowsTypedError() = runBlocking<Unit> {
        val n = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { error401("token_invalid") },
            userTokenProvider = { "tok-${n.incrementAndGet()}" }
        )
        try {
            storage.listBuckets()
            fail("expected an exception")
        } catch (e: RiviumStorageException.UserTokenRejectedException) {
            assertEquals("token_invalid", e.code)
        }
        assertEquals(2, seen.size)
    }

    @Test
    fun uploadIsResentWithItsBodyAfterTokenExpired() = runBlocking<Unit> {
        val n = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { req -> if (req.header("x-user-token") == "tok-1") error401("token_expired") else 201 to fileJson },
            userTokenProvider = { "tok-${n.incrementAndGet()}" }
        )

        val file = storage.upload("b1", "a/b.txt", "abc".toByteArray())

        assertEquals("f1", file.id)
        assertEquals(2, seen.size)
        assertTrue(seen[1].body.contains("abc"))
        assertTrue(seen[1].body.contains("a/b.txt"))
        assertEquals("tok-2", seen[1].request.header("x-user-token"))
    }

    @Test
    fun concurrentRequestsShareOneProviderCall() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { 200 to "[]" },
            userTokenProvider = {
                calls.incrementAndGet()
                Thread.sleep(50)
                "tok"
            }
        )

        (1..5).map { async(Dispatchers.IO) { storage.listBuckets() } }.awaitAll()

        assertEquals(1, calls.get())
        assertTrue(seen.all { it.request.header("x-user-token") == "tok" })
    }

    @Test
    fun setUserTokenRefreshAndSignedOutProvider() = runBlocking<Unit> {
        var current: String? = null
        val calls = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { 200 to "[]" },
            userTokenProvider = { calls.incrementAndGet(); current }
        )

        storage.listBuckets()
        storage.listBuckets()
        assertTrue(seen.all { it.request.header("x-user-token") == null && it.request.header("x-user-id") == null })
        assertEquals(1, calls.get()) // signed out: not asked on every request

        current = "signed-in"
        storage.refreshUserToken()
        storage.listBuckets()
        assertEquals("signed-in", seen.last().request.header("x-user-token"))

        storage.setUserToken("manual")
        storage.listBuckets()
        assertEquals("manual", seen.last().request.header("x-user-token"))

        storage.setUserToken(null)
        storage.setUserTokenProvider(null)
        storage.listBuckets()
        assertFalse(seen.last().request.headers.names().contains("x-user-token"))
        assertNull(seen.last().request.header("x-user-id"))
    }

    @Test
    fun expiredTokenIsRefreshedBeforeSending() = runBlocking<Unit> {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val now = System.currentTimeMillis() / 1000
        val expired = "${b64("{}")}.${b64("""{"exp":${now - 10}}""")}.sig"
        val fresh = "${b64("{}")}.${b64("""{"exp":${now + 3600}}""")}.sig"
        val n = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            client = client { 200 to "[]" },
            userTokenProvider = { if (n.incrementAndGet() == 1) expired else fresh }
        )

        storage.listBuckets()
        storage.listBuckets()
        assertEquals(listOf(expired, fresh), seen.map { it.request.header("x-user-token") })
    }
}
