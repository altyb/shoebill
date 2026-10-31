package dev.altayeb.shoebill.subs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: $body")

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

internal object Http {
    private const val TIMEOUT_MS = 8_000

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) { request("GET", url, headers, null) }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            request("POST", url, headers + ("Content-Type" to "application/json"), json)
        }

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "*/*")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw HttpStatusException(code, String(bytes, Charsets.UTF_8).take(300))
            return bytes
        } finally {
            c.disconnect()
        }
    }
}
