package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * File paths and bucket names reach the server as written: spaces are %20 (never `+`), Unicode is UTF-8. Ids are one
 * segment, `.`/`..` segments are refused before any request, and redirects are never followed.
 */
class PathEncodingTest {

    private lateinit var server: MockWebServer

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"p","fileName":"p","mimeType":"text/plain","size":1,
        |"createdAt":"2026-01-01T00:00:00.000Z",
        |"updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

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
    fun getFileByPathEncodesSpacesAsPercent20() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        storage().getFileByPath("b1", "users/u1/my notes 2026.txt")
        assertEquals("/api/v1/buckets/b1/files/users/u1/my%20notes%202026.txt", server.takeRequest().path)
    }

    @Test
    fun deleteByPathEncodesUnicodeAndReservedCharacters() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        storage().deleteByPath("b1", "رسید/a+b #1?.pdf")
        assertEquals(
            "/api/v1/buckets/b1/files/%D8%B1%D8%B3%DB%8C%D8%AF/a%2Bb%20%231%3F.pdf",
            server.takeRequest().path,
        )
    }

    @Test
    fun getBucketByNameEncodesTheName() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"b1","name":"my bucket"}"""))
        runCatching { storage().getBucketByName("my bucket") }
        assertEquals("/api/v1/buckets/name/my%20bucket", server.takeRequest().path)
    }

    private suspend fun refused(call: suspend () -> Unit): RiviumStorageException.HttpException {
        try {
            call()
        } catch (e: RiviumStorageException.HttpException) {
            return e
        }
        fail("no exception")
        throw IllegalStateException()
    }

    private fun assertInvalidPath(e: RiviumStorageException.HttpException) {
        assertEquals(StorageErrorCode.INVALID_PATH, e.code)
        assertEquals("invalid_path", e.code)
        assertEquals(400, e.statusCode)
    }

    @Test
    fun dotSegmentsInPathsAreRefusedWithoutARequest() = runBlocking {
        val storage = storage()
        for (path in listOf(
            "..", ".", "a/../b", "a/./b", "x/%2e%2e/%2e%2e/%2e%2e/f1", "x/%2E%2e/y", "x/.%2E/y", "x/%2e/y",
            "x/%252e%252e/y"
        )) {
            assertInvalidPath(refused { storage.getFileByPath("b1", path) })
            assertInvalidPath(refused { storage.deleteByPath("b1", path) })
            assertTrue(refused { storage.getFileByPath("b1", path) }.message!!.contains("File paths can't contain '.' or '..' segments"))
        }
        assertInvalidPath(refused { storage.upload("b1", "a/../b.txt", byteArrayOf(1)) })
        assertInvalidPath(refused { storage.upload("b1", "a/%2e%2e/b.txt", byteArrayOf(1)) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun dotIdsAreRefusedWithoutARequest() = runBlocking {
        val storage = storage()
        for (id in listOf(".", "..", "%2e%2e", "%2E")) {
            assertInvalidPath(refused { storage.getFile(id) })
            assertInvalidPath(refused { storage.download(id) })
            assertInvalidPath(refused { storage.delete(id) })
            assertInvalidPath(refused { storage.getBucket(id) })
            assertInvalidPath(refused { storage.getBucketByName(id) })
            assertInvalidPath(refused { storage.listFiles(id) })
            assertInvalidPath(refused { storage.upload(id, "a.txt", byteArrayOf(1)) })
            assertInvalidPath(refused { storage.getUrl(id) })
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun leadingTrailingAndRepeatedSlashesAreDroppedLikeTheServer() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        storage().getFileByPath("b1", "/a//b/c.txt/")
        assertEquals("/api/v1/buckets/b1/files/a/b/c.txt", server.takeRequest().path)
    }

    @Test
    fun storedPercent2e2ePathStaysInsideTheFilesEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        storage().getFileByPath("b1", "x/%2e%2e%2f/y")
        assertEquals("/api/v1/buckets/b1/files/x/%252e%252e%252f/y", server.takeRequest().path)
    }

    @Test
    fun questionHashAndPercentInNamesAreEncoded() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        storage().getFileByPath("b1", "photos/a?b#c%d.jpg")
        assertEquals("/api/v1/buckets/b1/files/photos/a%3Fb%23c%25d.jpg", server.takeRequest().path)
    }

    @Test
    fun idsAreEncodedAsOneSegment() = runBlocking {
        val storage = storage()
        server.enqueue(MockResponse().setBody(fileJson))
        storage.getFile("a/b?c#d%")
        assertEquals("/api/v1/files/a%2Fb%3Fc%23d%25", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("abc"))
        storage.download("a/b")
        assertEquals("/api/v1/files/a%2Fb/download", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("{}"))
        storage.delete("a/b")
        assertEquals("/api/v1/files/a%2Fb", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"id":"b1","name":"x"}"""))
        runCatching { storage.getBucket("x/y") }
        assertEquals("/api/v1/buckets/x%2Fy", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"files":[],"nextCursor":null}"""))
        storage.listFiles("b/1")
        assertEquals("/api/v1/buckets/b%2F1/files", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(fileJson))
        storage.upload("my bucket", "a/b.txt", byteArrayOf(1))
        assertEquals("/api/v1/buckets/my%20bucket/files", server.takeRequest().path)

        assertTrue(storage.getUrl("a/b").endsWith("/api/v1/files/a%2Fb/url"))
        assertTrue(storage.getDownloadUrl("a?b").endsWith("/api/v1/files/a%3Fb/download"))
        assertTrue(storage.getTransformUrl("a#b").endsWith("/api/v1/transform/a%23b"))
    }

    @Test
    fun dotSegmentDetection() {
        assertTrue(RiviumStorage.isDotSegment(".."))
        assertTrue(RiviumStorage.isDotSegment("%2e%2E"))
        assertTrue(RiviumStorage.isDotSegment("%252e"))
        assertFalse(RiviumStorage.isDotSegment("..."))
        assertFalse(RiviumStorage.isDotSegment(".hidden"))
        assertFalse(RiviumStorage.isDotSegment("%zz"))
        assertFalse(RiviumStorage.isDotSegment("100%"))
    }

    private fun redirect() = MockResponse().setResponseCode(302)
        .setHeader("Location", server.url("/elsewhere").toString())

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        val storage = storage()
        server.enqueue(redirect())
        val e = refused { storage.getFile("f1") }
        assertEquals(302, e.statusCode)

        server.enqueue(redirect())
        val d = try {
            storage.download("f1"); null
        } catch (x: RiviumStorageException.DownloadException) {
            x
        }
        assertEquals(302, d?.statusCode)

        server.enqueue(redirect())
        val u = try {
            storage.upload("b1", "a.txt", byteArrayOf(1)); null
        } catch (x: RiviumStorageException.UploadException) {
            x
        }
        assertEquals(302, u?.statusCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun redirectsAreNotFollowedWithTheAppsOwnClientEither() = runBlocking {
        // The app's client follows redirects; the SDK derives its own from it that does not.
        val appClient = OkHttpClient.Builder().followRedirects(true).addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(target).build())
        }).build()
        server.enqueue(redirect())
        server.enqueue(MockResponse().setBody(fileJson))
        val e = refused { RiviumStorage(apiKey = "rv_live_x", userToken = "tok", client = appClient).getFile("f1") }
        assertEquals(302, e.statusCode)
        assertEquals(1, server.requestCount)
    }
}
