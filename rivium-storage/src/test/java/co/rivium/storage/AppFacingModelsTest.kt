package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Answers that carry only the fields Rivium Storage shows to apps decode in full. */
class AppFacingModelsTest {

    private lateinit var server: MockWebServer

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"a/b.txt","fileName":"b.txt",
        |"mimeType":"text/plain","size":3,"checksum":"abc","metadata":{"k":"v"},"uploadedBy":"u1",
        |"createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-02T00:00:00.000Z",
        |"uploadedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    private val bucketJson = """{"id":"b1","name":"photos","description":"Avatars","visibility":"private",
        |"maxFileSize":1048576,"allowedMimeTypes":["image/png"],"blockExecutables":true,
        |"createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-02T00:00:00.000Z"}""".trimMargin()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun storage() = RiviumStorage(apiKey = "rv_live_x", userToken = "tok", client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val original = chain.request()
        val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(original.newBuilder().url(target).build())
    }).build())

    @Test
    fun fileWithOnlyAppFields() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        val f = storage().getFile("f1")
        assertEquals("f1", f.id)
        assertEquals("b1", f.bucketId)
        assertEquals("a/b.txt", f.path)
        assertEquals("b.txt", f.fileName)
        assertEquals("text/plain", f.mimeType)
        assertEquals(3L, f.size)
        assertEquals("abc", f.checksum)
        assertEquals("v", f.metadata?.get("k"))
        assertEquals("u1", f.uploadedBy)
        assertNotNull(f.createdAt)
        assertNotNull(f.updatedAt)
        assertNotNull(f.uploadedAt)
    }

    @Test
    fun sizeAbove2GbFits() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson.replace("\"size\":3", "\"size\":5000000000")))
        assertEquals(5_000_000_000L, storage().getFile("f1").size)
    }

    @Test
    fun minimalFileAndListResult() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"f1","bucketId":"b1","path":"a","fileName":"a",
            |"mimeType":"text/plain","size":1,"createdAt":"2026-01-01T00:00:00.000Z",
            |"updatedAt":"2026-01-01T00:00:00.000Z"}],"nextCursor":null}""".trimMargin()))
        val result = storage().listFiles("b1")
        assertEquals(1, result.files.size)
        assertNull(result.files[0].uploadedAt)
        assertNull(result.files[0].checksum)
        assertNull(result.nextCursor)
    }

    @Test
    fun bucketWithOnlyAppFields() = runBlocking {
        server.enqueue(MockResponse().setBody(bucketJson))
        val b = storage().getBucket("b1")
        assertEquals("b1", b.id)
        assertEquals("photos", b.name)
        assertEquals("Avatars", b.description)
        assertEquals("private", b.visibility)
        assertEquals(1048576, b.maxFileSize)
        assertEquals(listOf("image/png"), b.allowedMimeTypes)
        assertEquals(true, b.blockExecutables)
        assertNotNull(b.createdAt)
        assertNotNull(b.updatedAt)

        server.enqueue(MockResponse().setBody("[$bucketJson]"))
        assertEquals("photos", storage().listBuckets().single().name)
    }
}
