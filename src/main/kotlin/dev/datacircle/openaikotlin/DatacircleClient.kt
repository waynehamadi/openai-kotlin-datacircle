package dev.datacircle.openaikotlin

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.Normalizer
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Low-level client for the open Datacircle API at `https://api.datacircle.dev/v1`.
 *
 * The API needs no key and no account. It allows 60 calls a minute from one address.
 */
internal class DatacircleClient(baseUrl: String?, timeout: Duration) {

    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val timeoutMillis = timeout.inWholeMilliseconds.also {
        require(it in 1..Int.MAX_VALUE) { "timeout must be between 1 ms and ${Int.MAX_VALUE} ms" }
    }.toInt()

    /** `GET /apis`: every API on the sheet, in its order, an array. */
    suspend fun listApis(): Any? = get("/apis")

    /** `GET /apis/{slug}/best`: one API's plans, best first by score. */
    suspend fun bestPlans(slug: String?, limit: Int?): Any? =
        get("/apis/" + encodePath(normalizeSlug(slug, "api")) + "/best?limit=" + normalizeLimit(limit))

    /** `GET /plans`: the plans of every API, or of one, by words in the provider's or plan's name. */
    suspend fun searchPlans(api: String?, query: String?, sort: String?, limit: Int?, offset: Int?): Any? {
        val path = StringBuilder("/plans?sort=")
            .append(normalizeSort(sort))
            .append("&limit=")
            .append(normalizeLimit(limit))
            .append("&offset=")
            .append(normalizeOffset(offset))
        appendOptional(path, "api", api)
        appendOptional(path, "query", query)
        return get(path.toString())
    }

    /** `GET /providers/{slug}`: one provider's plans and rank on each API it sells. */
    suspend fun provider(slug: String?): Any? = get("/providers/" + encodePath(providerSlug(slug)))

    /** Reads one call on an IO thread: HttpURLConnection blocks, on the JVM as on Android. */
    private suspend fun get(path: String): Any? {
        val (status, body) = runInterruptible(Dispatchers.IO) { fetch(path) }
        if (status !in 200..299) {
            throw DatacircleException(httpErrorMessage(status, body), statusCode = status)
        }
        if (body.isNullOrBlank()) {
            throw DatacircleException("The Datacircle API returned an empty response.")
        }
        return try {
            Json.parse(body)
        } catch (e: IllegalArgumentException) {
            throw DatacircleException("The Datacircle API returned invalid JSON.", e)
        }
    }

    private fun fetch(path: String): Pair<Int, String?> {
        val connection = try {
            URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw DatacircleException(transportErrorMessage(e), e)
        }
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            return status to body
        } catch (e: IOException) {
            throw DatacircleException(transportErrorMessage(e), e)
        } finally {
            connection.disconnect()
        }
    }

    /** Says why no answer came: a timeout, an unknown host, a TLS failure, a refused or a lost connection. */
    private fun transportErrorMessage(error: Throwable): String {
        val causes = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).toList()
        return when {
            causes.any { it is SocketTimeoutException } ->
                "The Datacircle API did not answer within ${seconds(timeoutMillis)} s. Retry in a moment."
            causes.any { it is UnknownHostException } ->
                "The Datacircle API's host ${URI.create(baseUrl).host} could not be found. " +
                    "Check the network and the base URL."
            causes.any { it is SSLException } ->
                "The secure connection to the Datacircle API failed (TLS). Check the network and the base URL."
            causes.any { it is ConnectException } ->
                "The Datacircle API refused the connection. Check the network and the base URL."
            else -> "The connection to the Datacircle API was lost before it answered. Retry in a moment."
        }
    }

    internal companion object {
        const val DEFAULT_LIMIT: Int = 10
        const val MAX_LIMIT: Int = 50
        val SORTS: List<String> = listOf("price", "provider", "score")

        private const val USER_AGENT = "openai-kotlin-datacircle/0.1.0"
        private const val MAX_ERROR_LENGTH = 300

        /** Turns a provider's name into its slug: accents dropped, lowercase letters and digits, the rest a hyphen. */
        fun providerSlug(name: String?): String {
            val text = ensureNotBlank(name, "provider")
            val slug = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            require(slug.isNotEmpty()) {
                "Datacircle writes each provider's name in Latin letters, so \"${text.trim()}\" finds none. " +
                    "Pass the name in Latin letters or its website's domain, like ihuyi for ihuyi.com, " +
                    "or find the provider with datacircle_search_plans."
            }
            return slug
        }

        private fun httpErrorMessage(statusCode: Int, body: String?): String {
            val retry = "The API allows 60 calls a minute from one address. Retry in a minute."
            val reason = errorSentence(body)?.let { if (statusCode == 429) "$it $retry" else it }
                ?: when (statusCode) {
                    400 -> "Invalid request. Check the tool arguments."
                    404 -> "Not found."
                    429 -> retry
                    else -> "Request could not be completed."
                }
            return "Datacircle API request failed: HTTP $statusCode. $reason"
        }

        /** The API's own one-line reason, from its `{"error": "..."}` body, or `null`. */
        private fun errorSentence(body: String?): String? {
            if (body.isNullOrBlank()) {
                return null
            }
            val parsed = try {
                Json.parse(body)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val error = ((parsed as? Map<*, *>)?.get("error") as? String)?.trim()
            if (error.isNullOrEmpty()) {
                return null
            }
            val sentence = if (error.length > MAX_ERROR_LENGTH) error.take(MAX_ERROR_LENGTH - 3) + "..." else error
            return if (sentence.endsWith(".")) sentence else "$sentence."
        }

        private fun appendOptional(path: StringBuilder, name: String, value: String?) {
            if (!value.isNullOrBlank()) {
                path.append('&').append(name).append('=').append(encode(value.trim()))
            }
        }

        private fun normalizeLimit(limit: Int?): Int {
            val value = limit ?: DEFAULT_LIMIT
            require(value in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
            return value
        }

        private fun normalizeOffset(offset: Int?): Int {
            val value = offset ?: 0
            require(value >= 0) { "offset must be 0 or more" }
            return value
        }

        private fun normalizeSort(sort: String?): String {
            if (sort.isNullOrBlank()) {
                return "score"
            }
            val normalized = sort.trim().lowercase(Locale.ROOT)
            require(normalized in SORTS) { "sort must be one of $SORTS" }
            return normalized
        }

        private fun normalizeSlug(slug: String?, name: String): String =
            ensureNotBlank(slug, name).trim().lowercase(Locale.ROOT)

        private fun ensureNotBlank(value: String?, name: String): String {
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$name must not be empty")
            }
            return value
        }

        private fun seconds(millis: Int): String =
            if (millis % 1000 == 0) (millis / 1000).toString() else String.format(Locale.ROOT, "%.1f", millis / 1000.0)

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun encodePath(value: String): String = encode(value).replace("+", "%20")

        private fun normalizeBaseUrl(baseUrl: String?): String {
            val value = (if (baseUrl.isNullOrBlank()) DatacircleTools.DEFAULT_BASE_URL else baseUrl.trim()).trimEnd('/')
            val uri = try {
                URI.create(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("baseUrl is not a valid URL", e)
            }
            val scheme = uri.scheme
            require(scheme != null && (scheme.equals("http", true) || scheme.equals("https", true))) {
                "baseUrl must use HTTP or HTTPS"
            }
            require(uri.host != null) { "baseUrl must include a host" }
            return value
        }
    }
}
