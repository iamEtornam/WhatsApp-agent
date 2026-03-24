package com.whatsapp.bot.webhook

import com.whatsapp.bot.agent.BotAgent
import com.whatsapp.bot.kapso.WebhookEvent
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("WebhookHandler")

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * In-memory deduplication cache: idempotency key → arrival epoch-ms.
 * A background coroutine (launched once per application lifecycle) sweeps
 * expired entries every [DEDUP_TTL_MS], so memory is bounded regardless of
 * traffic patterns.
 */
private val seenKeys = ConcurrentHashMap<String, Long>()
private const val DEDUP_TTL_MS = 5 * 60 * 1_000L // 5 minutes

/**
 * Registers the `/webhook` POST route that Kapso will call for every WhatsApp event.
 *
 * - Signature verification is performed when [webhookSecret] is non-empty.
 * - Duplicate deliveries are suppressed via an idempotency-key cache with
 *   scheduled eviction tied to the application lifecycle.
 * - Message processing is dispatched on the application's lifecycle scope so
 *   coroutines are cancelled cleanly on shutdown.
 * - Parse failures return 400 so Kapso can retry.
 * - HMAC verification operates on raw bytes to avoid encoding round-trip bugs.
 */
fun Route.webhookRoutes(agent: BotAgent, webhookSecret: String) {
    // Launch a background sweeper on the application scope (cancelled on shutdown).
    application.launch {
        while (isActive) {
            delay(DEDUP_TTL_MS)
            val cutoff = System.currentTimeMillis() - DEDUP_TTL_MS
            seenKeys.entries.removeIf { (_, ts) -> ts < cutoff }
            logger.debug("Swept dedup cache, remaining entries: ${seenKeys.size}")
        }
    }

    post("/webhook") {
        // Receive raw bytes – avoids charset encoding round-trip for HMAC verification.
        val rawBytes = call.receive<ByteArray>()

        // ── Signature verification ────────────────────────────────────────────
        if (webhookSecret.isNotBlank()) {
            val signature = call.request.headers["X-Webhook-Signature"] ?: ""
            if (!verifySignature(rawBytes, webhookSecret, signature)) {
                logger.warn("Webhook signature verification failed")
                call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
                return@post
            }
        }

        // ── Idempotency / deduplication ───────────────────────────────────────
        val idempotencyKey = call.request.headers["X-Idempotency-Key"]
        if (idempotencyKey != null) {
            if (seenKeys.putIfAbsent(idempotencyKey, System.currentTimeMillis()) != null) {
                logger.debug("Duplicate webhook delivery ignored (idempotencyKey=$idempotencyKey)")
                call.respond(HttpStatusCode.OK, "OK")
                return@post
            }
        }

        // ── Parse & dispatch on the application's lifecycle scope ─────────────
        val event = try {
            json.decodeFromString<WebhookEvent>(rawBytes.decodeToString())
        } catch (e: Exception) {
            // Return 400 so Kapso can retry; a 200 would mark delivery as successful.
            logger.error("Failed to parse webhook payload: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Invalid payload")
            return@post
        }

        call.application.launch {
            handleEvent(event, agent)
        }

        call.respond(HttpStatusCode.OK, "OK")
    }

    // Health-check so Kapso can verify the endpoint is reachable
    get("/webhook") {
        call.respond(HttpStatusCode.OK, "WhatsApp bot webhook is running")
    }
}

private suspend fun handleEvent(event: WebhookEvent, agent: BotAgent) {
    val eventType = event.event ?: return

    logger.info("Handling event: $eventType")

    when (eventType) {
        "whatsapp.message.received" -> {
            val message = event.message ?: return
            val sender = message.from
                ?: event.conversation?.contact?.phoneNumber
                ?: run {
                    logger.warn("Cannot determine sender for received message")
                    return
                }

            logger.info("Processing inbound message (type=${message.type}) from $sender")
            agent.process(message, sessionId = sender)
        }

        "whatsapp.message.sent" ->
            logger.info("Message sent: ${event.message?.id}")

        "whatsapp.message.delivered" ->
            logger.info("Message delivered: ${event.message?.id}")

        "whatsapp.message.read" ->
            logger.info("Message read: ${event.message?.id}")

        "whatsapp.message.failed" ->
            logger.warn("Message failed: ${event.message?.id}")

        "whatsapp.conversation.created" ->
            logger.info("Conversation created: ${event.conversation?.id}")

        "whatsapp.conversation.ended" ->
            logger.info("Conversation ended: ${event.conversation?.id}")

        "whatsapp.conversation.inactive" ->
            logger.info("Conversation inactive: ${event.conversation?.id}")

        "whatsapp.phone_number.created" ->
            logger.info("New phone number connected: ${event.conversation?.phoneNumber?.number}")

        "whatsapp.phone_number.deleted" ->
            logger.info("Phone number removed: ${event.conversation?.phoneNumber?.number}")

        else -> logger.debug("Unhandled event type: $eventType")
    }
}

/**
 * Verify the HMAC-SHA256 signature sent by Kapso in `X-Webhook-Signature`.
 * Operates on the raw request bytes to avoid any charset encoding ambiguity.
 */
private fun verifySignature(payload: ByteArray, secret: String, receivedSignature: String): Boolean {
    return try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(payload)
        val received = hexToBytes(receivedSignature.removePrefix("sha256="))
        java.security.MessageDigest.isEqual(expected, received)
    } catch (e: Exception) {
        logger.error("Signature verification error: ${e.message}")
        false
    }
}

private fun hexToBytes(hex: String): ByteArray {
    if (hex.length % 2 != 0) return ByteArray(0)
    val data = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        data[i / 2] = ((hex[i].digitToInt(16) shl 4) + hex[i + 1].digitToInt(16)).toByte()
        i += 2
    }
    return data
}
