package co.rivium.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
        |"size":3,"storageKey":"k","isDeleted":false,"createdAt":"2026-01-01T00:00:00.000Z",
        |"updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    @Test
    fun sendsTokenFromProviderAndNoUserId() = runBlocking {
        val storage = RiviumStorage.builder("rv_live_x")
            .userId("legacy")
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
    fun legacyUserIdUnchangedWithoutToken() = runBlocking {
        val storage = RiviumStorage(apiKey = "rv_live_x", userId = "u1", client = client { 200 to "[]" })

        storage.listBuckets()

        val headers = seen.single().request.headers
        assertEquals("u1", headers["x-user-id"])
        assertNull(headers["x-user-token"])
    }

    @Test
    fun tokenExpiredRefreshesAndRetriesOnce() = runBlocking {
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
    fun retriesOnlyOnceThenThrowsTypedError() = runBlocking {
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
    fun tokenRequiredWithoutProviderSaysWhatToDo() = runBlocking {
        val storage = RiviumStorage(apiKey = "rv_live_x", client = client { error401("token_required") })
        try {
            storage.getFile("f1")
            fail("expected an exception")
        } catch (e: RiviumStorageException.UserTokenRequiredException) {
            assertTrue(e.message!!.contains("userTokenProvider"))
        }
    }

    @Test
    fun uploadIsResentWithItsBodyAfterTokenExpired() = runBlocking {
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
    fun concurrentRequestsShareOneProviderCall() = runBlocking {
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
    fun setUserTokenRefreshAndSignedOutProvider() = runBlocking {
        var current: String? = null
        val calls = AtomicInteger()
        val storage = RiviumStorage(
            apiKey = "rv_live_x",
            userId = "u1",
            client = client { 200 to "[]" },
            userTokenProvider = { calls.incrementAndGet(); current }
        )

        storage.listBuckets()
        storage.listBuckets()
        assertEquals(listOf("u1", "u1"), seen.map { it.request.header("x-user-id") })
        assertEquals(1, calls.get()) // signed out: not asked on every request

        current = "signed-in"
        storage.refreshUserToken()
        storage.listBuckets()
        assertEquals("signed-in", seen.last().request.header("x-user-token"))
        assertNull(seen.last().request.header("x-user-id"))

        storage.setUserToken("manual")
        storage.listBuckets()
        assertEquals("manual", seen.last().request.header("x-user-token"))

        storage.setUserToken(null)
        storage.setUserTokenProvider(null)
        storage.listBuckets()
        assertFalse(seen.last().request.headers.names().contains("x-user-token"))
        assertEquals("u1", seen.last().request.header("x-user-id"))
    }

    @Test
    fun expiredTokenIsRefreshedBeforeSending() = runBlocking {
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
