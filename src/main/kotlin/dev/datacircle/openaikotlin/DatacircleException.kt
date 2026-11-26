package dev.datacircle.openaikotlin

/**
 * Raised when a Datacircle API request fails.
 *
 * @property statusCode the HTTP status, or -1 when the request received no HTTP response
 */
internal class DatacircleException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int = -1,
) : RuntimeException(message, cause)
