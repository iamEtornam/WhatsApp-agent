package com.whatsapp.bot.agent

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message

/**
 * Sanitizes the chat history message list to ensure valid turn ordering for the Google Gemini API.
 *
 * Gemini requires that a function-call turn (model role with function calls) comes immediately
 * after a user turn or a function-response turn. When [WindowSizePreProcessor]
 * [ai.koog.agents.chatMemory.feature.WindowSizePreProcessor] truncates the history, it can cut in
 * the middle of a tool-call / tool-result sequence, producing orphaned turns that violate this
 * constraint.
 *
 * This preprocessor strips leading messages until the list starts with a [Message.User], ensuring
 * the remaining sequence is valid for Gemini.
 */
class GeminiTurnOrderPreProcessor : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages

        val system = messages.takeWhile { it is Message.System }
        var content = messages.drop(system.size)

        content = content.dropWhile { it !is Message.User }

        content = stripOrphanedToolResults(content)

        return system + content
    }

    private fun stripOrphanedToolResults(messages: List<Message>): List<Message> {
        val result = mutableListOf<Message>()
        for (msg in messages) {
            if (msg is Message.Tool.Result) {
                val prev = result.lastOrNull()
                if (prev !is Message.Tool.Call && prev !is Message.Tool.Result) continue
            }
            if (msg is Message.Tool.Call) {
                val prev = result.lastOrNull()
                if (prev != null && prev !is Message.User && prev !is Message.Tool.Result) continue
            }
            result.add(msg)
        }
        return result
    }
}
