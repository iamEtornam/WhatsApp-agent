package com.whatsapp.bot.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.whatsapp.bot.kapso.InboundInteractive
import com.whatsapp.bot.kapso.InboundMessage
import com.whatsapp.bot.kapso.KapsoClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

private val SYSTEM_PROMPT =
        """
You are a helpful WhatsApp assistant bot. You can:
- Answer questions and have conversations
- Help users with information and tasks
- Send images, documents, and reaction emojis when helpful
- Present options as quick-reply button menus (max 3 buttons)

When a user sends you a file or image, acknowledge it and describe what you can see or do with it.
When responding, keep messages concise and WhatsApp-friendly (avoid long markdown blocks).
Always be friendly, helpful, and respond in the same language the user writes in.
""".trimIndent()

/**
 * Manages per-user Koog [AIAgent] instances backed by chat memory.
 *
 * A **new** [AIAgent] (and its [WhatsAppTools]) is constructed for every [process] call,
 * eliminating shared mutable state across concurrent coroutines. The prompt executor (and its
 * underlying HTTP client) is created once and reused for the lifetime of this [BotAgent] to avoid
 * per-message connection pool and TLS handshake overhead.
 *
 * Chat memory is keyed on [sessionId] (the sender's phone number) so conversation history persists
 * across turns.
 */
class BotAgent(
        googleApiKey: String,
        private val kapsoClient: KapsoClient,
) {
    private val logger = LoggerFactory.getLogger(BotAgent::class.java)
    private val sharedChatHistoryProvider = InMemoryChatHistoryProvider()

    // Created once; the underlying Ktor HttpClient and thread pool are reused
    // across all agent invocations.
    private val executor = simpleGoogleAIExecutor(googleApiKey)

    /**
     * Process an inbound WhatsApp message.
     *
     * On any unhandled failure the sender receives a fallback error message so the conversation is
     * never silently dropped.
     */
    suspend fun process(message: InboundMessage, sessionId: String) {
        val from = message.from ?: sessionId

        // Fresh tools + agent per invocation: no shared mutable state.
        val tools = WhatsAppTools(kapsoClient, recipient = from)
        val agent = buildAgent(tools)

        val userInput = buildUserInput(message)
        logger.info("Processing message from $from (session=$sessionId): $userInput")

        try {
            val reply = withTimeout(30_000) { agent.run(userInput, sessionId) }
            if (!tools.didSendReply() && reply.isNotBlank()) {
                kapsoClient.sendText(from, reply.trim())
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Agent timed out processing message from $from", e)
            kapsoClient.sendText(
                    from,
                    "Sorry, I'm taking longer than expected. Please try again in a moment."
            )
        } catch (e: Exception) {
            logger.error("Agent error processing message from $from", e)
            // Inform the user rather than silently dropping the message.
            try {
                kapsoClient.sendText(from, "Sorry, something went wrong. Please try again.")
            } catch (sendError: Exception) {
                logger.error("Failed to send error reply to $from", sendError)
            }
        }
    }

    private fun buildAgent(toolSet: WhatsAppTools): AIAgent<String, String> =
            AIAgent(
                    promptExecutor = executor,
                    systemPrompt = SYSTEM_PROMPT,
                    llmModel = GoogleModels.Gemini3_Flash_Preview,
                    temperature = 0.7,
                    maxIterations = 10,
                    toolRegistry = ToolRegistry { tools(toolSet) },
            ) {
                install(ChatMemory) {
                    chatHistoryProvider = this@BotAgent.sharedChatHistoryProvider
                    windowSize(20)
                }
            }

    private fun buildUserInput(message: InboundMessage): String =
            formatInboundMessageForLlm(message)
}

internal fun formatInboundMessageForLlm(message: InboundMessage): String = buildString {
    val mediaData = message.kapso?.mediaData

    when (message.type) {
        "text" -> append(message.text?.body ?: message.kapso?.content ?: "(empty text)")
        "image" -> {
            append("[User sent an image")
            message.image?.caption?.takeIf { it.isNotBlank() }?.let { append(" with caption: $it") }
            mediaData?.let { md ->
                append(". Media URL: ${md.url}")
                md.filename?.let { append(", filename: $it") }
                md.contentType?.let { append(", type: $it") }
            }
            append("]")
        }
        "video" -> {
            append("[User sent a video")
            message.video?.caption?.takeIf { it.isNotBlank() }?.let { append(" with caption: $it") }
            mediaData?.url?.let { append(". Media URL: $it") }
            append("]")
        }
        "audio" -> {
            append("[User sent a voice message / audio clip")
            mediaData?.url?.let { append(". Media URL: $it") }
            message.kapso?.transcript?.text?.let { append(". Transcript: $it") }
            append("]")
        }
        "document" -> {
            append("[User sent a document")
            val name = message.document?.filename ?: mediaData?.filename
            name?.let { append(": $it") }
            message.document?.caption?.takeIf { it.isNotBlank() }?.let { append(" (caption: $it)") }
            mediaData?.url?.let { append(". Media URL: $it") }
            append("]")
        }
        "location" -> {
            val loc = message.location
            append("[User shared a location: lat=${loc?.latitude}, lon=${loc?.longitude}")
            loc?.name?.let { append(", name: $it") }
            loc?.address?.let { append(", address: $it") }
            append("]")
        }
        "reaction" ->
                append(
                        "[User reacted with '${message.reaction?.emoji}' to message ${message.reaction?.messageId}]"
                )
        "interactive" -> append(formatInteractiveMessage(message.interactive))
        "sticker" -> append("[User sent a sticker]")
        "contacts" -> append("[User shared a contact card]")
        else -> append("[User sent a ${message.type ?: "unknown"} message]")
    }

    message.context?.id?.let { append("\n(This is a reply to message $it)") }
}

private fun formatInteractiveMessage(interactive: InboundInteractive?): String {
    if (interactive == null) {
        return "[User sent an interactive message]"
    }

    interactive.buttonReply?.let { reply ->
        val title = reply.title ?: "unknown"
        val id = reply.id ?: "unknown"
        return "[User selected button \"$title\" (id: $id)]"
    }

    interactive.listReply?.let { reply ->
        val title = reply.title ?: "unknown"
        val id = reply.id ?: "unknown"
        return buildString {
            append("[User selected list option \"$title\" (id: $id)")
            reply.description?.takeIf { it.isNotBlank() }?.let { append(", description: $it") }
            append("]")
        }
    }

    return "[User sent an interactive message]"
}
