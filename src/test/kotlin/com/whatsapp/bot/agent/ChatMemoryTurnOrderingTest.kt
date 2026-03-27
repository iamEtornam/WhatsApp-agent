package com.whatsapp.bot.agent

import ai.koog.agents.chatMemory.feature.WindowSizePreProcessor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces the Gemini API "INVALID_ARGUMENT" error:
 *
 * "Please ensure that function call turn comes immediately after
 * ```
 *    a user turn or after a function response turn."
 * ```
 * Root cause: [WindowSizePreProcessor] truncates the stored chat history with a simple
 * `takeLast(n)`. When the window boundary falls inside a tool-call / tool-result sequence the
 * resulting message list starts with orphaned [Message.Tool.Call] or [Message.Tool.Result]
 * messages. The Koog [GoogleLLMClient] converts these into Google `Content` turns that violate
 * Gemini's strict turn-ordering rules.
 */
class ChatMemoryTurnOrderingTest {

    private fun user(text: String): Message.User =
            Message.User(content = text, metaInfo = RequestMetaInfo.Empty)

    private fun assistant(text: String): Message.Assistant =
            Message.Assistant(content = text, metaInfo = ResponseMetaInfo.Empty)

    private fun toolCall(id: String, tool: String, args: String = "{}"): Message.Tool.Call =
            Message.Tool.Call(
                    id = id,
                    tool = tool,
                    content = args,
                    metaInfo = ResponseMetaInfo.Empty
            )

    private fun toolResult(id: String, tool: String, result: String): Message.Tool.Result =
            Message.Tool.Result(
                    id = id,
                    tool = tool,
                    content = result,
                    metaInfo = RequestMetaInfo.Empty
            )

    /**
     * Simulates a realistic multi-turn conversation where the bot used tools.
     *
     * The history contains 12 messages. Using a window of 5 forces the window to start at the
     * Tool.Call message, which becomes the first content turn sent to Gemini – triggering the API
     * error.
     */
    private fun buildConversationWithToolCalls(): List<Message> =
            listOf(
                    user("Hello!"),
                    assistant("Hi there! How can I help?"),
                    user("What can you do?"),
                    assistant("I can answer questions, send images, and more!"),
                    user("Send me a greeting"),
                    toolCall("call-1", "sendTextReply", """{"message":"Hello from the bot!"}"""),
                    toolResult("call-1", "sendTextReply", "Message sent successfully"),
                    assistant("I've sent you a greeting!"),
                    user("Now send me buttons"),
                    toolCall(
                            "call-2",
                            "sendButtons",
                            """{"message":"Pick one","buttons":"a:A,b:B"}"""
                    ),
                    toolResult("call-2", "sendButtons", "Buttons sent successfully"),
                    user("Thanks!"),
            )

    /**
     * Validates that a message list satisfies Google Gemini's turn-ordering invariant for function
     * calls.
     *
     * Rules enforced:
     * 1. A [Message.Tool.Call] (model function-call turn) must be preceded
     * ```
     *    by a [Message.User] or another [Message.Tool.Result].
     * ```
     * 2. A [Message.Tool.Result] (function-response turn) must be preceded
     * ```
     *    by a [Message.Tool.Call] or another [Message.Tool.Result].
     * ```
     * 3. The first non-system message must be a [Message.User].
     */
    private fun validateGeminiTurnOrdering(messages: List<Message>): List<String> {
        val errors = mutableListOf<String>()
        val nonSystem = messages.filter { it !is Message.System }

        if (nonSystem.isEmpty()) return errors

        val first = nonSystem.first()
        if (first !is Message.User) {
            errors.add(
                    "First non-system message must be User but was ${first::class.simpleName}: " +
                            "'${first.content.take(60)}'"
            )
        }

        for (i in 1 until nonSystem.size) {
            val prev = nonSystem[i - 1]
            val curr = nonSystem[i]

            if (curr is Message.Tool.Call) {
                val validPredecessor = prev is Message.User || prev is Message.Tool.Result
                if (!validPredecessor) {
                    errors.add(
                            "Tool.Call at index $i must follow User or Tool.Result " +
                                    "but follows ${prev::class.simpleName}: '${prev.content.take(60)}'"
                    )
                }
            }

            if (curr is Message.Tool.Result) {
                val validPredecessor = prev is Message.Tool.Call || prev is Message.Tool.Result
                if (!validPredecessor) {
                    errors.add(
                            "Tool.Result at index $i must follow Tool.Call or Tool.Result " +
                                    "but follows ${prev::class.simpleName}: '${prev.content.take(60)}'"
                    )
                }
            }
        }

        return errors
    }

    // -----------------------------------------------------------------------
    // Bug reproduction
    // -----------------------------------------------------------------------

    @Test
    fun `full conversation has valid turn ordering`() {
        val history = buildConversationWithToolCalls()
        val errors = validateGeminiTurnOrdering(history)
        assertTrue(errors.isEmpty(), "Full history should be valid but had: $errors")
    }

