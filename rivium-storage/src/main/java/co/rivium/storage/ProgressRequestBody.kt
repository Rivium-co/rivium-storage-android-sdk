package co.rivium.storage

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.util.concurrent.Executor

/**
 * Reports upload progress for one upload() call.
 *
 * Shared by every attempt of the call (the SDK re-sends once after a refused
 * user token), so the numbers it reports only go up. Throttled to about one
 * event per 50 ms or per 1% of the body, plus always the final event.
 */
internal class UploadProgressReporter(
    private val totalBytes: Long,
    private val executor: Executor,
    private val callback: (UploadProgress) -> Unit,
    private val clock: () -> Long = { System.nanoTime() }
) {
    private var lastSent = -1L
    private var lastAt = 0L
    private val step = maxOf(1L, totalBytes / 100)

    @Synchronized
    fun onBytes(sent: Long) {
        val value = sent.coerceIn(0L, totalBytes)
        if (value <= lastSent) return
        val now = clock()
        val isFinal = value == totalBytes
        if (!isFinal && lastSent >= 0 && now - lastAt < MIN_INTERVAL_NANOS && value - lastSent < step) return
        lastSent = value
        lastAt = now
        val progress = UploadProgress(value, totalBytes)
        executor.execute { callback(progress) }
    }

    private companion object {
        const val MIN_INTERVAL_NANOS = 50_000_000L
    }
}

/**
 * Wraps a request body and counts the bytes written to the network.
 * Each write starts counting from zero; [reporter] keeps the reported
 * numbers increasing.
 */
internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val reporter: UploadProgressReporter
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        // A Buffer is not the network (for example a logging interceptor
        // reading the body): write through without reporting.
        if (sink is Buffer) {
            delegate.writeTo(sink)
            return
        }
        val counting = CountingSink(sink, reporter).buffer()
        delegate.writeTo(counting)
        counting.flush()
    }

    private class CountingSink(delegate: Sink, private val reporter: UploadProgressReporter) : ForwardingSink(delegate) {
        private var written = 0L

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            written += byteCount
            reporter.onBytes(written)
        }
    }
}
