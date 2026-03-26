package com.whatsapp.bot.kapso

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─── Outbound message models ────────────────────────────────────────────────

@Serializable data class TextBody(val body: String)

@Serializable
data class SendTextRequest(
        @SerialName("messaging_product") val messagingProduct: String = "whatsapp",
        @SerialName("recipient_type") val recipientType: String = "individual",
        val to: String,
        val type: String = "text",
        val text: TextBody,
)

@Serializable
data class MediaBody(
        val id: String? = null,
        val link: String? = null,
        val caption: String? = null,
        val filename: String? = null,
)

@Serializable
data class SendMediaRequest(
        @SerialName("messaging_product") val messagingProduct: String = "whatsapp",
        @SerialName("recipient_type") val recipientType: String = "individual",
        val to: String,
        val type: String, // "image", "video", "audio", "document"
        val image: MediaBody? = null,
        val video: MediaBody? = null,
        val audio: MediaBody? = null,
        val document: MediaBody? = null,
)

@Serializable
data class ReactionBody(
        @SerialName("message_id") val messageId: String,
        val emoji: String,
)

@Serializable
data class SendReactionRequest(
        @SerialName("messaging_product") val messagingProduct: String = "whatsapp",
        val to: String,
        val type: String = "reaction",
        val reaction: ReactionBody,
)

@Serializable
data class InteractiveButton(
        val type: String = "reply",
        val reply: ButtonReply,
)

@Serializable data class ButtonReply(val id: String, val title: String)

@Serializable data class InteractiveBody(val text: String)

@Serializable data class InteractiveAction(val buttons: List<InteractiveButton>)

@Serializable
data class InteractivePayload(
        val type: String = "button",
        val body: InteractiveBody,
        val action: InteractiveAction,
)

@Serializable
data class SendInteractiveRequest(
        @SerialName("messaging_product") val messagingProduct: String = "whatsapp",
        val to: String,
        val type: String = "interactive",
        val interactive: InteractivePayload,
)

@Serializable
data class SendMessageResponse(
        @SerialName("messaging_product") val messagingProduct: String? = null,
        val contacts: List<MessageContact>? = null,
        val messages: List<MessageId>? = null,
)

@Serializable
data class MessageContact(
        val input: String? = null,
        @SerialName("wa_id") val waId: String? = null,
)

@Serializable data class MessageId(val id: String? = null)

// ─── Media upload/download models ───────────────────────────────────────────

@Serializable data class UploadMediaResponse(val id: String)

@Serializable
data class MediaUrlResponse(
        val id: String? = null,
        val url: String? = null,
        @SerialName("mime_type") val mimeType: String? = null,
        @SerialName("sha256") val sha256: String? = null,
        @SerialName("file_size") val fileSize: Long? = null,
)

// ─── Inbound webhook models ──────────────────────────────────────────────────

@Serializable
data class WebhookEvent(
        val message: InboundMessage? = null,
        val conversation: WebhookConversation? = null,
        @SerialName("is_new_conversation") val isNewConversation: Boolean? = null,
        @SerialName("phone_number_id") val phoneNumberId: String? = null,
)

@Serializable
data class BatchedWebhookPayload(
        val type: String? = null,
        val batch: Boolean? = null,
        val data: List<WebhookEvent> = emptyList(),
)

@Serializable
data class MessageKapso(
        val direction: String? = null,
        val status: String? = null,
        @SerialName("processing_status") val processingStatus: String? = null,
        val origin: String? = null,
        @SerialName("has_media") val hasMedia: Boolean? = null,
        val content: String? = null,
        val transcript: MessageTranscript? = null,
        @SerialName("media_url") val mediaUrl: String? = null,
        @SerialName("media_data") val mediaData: MediaData? = null,
)

@Serializable data class MessageTranscript(val text: String? = null)

@Serializable
data class InboundMessage(
        val id: String? = null,
        val timestamp: String? = null,
        val type: String? = null,
        val text: InboundText? = null,
        val image: InboundMedia? = null,
        val video: InboundMedia? = null,
        val audio: InboundMedia? = null,
        val document: InboundMedia? = null,
        val sticker: InboundMedia? = null,
        val location: InboundLocation? = null,
        val reaction: InboundReaction? = null,
        val contacts: List<JsonElement>? = null,
        val interactive: InboundInteractive? = null,
        val kapso: MessageKapso? = null,
        val context: MessageContext? = null,
        val from: String? = null,
        val to: String? = null,
)

@Serializable
data class InboundInteractive(
        val type: String? = null,
        @SerialName("button_reply") val buttonReply: InboundButtonReply? = null,
        @SerialName("list_reply") val listReply: InboundListReply? = null,
        @SerialName("nfm_reply") val nfmReply: JsonElement? = null,
)

@Serializable
data class InboundButtonReply(
        val id: String? = null,
        val title: String? = null,
)

@Serializable
data class InboundListReply(
        val id: String? = null,
        val title: String? = null,
        val description: String? = null,
)

@Serializable data class InboundText(val body: String? = null)

@Serializable
data class InboundMedia(
        val id: String? = null,
        val caption: String? = null,
        val filename: String? = null,
        @SerialName("mime_type") val mimeType: String? = null,
        val sha256: String? = null,
)

@Serializable
data class MediaData(
        val url: String? = null,
        val filename: String? = null,
        @SerialName("content_type") val contentType: String? = null,
        @SerialName("byte_size") val byteSize: Long? = null,
)

@Serializable
data class InboundLocation(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val name: String? = null,
        val address: String? = null,
)

@Serializable
data class InboundReaction(
        @SerialName("message_id") val messageId: String? = null,
        val emoji: String? = null,
)

@Serializable
data class MessageContext(
        @SerialName("from") val from: String? = null,
        val id: String? = null,
)

@Serializable
data class WebhookConversation(
        val id: String? = null,
        @SerialName("phone_number") val phoneNumber: String? = null,
        @SerialName("phone_number_id") val phoneNumberId: String? = null,
        @SerialName("contact_name") val contactName: String? = null,
        val status: String? = null,
        val kapso: ConversationKapso? = null,
)

@Serializable
data class ConversationKapso(
        @SerialName("contact_name") val contactName: String? = null,
        @SerialName("messages_count") val messagesCount: Int? = null,
        @SerialName("last_message_text") val lastMessageText: String? = null,
        @SerialName("last_inbound_at") val lastInboundAt: String? = null,
        @SerialName("last_outbound_at") val lastOutboundAt: String? = null,
)
