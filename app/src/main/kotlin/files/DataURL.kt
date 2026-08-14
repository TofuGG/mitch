package garden.appl.mitch.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DataURL(val url: String) {
    private val startPos: Int
    private val base64: Boolean
    private val charset: String

    init {
        if (!isValid(url))
            throw IllegalArgumentException(url)
        startPos = url.indexOf(',') + 1
        val mediaType = url.substring("data:".length, startPos - 1)
        base64 = mediaType.endsWith(";base64")
        charset = Regex(""";charset=(.*?)(?:;.*)?$""").find(mediaType)?.groupValues?.getOrNull(1)
                ?: "US-ASCII"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun toInputStream(): InputStream {
        // Data URLs use percent-encoding only; URLDecoder must not be used here because it
        // also decodes '+' to a space (form-url-encoded semantics), which corrupts any base64
        // payload containing '+' (a regular base64 character) and any literal '+' in raw data.
        val charSet = Charset.forName(charset)
        val bytes = percentDecode(url.substring(startPos), charSet)
        val byteArray = if (base64)
            Base64.decode(bytes.toString(charSet))
        else
            bytes
        return ByteArrayInputStream(byteArray)
    }

    private fun percentDecode(s: String, charset: Charset): ByteArray {
        if (s.indexOf('%') == -1)
            return s.toByteArray(charset)
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi != -1 && lo != -1) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(charset))
            i++
        }
        return out.toByteArray()
    }

    companion object {
        fun isValid(url: String) = url.startsWith("data:")
    }
}