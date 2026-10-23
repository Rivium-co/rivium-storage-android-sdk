package co.rivium.storage.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/** Small files made in code, so every demo works without picking anything. */
object SampleFiles {

    /** A real PNG (gradient + label), large enough that transforms are easy to see. */
    fun png(width: Int = 480, height: Int = 320, label: String = "Rivium"): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.rgb(37, 99, 235), Color.rgb(236, 72, 153), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = height / 6f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, width / 2f, height / 2f + paint.textSize / 3, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawRect(3f, 3f, width - 3f, height - 3f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** A minimal valid PDF (one empty page). */
    fun pdf(): ByteArray = (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF\n"
        ).toByteArray()

    /**
     * A real PNG of random-noise pixels, for watching upload progress move. Noise does not compress,
     * so the file is about width × height × 3 bytes (850×850 ≈ 2.2 MB, 1300×1300 ≈ 5 MB).
     * It is a real image/png, so buckets that allow only images accept it. Call it off the main thread.
     */
    fun noisePng(width: Int, height: Int): ByteArray {
        val rnd = java.util.Random(42)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(width * height * 3 + 1024)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Width × height of PNG/JPEG/… bytes, decoded by Android itself (bounds only), or null when it is not an image. */
    fun decodedSize(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun text(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)

    /** HTML bytes. Declared as image/png they must be refused by an images-only bucket. */
    fun html(): ByteArray = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".toByteArray()

    /** The first bytes of a Windows program ("MZ" header). Not runnable; enough for the server to recognise it. */
    fun fakeExe(): ByteArray = ByteArray(512).also {
        it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte()
        it[0x3C] = 0x80.toByte() // e_lfanew -> "PE\0\0" at 0x80
        it[0x80] = 'P'.code.toByte(); it[0x81] = 'E'.code.toByte()
    }

    /** [size] bytes that start as a PNG, for the "too big" demo. */
    fun oversized(size: Int): ByteArray {
        val head = png(16, 16)
        return ByteArray(size).also { System.arraycopy(head, 0, it, 0, minOf(head.size, size)) }
    }

    fun isImage(bytes: ByteArray): Boolean =
        bytes.size > 12 && (
            (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) || // PNG
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || // JPEG
                (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) || // GIF
                (bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()) // WEBP
            )
}
