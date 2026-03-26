package com.whatsapp.bot.agent

import com.whatsapp.bot.kapso.InboundButtonReply
import com.whatsapp.bot.kapso.InboundInteractive
import com.whatsapp.bot.kapso.InboundListReply
import com.whatsapp.bot.kapso.InboundMessage
import com.whatsapp.bot.kapso.InboundText
import com.whatsapp.bot.kapso.MessageContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotAgentFormattingTest {

        @Test
        fun `format text message for llm`() {
                val formatted =
                        formatInboundMessageForLlm(
                                InboundMessage(
                                        type = "text",
                                        text = InboundText(body = "Write me a short story"),
                                )
                        )

                assertEquals("Write me a short story", formatted)
        }

        @Test
        fun `format interactive button reply with title and id`() {
                val formatted =
                        formatInboundMessageForLlm(
                                InboundMessage(
                                        type = "interactive",
                                        interactive =
                                                InboundInteractive(
                                                        type = "button_reply",
                                                        buttonReply =
                                                                InboundButtonReply(
                                                                        id = "fantasy",
                                                                        title = "Fantasy",
                                                                ),
                                                ),
                                        context = MessageContext(id = "wamid.button"),
                                )
                        )

                assertEquals(
                        "[User selected button \"Fantasy\" (id: fantasy)]\n(This is a reply to message wamid.button)",
                        formatted,
                )
        }

        @Test
        fun `format interactive list reply with description`() {
                val formatted =
                        formatInboundMessageForLlm(
                                InboundMessage(
                                        type = "interactive",
                                        interactive =
                                                InboundInteractive(
                                                        type = "list_reply",
                                                        listReply =
                                                                InboundListReply(
                                                                        id = "support",
                                                                        title = "Get Support",
                                                                        description =
                                                                                "Talk to a human",
                                                                ),
                                                ),
                                )
                        )

                assertEquals(
                        "[User selected list option \"Get Support\" (id: support), description: Talk to a human]",
                        formatted,
                )
        }

        @Test
        fun `interactive fallback no longer says generic type placeholder`() {
                val formatted =
                        formatInboundMessageForLlm(
                                InboundMessage(
                                        type = "interactive",
                                        interactive = InboundInteractive(type = "button_reply"),
                                )
                        )

                assertTrue(formatted.contains("interactive message"))
                assertTrue(!formatted.contains("[User sent a interactive message]"))
        }
}
