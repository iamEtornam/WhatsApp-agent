package com.whatsapp.bot

import com.whatsapp.bot.agent.BotAgent
import com.whatsapp.bot.config.loadConfig
import com.whatsapp.bot.kapso.KapsoClient
import com.whatsapp.bot.webhook.webhookRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Main")

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val config = loadConfig()

    val kapsoClient = KapsoClient(config.kapso)
    val agent = BotAgent(
        googleApiKey = config.googleApiKey,
        kapsoClient = kapsoClient,
    )

    installPlugins()

    routing {
        // Health-check
        get("/") {
            call.respondText("WhatsApp Bot is running!", ContentType.Text.Plain)
        }

        // WhatsApp webhook endpoint
        webhookRoutes(agent, config.kapso.webhookSecret)
    }

    logger.info("WhatsApp bot started on port ${environment.config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
    logger.info("Webhook endpoint: POST /webhook")
}

private fun Application.installPlugins() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal server error"))
            )
        }
    }
}
