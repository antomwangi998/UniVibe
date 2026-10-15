package com.univibe.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val status: Int, val body: String, val retryAfterSeconds: Int? = null)

/** Sends one HTTP request. Throws [java.io.IOException] when the network fails; HTTP error codes are normal responses. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Plain HttpURLConnection, so the app needs no extra networking library.
 * The long read timeout covers a free-tier server waking up from sleep (up to about a minute).
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "UniVibe-Android")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(status, text, connection.getHeaderField("Retry-After")?.toIntOrNull())
        } finally {
            connection.disconnect()
        }
    }
}
