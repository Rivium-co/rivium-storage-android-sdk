package co.rivium.storage

/**
 * The content type the SDK sends when the app passes none.
 *
 * One entry per file extension the Rivium Console lists as a file type (Bucket
 * settings), mapped to the type the server stores for that format. Keep it
 * equal to the `extensions` of RiviumConsole/docs/storage-redesign/FILE-TYPES.md
 * (`FileTypesTest` checks every entry against a copy of that JSON).
 */
internal object FileTypes {

    /** Extension (lower case, no dot) to the content type to send. */
    val byExtension: Map<String, String> = mapOf(
        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        // Documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        // Text · Data
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "xml" to "application/xml",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        // Video
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        // Audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/vnd.wave",
        "m4a" to "audio/x-m4a",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/x-flac",
        // Archives
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "rar" to "application/x-rar-compressed",
        // Risky
        "svg" to "image/svg+xml",
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml"
    )

    /**
     * The extension of the file [path] names: the text after the last dot of
     * the last path segment, lower case. Null when there is none (`notes`,
     * `.config/file`, `.gitignore`, `name.`).
     */
    fun extensionOf(path: String): String? {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    /** The table's type for [path]'s extension, or null when the table has none. */
    fun contentTypeFor(path: String): String? = extensionOf(path)?.let { byExtension[it] }
}
