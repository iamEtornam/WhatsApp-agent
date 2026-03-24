package com.whatsapp.bot.config

import io.ktor.server.application.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Config")

data class KapsoConfig(
    val apiKey: String,
    val phoneNumberId: String,
    val webhookSecret: String,
    val baseUrl: String = "https://api.kapso.ai",
)

data class AppConfig(
    val kapso: KapsoConfig,
    val googleApiKey: String,
)

fun Application.loadConfig(): AppConfig {
    val kapsoApiKey = environment.config.propertyOrNull("kapso.apiKey")?.getString()
        ?: System.getenv("KAPSO_API_KEY")
        ?: error("KAPSO_API_KEY is required")

    val phoneNumberId = environment.config.propertyOrNull("kapso.phoneNumberId")?.getString()
        ?: System.getenv("KAPSO_PHONE_NUMBER_ID")
        ?: error("KAPSO_PHONE_NUMBER_ID is required")

    val webhookSecret = environment.config.propertyOrNull("kapso.webhookSecret")?.getString()
        ?: System.getenv("KAPSO_WEBHOOK_SECRET")
        ?: ""

    if (webhookSecret.isBlank()) {
        logger.warn("KAPSO_WEBHOOK_SECRET is not set – webhook signature verification is DISABLED. Anyone who knows your webhook URL can inject arbitrary messages.")
    }

    val baseUrl = environment.config.propertyOrNull("kapso.baseUrl")?.getString()
        ?: "https://api.kapso.ai"

    val googleApiKey = environment.config.propertyOrNull("koog.google.apikey")?.getString()
        ?: System.getenv("GOOGLE_API_KEY")
        ?: error("GOOGLE_API_KEY is required")

    return AppConfig(
        kapso = KapsoConfig(
            apiKey = kapsoApiKey,
            phoneNumberId = phoneNumberId,
            webhookSecret = webhookSecret,
            baseUrl = baseUrl,
        ),
        googleApiKey = googleApiKey,
    )
}
