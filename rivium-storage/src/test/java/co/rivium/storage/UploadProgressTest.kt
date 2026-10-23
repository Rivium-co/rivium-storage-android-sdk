package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class UploadProgressTest {

    private lateinit var server: MockWebServer

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"a/big.bin","fileName":"big.bin",
        |"mimeType":"application/octet-stream","size":1048576,
        |"createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    private val direct = Executor { it.run() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Sends every request to the local MockWebServer (the SDK always uses the Rivium host). */
    private fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val original = chain.request()
        val target = original.url.newBuilder()
            .scheme("http")
            .host(server.hostName)
            .port(server.port)
            .build()
        chain.proceed(original.newBuilder().url(target).build())
    }).build()

    private fun storage() = RiviumStorage(apiKey = "rv_live_x", userToken = "tok", client = client()).also {
        it.progressExecutor = direct
    }

    @Test
    fun reportsIncreasingProgressEndingAtTotal() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(fileJson))
        val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val events = Collections.synchronizedList(mutableListOf<UploadProgress>())

        storage().upload("b1", "a/big.bin", data, onProgress = { events.add(it) })

        val recorded = server.takeRequest()
        val total = recorded.bodySize
        assertTrue("expected several events, got ${events.size}", events.size >= 2)
        events.forEach { assertEquals(total, it.totalBytes) }
        events.zipWithNext().forEach { (a, b) -> assertTrue("not increasing: $a -> $b", b.bytesSent > a.bytesSent) }
        events.forEach { assertTrue(it.fraction in 0.0..1.0) }
        assertEquals(total, events.last().bytesSent)
        assertEquals(1.0, events.last().fraction, 0.0)
        assertTrue(total > data.size)
    }

    @Test
    fun noProgressRequestedSendsSameBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(fileJson))
        server.enqueue(MockResponse().setResponseCode(201).setBody(fileJson))
        val data = ByteArray(200_000) { 7 }
        val calls = AtomicInteger()

        storage().upload("b1", "a/big.bin", data)
        storage().upload("b1", "a/big.bin", data, onProgress = { calls.incrementAndGet() })

        val plain = server.takeRequest()
        val withProgress = server.takeRequest()
        // Same size and same form parts (only the random multipart boundary differs).
        assertEquals(plain.bodySize, withProgress.bodySize)
        assertEquals(plain.getHeader("Content-Length"), withProgress.getHeader("Content-Length"))
        assertTrue(calls.get() >= 1)
    }

    @Test
    fun throttlesAndAlwaysSendsFinalEvent() {
        val events = mutableListOf<UploadProgress>()
        var now = 0L
        val reporter = UploadProgressReporter(10_000, direct, { events.add(it) }, clock = { now })

        // 1-byte steps at the same instant: only the first, then one per 1% (100 bytes).
        for (i in 1..10_000L) reporter.onBytes(i)
        assertEquals(10_000L, events.last().bytesSent)
        assertTrue("throttled to about 100 events, got ${events.size}", events.size in 2..102)

        // A second attempt (re-send after a token refresh) never goes backwards.
        val before = events.size
        reporter.onBytes(5)
        reporter.onBytes(10_000)
        assertEquals(before, events.size)

        // Time-based: 50 ms apart is enough even for a tiny step.
        val timed = mutableListOf<UploadProgress>()
        val r2 = UploadProgressReporter(1_000_000, direct, { timed.add(it) }, clock = { now })
        r2.onBytes(1); now += 10_000_000; r2.onBytes(2); now += 50_000_000; r2.onBytes(3)
        assertEquals(listOf(1L, 3L), timed.map { it.bytesSent })
    }
}
