package com.whatsapp.bot.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.whatsapp.bot.kapso.KapsoClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Koog [ToolSet] that exposes WhatsApp sending capabilities to the AI agent.
 *
 * Each instance is bound to a single [recipient] and a single request – never shared across
 * concurrent coroutines.
 */
/**
 * Dedicated thread pool for tool HTTP calls. Sized to 2× available processors, isolated from
 * [Dispatchers.IO] so tool calls cannot starve webhook or agent coroutines on the shared IO pool.
 */
private val toolDispatcher =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)
                .asCoroutineDispatcher()

@LLMDescription("Tools for sending WhatsApp messages and media back to users")
class WhatsAppTools(
        private val kapso: KapsoClient,
        /** Immutable per-instance recipient – set once at construction time. */
        private val recipient: String,
) : ToolSet {

    private val logger = LoggerFactory.getLogger(WhatsAppTools::class.java)
    private val sentReply = AtomicBoolean(false)

    // Koog's @Tool processor requires plain (non-suspend) functions.
    // We use a dedicated fixed-size thread pool rather than Dispatchers.IO to
    // bound the number of threads that can be pinned by concurrent tool calls,
    // preventing starvation of the shared IO dispatcher.
    private fun <T> io(block: suspend () -> T): T = runBlocking(toolDispatcher) { block() }

    fun didSendReply(): Boolean = sentReply.get()

    @Tool
    @LLMDescription("Send a plain text reply to the current WhatsApp user")
    fun sendTextReply(
            @LLMDescription("The text message body to send") message: String,
    ): String {
        logger.debug("sendTextReply -> $recipient: $message")
        return try {
            io { kapso.sendText(recipient, message) }
            sentReply.set(true)
            "Message sent successfully"
        } catch (e: Exception) {
            "Failed to send message: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Send an image to the current WhatsApp user using a public URL")
    fun sendImage(
            @LLMDescription("Publicly accessible URL of the image to send") imageUrl: String,
            @LLMDescription("Optional caption for the image") caption: String = "",
    ): String {
        logger.debug("sendImage -> $recipient: $imageUrl")
        return try {
            io { kapso.sendImage(recipient, url = imageUrl, caption = caption.ifBlank { null }) }
            sentReply.set(true)
            "Image sent successfully"
        } catch (e: Exception) {
            "Failed to send image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Send a document or file to the current WhatsApp user using a public URL")
    fun sendDocument(
            @LLMDescription("Publicly accessible URL of the document to send") documentUrl: String,
            @LLMDescription("Display filename shown to the recipient") filename: String,
            @LLMDescription("Optional caption for the document") caption: String = "",
    ): String {
        logger.debug("sendDocument -> $recipient: $documentUrl")
        return try {
            io {
                kapso.sendDocument(
                        recipient,
                        url = documentUrl,
                        filename = filename,
                        caption = caption.ifBlank { null },
                )
            }
            sentReply.set(true)
            "Document sent successfully"
        } catch (e: Exception) {
            "Failed to send document: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("React to the user's message with an emoji")
    fun sendReaction(
            @LLMDescription("The WhatsApp message ID to react to") messageId: String,
            @LLMDescription("The emoji character to react with, e.g. 👍") emoji: String,
    ): String {
        logger.debug("sendReaction -> $recipient, msg=$messageId, emoji=$emoji")
        return try {
            io { kapso.sendReaction(recipient, messageId, emoji) }
            sentReply.set(true)
            "Reaction sent successfully"
        } catch (e: Exception) {
            "Failed to send reaction: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
            "Send a message with up to 3 quick-reply buttons. " +
                    "Provide buttons as a comma-separated list of 'id:label' pairs, e.g. 'yes:Yes,no:No,maybe:Maybe'",
    )
    fun sendButtons(
            @LLMDescription("The body text of the message shown above the buttons") message: String,
            @LLMDescription("Comma-separated button definitions in 'id:label' format, max 3")
            buttons: String,
    ): String {
        logger.debug("sendButtons -> $recipient")
        return try {
            val parsed =
                    buttons.split(",").map { pair ->
                        val parts = pair.trim().split(":", limit = 2)
                        require(parts.size == 2) { "Each button must be in 'id:label' format" }
                        parts[0].trim() to parts[1].trim()
                    }
            io { kapso.sendButtons(recipient, message, parsed) }
            sentReply.set(true)
            "Buttons sent successfully"
        } catch (e: Exception) {
            "Failed to send buttons: ${e.message}"
        }
    }
}
