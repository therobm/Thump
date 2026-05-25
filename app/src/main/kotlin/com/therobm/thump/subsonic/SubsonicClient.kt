package com.therobm.thump.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A thin client over the OpenSubsonic JSON API.
 *
 * The client is the layer boundary between the rest of the app and the HTTP/JSON machinery, so
 * it constructs requests, runs them on a background dispatcher, and decodes the response. The
 * rest of the app sees suspend functions returning a SubsonicResult and never touches OkHttp or
 * the response envelope directly.
 *
 * One client per server connection. Construct a new one if the credentials or server URL change.
 */
class SubsonicClient(
    private val okHttpClient: OkHttpClient,
    private val jsonDecoder: Json,
    private val credentials: SubsonicCredentials,
    private val authMode: SubsonicAuthMode,
) {

    /**
     * Calls /rest/ping. A success result confirms the credentials work and tells the caller what
     * protocol version and server brand the server reports.
     */
    suspend fun ping(): SubsonicResult<SubsonicPingResult> {
        return callAndDecodeEnvelope("rest/ping", emptyMap()) { envelope: SubsonicResponseEnvelope ->
            SubsonicPingResult(
                protocolVersion = envelope.version,
                serverType = envelope.type,
                serverVersion = envelope.serverVersion,
                isOpenSubsonicServer = envelope.openSubsonic == true,
            )
        }
    }

    /**
     * Calls /rest/pulse/recentlyPlayed?count=1 and turns the HTTP status into a boolean.
     *
     * Per the Pulse extension spec, HTTP 200 means the server implements the Pulse home-screen
     * endpoints and HTTP 404 means it is a standard OpenSubsonic server with no Pulse layer. Any
     * other status is reported as a server error so callers can decide whether to retry or treat
     * the server as standard.
     */
    suspend fun probePulseExtensions(): SubsonicResult<Boolean> {
        val request: Request
        try {
            val requestUrl: String = buildAuthenticatedUrl(
                pathAfterBase = "rest/pulse/recentlyPlayed",
                extraQueryParameters = mapOf("count" to "1"),
            )
            request = Request.Builder().url(requestUrl).get().build()
        } catch (urlBuildFailure: Exception) {
            return SubsonicResult.TransportError(urlBuildFailure)
        }

        val httpStatusCode: Int
        try {
            httpStatusCode = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    response.code
                }
            }
        } catch (transportFailure: Exception) {
            return SubsonicResult.TransportError(transportFailure)
        }

        if (httpStatusCode == 200) {
            return SubsonicResult.Ok(true)
        }
        if (httpStatusCode == 404) {
            return SubsonicResult.Ok(false)
        }
        return SubsonicResult.ServerError(httpStatusCode, "Unexpected HTTP status from Pulse probe")
    }

    /**
     * Run a Subsonic call that only needs the standard response envelope to produce its result.
     *
     * For endpoints that carry an endpoint-specific payload, write a dedicated method that
     * decodes the payload separately rather than extending this helper.
     */
    private suspend fun <T> callAndDecodeEnvelope(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
        buildResult: (SubsonicResponseEnvelope) -> T,
    ): SubsonicResult<T> {
        val request: Request
        try {
            val requestUrl: String = buildAuthenticatedUrl(pathAfterBase, extraQueryParameters)
            request = Request.Builder().url(requestUrl).get().build()
        } catch (urlBuildFailure: Exception) {
            return SubsonicResult.TransportError(urlBuildFailure)
        }

        val responseBodyText: String = try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (body == null) {
                        throw IllegalStateException("Subsonic response had no body")
                    }
                    body.string()
                }
            }
        } catch (transportFailure: Exception) {
            return SubsonicResult.TransportError(transportFailure)
        }

        val envelope: SubsonicResponseEnvelope = try {
            jsonDecoder.decodeFromString(SubsonicResponseRoot.serializer(), responseBodyText)
                .subsonicResponse
        } catch (decodeFailure: Exception) {
            return SubsonicResult.MalformedResponse(decodeFailure)
        }

        if (envelope.status != "ok") {
            val errorPayload = envelope.error
            if (errorPayload != null) {
                return SubsonicResult.ServerError(errorPayload.code, errorPayload.message)
            }
            return SubsonicResult.ServerError(-1, "Subsonic returned status=${envelope.status} with no error payload")
        }

        return SubsonicResult.Ok(buildResult(envelope))
    }

    /**
     * Build the fully-qualified URL for a Subsonic endpoint with auth and standard params attached.
     *
     * Callers supply the path after the server origin (for example "rest/ping" or
     * "rest/pulse/recentlyPlayed") and any endpoint-specific query parameters; this method adds
     * the username, protocol version, client identifier, response format, and the chosen auth
     * params (token+salt or legacy hex-encoded password).
     */
    private fun buildAuthenticatedUrl(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
    ): String {
        val trimmed = credentials.serverUrl.trimEnd('/')
        // If the user omitted a scheme, default to http. https takes precedence when explicitly
        // provided; this default only kicks in for LAN servers typed as bare host[:port].
        val base: String
        val lowerCased = trimmed.lowercase()
        if (lowerCased.startsWith("http://") || lowerCased.startsWith("https://")) {
            base = trimmed
        } else {
            base = "http://" + trimmed
        }
        val builder: HttpUrl.Builder = "$base/$pathAfterBase".toHttpUrl().newBuilder()

        builder.addQueryParameter("u", credentials.username)
        builder.addQueryParameter("v", PROTOCOL_VERSION)
        builder.addQueryParameter("c", CLIENT_NAME)
        builder.addQueryParameter("f", "json")

        for (extraEntry in extraQueryParameters.entries) {
            builder.addQueryParameter(extraEntry.key, extraEntry.value)
        }

        when (authMode) {
            is SubsonicAuthMode.Token -> {
                val salt: String = generateAuthSalt()
                val token: String = computeMd5Hex(credentials.password + salt)
                builder.addQueryParameter("t", token)
                builder.addQueryParameter("s", salt)
            }
            is SubsonicAuthMode.Legacy -> {
                builder.addQueryParameter("p", "enc:" + encodePasswordAsHex(credentials.password))
            }
        }

        return builder.build().toString()
    }

    private fun generateAuthSalt(): String {
        val saltCharacters = "abcdefghijklmnopqrstuvwxyz0123456789"
        val saltCharacterCount = saltCharacters.length
        val saltLength = 12
        val result = StringBuilder(saltLength)
        for (saltCharacterIndex in 0 until saltLength) {
            val pickedIndex = saltRandom.nextInt(saltCharacterCount)
            result.append(saltCharacters[pickedIndex])
        }
        return result.toString()
    }

    private fun computeMd5Hex(input: String): String {
        val digestBytes: ByteArray = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digestBytes)
    }

    private fun encodePasswordAsHex(password: String): String {
        return bytesToLowerHex(password.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToLowerHex(source: ByteArray): String {
        val hexCharacters = "0123456789abcdef"
        val sourceLength = source.size
        val result = StringBuilder(sourceLength * 2)
        for (sourceByteIndex in 0 until sourceLength) {
            val currentByte = source[sourceByteIndex].toInt() and 0xff
            result.append(hexCharacters[currentByte ushr 4])
            result.append(hexCharacters[currentByte and 0x0f])
        }
        return result.toString()
    }

    companion object {
        const val PROTOCOL_VERSION: String = "1.16.1"
        const val CLIENT_NAME: String = "Thump"
        private val saltRandom: SecureRandom = SecureRandom()
    }
}
