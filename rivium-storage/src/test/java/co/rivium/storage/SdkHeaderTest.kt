package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Every Rivium Storage API request names the SDK: `X-Rivium-SDK: android/<version>`. */
class SdkHeaderTest {

    private val seen: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"a/b.txt","fileName":"b.txt","mimeType":"text/plain",
        |"size":3,"storageKey":"k","isDeleted":false,"createdAt":"2026-01-01T00:00:00.000Z",
        |"updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    private fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        seen.add(request)
        val json = when {
            request.url.encodedPath.endsWith("/download") -> "abc"
            request.method == "POST" -> fileJson
            else -> "[]"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }).build()

    @Test
    fun headerValueIsNameSlashVersion() {
        assertEquals("android", RiviumStorage.SDK_NAME)
        assertTrue(RiviumStorage.SDK_VERSION.matches(Regex("^[A-Za-z0-9._+-]{1,32}$")))
        assertEquals("android/${RiviumStorage.SDK_VERSION}", RiviumStorage.SDK_HEADER_VALUE)
    }

    @Test
    fun bucketUploadAndDownloadRequestsCarryTheHeader() = runBlocking {
        val storage = RiviumStorage.builder("rv_live_x").client(client()).build()

        storage.listBuckets()
        storage.upload("b1", "a/b.txt", "abc".toByteArray())
        storage.download("f1")

        assertEquals(3, seen.size)
        seen.forEach { assertEquals("android/${RiviumStorage.SDK_VERSION}", it.header("X-Rivium-SDK")) }
    }

    @Test
    fun headerIsSentWithUserTokens() = runBlocking {
        val storage = RiviumStorage.builder("rv_live_x").userTokenProvider { "tok" }.client(client()).build()

        storage.listBuckets()

        val request = seen.single()
        assertEquals("tok", request.header("x-user-token"))
        assertEquals("android/${RiviumStorage.SDK_VERSION}", request.header("X-Rivium-SDK"))
    }
}
