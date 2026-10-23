package co.rivium.storage

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dates as Rivium Storage sends them: ISO 8601 in UTC (`2026-10-23T09:15:02.123Z`), read with or without the
 * fractional seconds and always as UTC (a plain date format would read them in the phone's time zone).
 */
internal object IsoDateAdapter : JsonDeserializer<Date>, JsonSerializer<Date> {
    private val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Date {
        val text = json.asString
        for (pattern in patterns) {
            try {
                return format(pattern).parse(text) ?: continue
            } catch (_: ParseException) {
                // try the next pattern
            }
        }
        throw JsonParseException("Expected an ISO 8601 date, got $text")
    }

    override fun serialize(src: Date, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonPrimitive(format(patterns[0]).format(src))
}