    @Test
    fun `window size preprocessor breaks turn ordering when it cuts mid-tool-sequence`() {
        val history = buildConversationWithToolCalls()

        val windowed = WindowSizePreProcessor(windowSize = 5).preprocess(history)

        assertEquals(5, windowed.size)

        val errors = validateGeminiTurnOrdering(windowed)
        assertTrue(
                errors.isNotEmpty(),
                "Windowed history should violate Gemini turn ordering (reproducing the bug)"
        )
        assertTrue(
                errors.any { it.contains("Tool.Call") || it.contains("First non-system") },
                "Error should mention Tool.Call ordering or invalid first message: $errors"
        )
    }

    @Test
    fun `window starting at tool result also breaks turn ordering`() {
        val history = buildConversationWithToolCalls()

        val windowed = WindowSizePreProcessor(windowSize = 6).preprocess(history)

        assertEquals(6, windowed.size)

        val errors = validateGeminiTurnOrdering(windowed)
        assertTrue(
                errors.isNotEmpty(),
                "Windowed history starting at Tool.Result should violate turn ordering: " +
                        "first message is ${windowed.firstOrNull()?.let { it::class.simpleName }}"
        )
    }

    @Test
    fun `window size 3 from end breaks when starting with orphaned tool call`() {
        val history = buildConversationWithToolCalls()

        val windowed = WindowSizePreProcessor(windowSize = 3).preprocess(history)

        assertEquals(3, windowed.size)
        assertTrue(
                windowed.first() is Message.Tool.Call,
                "Window of 3 should start with Tool.Call but starts with " +
                        "${windowed.first()::class.simpleName}"
        )

        val errors = validateGeminiTurnOrdering(windowed)
        assertTrue(
                errors.isNotEmpty(),
                "Window of 3 should break ordering. First msg: " +
                        "${windowed.firstOrNull()?.let { it::class.simpleName }}"
        )
    }

    @Test
    fun `window size 7 breaks when starting with tool call from first tool sequence`() {
        val history = buildConversationWithToolCalls()

        val windowed = WindowSizePreProcessor(windowSize = 7).preprocess(history)

        assertEquals(7, windowed.size)
        assertTrue(windowed.first() is Message.Tool.Call, "Window of 7 should start with Tool.Call")

        val errors = validateGeminiTurnOrdering(windowed)
        assertTrue(
                errors.isNotEmpty(),
                "Window of 7 should break ordering due to orphaned Tool.Call at start"
        )
    }

    // -----------------------------------------------------------------------
    // Tests for the fix (will pass once GeminiTurnOrderPreProcessor is added)
    // -----------------------------------------------------------------------

    @Test
    fun `sanitized history has valid turn ordering after window truncation at size 5`() {
        val history = buildConversationWithToolCalls()
        val windowed = WindowSizePreProcessor(windowSize = 5).preprocess(history)

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(windowed)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(
                errors.isEmpty(),
                "Sanitized history should have valid ordering but had: $errors"
        )
        assertTrue(sanitized.isNotEmpty(), "Sanitized history should not be empty")
    }

    @Test
    fun `sanitized history has valid turn ordering after window truncation at size 6`() {
        val history = buildConversationWithToolCalls()
        val windowed = WindowSizePreProcessor(windowSize = 6).preprocess(history)

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(windowed)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(
                errors.isEmpty(),
                "Sanitized history should have valid ordering but had: $errors"
        )
    }

    @Test
    fun `sanitized history has valid turn ordering after window truncation at size 3`() {
        val history = buildConversationWithToolCalls()
        val windowed = WindowSizePreProcessor(windowSize = 3).preprocess(history)

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(windowed)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(
                errors.isEmpty(),
                "Sanitized history should have valid ordering but had: $errors"
        )
    }

    @Test
    fun `sanitized history has valid turn ordering after window truncation at size 7`() {
        val history = buildConversationWithToolCalls()
        val windowed = WindowSizePreProcessor(windowSize = 7).preprocess(history)

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(windowed)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(
                errors.isEmpty(),
                "Sanitized history should have valid ordering but had: $errors"
        )
    }

    @Test
    fun `sanitizing a valid history is a no-op`() {
        val history = buildConversationWithToolCalls()

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(history)

        assertEquals(history.size, sanitized.size, "Valid history should not be modified")
        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(errors.isEmpty(), "Should remain valid: $errors")
    }

    @Test
    fun `sanitizer preserves messages when window boundary is clean`() {
        val history = buildConversationWithToolCalls()
        val windowed = WindowSizePreProcessor(windowSize = 8).preprocess(history)

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(windowed)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(errors.isEmpty(), "Clean window boundary should produce valid history: $errors")
    }

    @Test
    fun `sanitizer handles empty history`() {
        val sanitized = GeminiTurnOrderPreProcessor().preprocess(emptyList())
        assertTrue(sanitized.isEmpty())
    }

    @Test
    fun `sanitizer handles history with only tool messages`() {
        val history =
                listOf(
                        toolCall("c1", "sendTextReply", """{"message":"hi"}"""),
                        toolResult("c1", "sendTextReply", "sent"),
                )

        val sanitized = GeminiTurnOrderPreProcessor().preprocess(history)

        val errors = validateGeminiTurnOrdering(sanitized)
        assertTrue(errors.isEmpty(), "All-tool history should be sanitized: $errors")
    }
}
