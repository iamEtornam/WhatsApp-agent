package com.whatsapp.bot.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebhookHandlerTest {

    @Test
    fun `decode single webhook payload`() {
        val events = decodeWebhookEvents(
            """
            {
              "message": {
                "id": "wamid.123",
                "timestamp": "1730092800",
                "type": "text",
                "text": { "body": "Hello" },
                "kapso": {
                  "direction": "inbound",
                  "status": "received",
                  "processing_status": "pending",
                  "origin": "cloud_api",
                  "has_media": false,
                  "content": "Hello"
                }
              },
              "conversation": {
                "id": "conv_123",
                "phone_number": "233503169330",
                "phone_number_id": "1022394497629051"
              },
              "is_new_conversation": true,
              "phone_number_id": "1022394497629051"
            }
            """.trimIndent(),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("wamid.123", event.message?.id)
        assertEquals("inbound", event.message?.kapso?.direction)
        assertEquals("Hello", event.message?.text?.body)
        assertEquals("233503169330", event.conversation?.phoneNumber)
        assertTrue(event.isNewConversation == true)
    }

    @Test
    fun `decode buffered batch webhook payload`() {
        val events = decodeWebhookEvents(
            """
            {
              "type": "whatsapp.message.received",
              "batch": true,
              "data": [
                {
                  "message": {
                    "id": "wamid.111",
                    "timestamp": "1730092801",
                    "type": "text",
                    "text": { "body": "First in batch" },
                    "kapso": {
                      "direction": "inbound",
                      "status": "received",
                      "processing_status": "pending",
                      "origin": "cloud_api"
                    }
                  },
                  "conversation": {
                    "id": "conv_123",
                    "phone_number": "233503169330",
                    "phone_number_id": "1022394497629051"
                  },
                  "is_new_conversation": false,
                  "phone_number_id": "1022394497629051"
                },
                {
                  "message": {
                    "id": "wamid.112",
                    "timestamp": "1730092802",
                    "type": "text",
                    "text": { "body": "Second in batch" },
                    "kapso": {
                      "direction": "inbound",
                      "status": "received",
                      "processing_status": "pending",
                      "origin": "cloud_api"
                    }
                  },
                  "conversation": {
                    "id": "conv_123",
                    "phone_number": "233503169330",
                    "phone_number_id": "1022394497629051"
                  },
                  "is_new_conversation": false,
                  "phone_number_id": "1022394497629051"
                }
              ],
              "batch_info": {
                "size": 2,
                "window_ms": 5000
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, events.size)
        assertEquals("wamid.111", events[0].message?.id)
        assertEquals("inbound", events[0].message?.kapso?.direction)
        assertEquals("First in batch", events[0].message?.text?.body)
        assertEquals("wamid.112", events[1].message?.id)
        assertEquals("Second in batch", events[1].message?.text?.body)
        assertFalse(events.any { it.message == null })
    }

    @Test
    fun `decode conversation only payload`() {
        val events = decodeWebhookEvents(
            """
            {
              "conversation": {
                "id": "conv_789",
                "phone_number": "233503169330",
                "status": "active",
                "phone_number_id": "1022394497629051"
              },
              "phone_number_id": "1022394497629051"
            }
            """.trimIndent(),
        )

        assertEquals(1, events.size)
        assertNotNull(events.single().conversation)
        assertEquals(null, events.single().message)
    }
}
