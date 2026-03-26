package com.whatsapp.bot.kapso

import com.whatsapp.bot.config.KapsoConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Client for the Kapso / Meta WhatsApp Cloud API.
 *
 * All outbound calls go to: POST {baseUrl}/meta/whatsapp/{phoneNumberId}/messages
 *
 * Media is uploaded to: POST {baseUrl}/meta/whatsapp/{phoneNumberId}/media
 */
class KapsoClient(private val config: KapsoConfig) {

    private val logger = LoggerFactory.getLogger(KapsoClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val http =
            HttpClient(CIO) {
                install(ContentNegotiation) { json(json) }
                install(Logging) {
                    logger = Logger.DEFAULT
                    level = LogLevel.INFO
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }

    private val messagesUrl
        get() = "${config.baseUrl}/meta/whatsapp/v24.0/${config.phoneNumberId}/messages"

    private val mediaUrl
        get() = "${config.baseUrl}/meta/whatsapp/v24.0/${config.phoneNumberId}/media"

    // ─── Text ────────────────────────────────────────────────────────────────

    /** Send a plain text message to [to] (E.164 phone number, e.g. "1234567890"). */
    suspend fun sendText(to: String, body: String): SendMessageResponse {
        logger.debug("Sending text to $to: $body")
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendTextRequest(to = to, text = TextBody(body)))
                }
                .body()
    }

    // ─── Reactions ───────────────────────────────────────────────────────────

    /** React to a specific message with an emoji. */
    suspend fun sendReaction(to: String, messageId: String, emoji: String): SendMessageResponse {
        logger.debug("Sending reaction $emoji to message $messageId for $to")
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendReactionRequest(to = to, reaction = ReactionBody(messageId, emoji)))
                }
                .body()
    }

    // ─── Media (by hosted URL or previously uploaded media ID) ───────────────

    /** Send an image. Provide either a public [url] or a Kapso/Meta [mediaId]. */
    suspend fun sendImage(
            to: String,
            url: String? = null,
            mediaId: String? = null,
            caption: String? = null
    ): SendMessageResponse {
        require(url != null || mediaId != null) {
            "Either url or mediaId must be provided for image"
        }
        val body = MediaBody(id = mediaId, link = url, caption = caption)
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendMediaRequest(to = to, type = "image", image = body))
                }
                .body()
    }

    /** Send a video. Provide either a public [url] or a Kapso/Meta [mediaId]. */
    suspend fun sendVideo(
            to: String,
            url: String? = null,
            mediaId: String? = null,
            caption: String? = null
    ): SendMessageResponse {
        require(url != null || mediaId != null) {
            "Either url or mediaId must be provided for video"
        }
        val body = MediaBody(id = mediaId, link = url, caption = caption)
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendMediaRequest(to = to, type = "video", video = body))
                }
                .body()
    }

    /** Send an audio clip. Provide either a public [url] or a Kapso/Meta [mediaId]. */
    suspend fun sendAudio(
            to: String,
            url: String? = null,
            mediaId: String? = null
    ): SendMessageResponse {
        require(url != null || mediaId != null) {
            "Either url or mediaId must be provided for audio"
        }
        val body = MediaBody(id = mediaId, link = url)
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendMediaRequest(to = to, type = "audio", audio = body))
                }
                .body()
    }

    /** Send a document. Provide either a public [url] or a Kapso/Meta [mediaId]. */
    suspend fun sendDocument(
            to: String,
            url: String? = null,
            mediaId: String? = null,
            caption: String? = null,
            filename: String? = null,
    ): SendMessageResponse {
        require(url != null || mediaId != null) {
            "Either url or mediaId must be provided for document"
        }
        val body = MediaBody(id = mediaId, link = url, caption = caption, filename = filename)
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(SendMediaRequest(to = to, type = "document", document = body))
                }
                .body()
    }

    // ─── Interactive (buttons) ───────────────────────────────────────────────

    /**
     * Send an interactive button message.
     *
     * @param buttons List of (id, label) pairs – max 3 buttons.
     */
    suspend fun sendButtons(
            to: String,
            body: String,
            buttons: List<Pair<String, String>>
    ): SendMessageResponse {
        require(buttons.size in 1..3) { "WhatsApp supports 1–3 quick reply buttons" }
        val payload =
                SendInteractiveRequest(
                        to = to,
                        interactive =
                                InteractivePayload(
                                        body = InteractiveBody(body),
                                        action =
                                                InteractiveAction(
                                                        buttons =
                                                                buttons.map { (id, title) ->
                                                                    InteractiveButton(
                                                                            reply =
                                                                                    ButtonReply(
                                                                                            id,
                                                                                            title
                                                                                    )
                                                                    )
                                                                }
                                                )
                                )
                )
        return http
                .post(messagesUrl) {
                    header("X-API-Key", config.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                .body()
    }

    // ─── Media upload ────────────────────────────────────────────────────────

    /**
     * Upload raw bytes to Kapso/Meta and return the media ID. Use the returned ID in subsequent
     * [sendImage]/[sendDocument] etc. calls.
     */
    suspend fun uploadMedia(fileBytes: ByteArray, mimeType: String, filename: String): String {
        logger.debug("Uploading media: $filename ($mimeType, ${fileBytes.size} bytes)")
        val response: UploadMediaResponse =
                http
                        .post(mediaUrl) {
                            header("X-API-Key", config.apiKey)
                            setBody(
                                    MultiPartFormDataContent(
                                            formData {
                                                append("messaging_product", "whatsapp")
                                                append(
                                                        "file",
                                                        fileBytes,
                                                        Headers.build {
                                                            append(
                                                                    HttpHeaders.ContentType,
                                                                    mimeType
                                                            )
                                                            append(
                                                                    HttpHeaders.ContentDisposition,
                                                                    "filename=\"$filename\""
                                                            )
                                                        }
                                                )
                                                append("type", mimeType)
                                            }
                                    )
                            )
                        }
                        .body()
        return response.id
    }

    /** Retrieve the CDN URL for a previously uploaded media asset. */
    suspend fun getMediaUrl(mediaId: String): String? {
        val response: MediaUrlResponse =
                http
                        .get("${config.baseUrl}/meta/whatsapp/v24.0/media/$mediaId") {
                            header("X-API-Key", config.apiKey)
                        }
                        .body()
        return response.url
    }

    fun close() = http.close()
}
