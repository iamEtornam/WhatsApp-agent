# WhatsApp AI Bot

A production-ready WhatsApp bot powered by **Google Gemini 2.0 Flash** and the **Koog AI agent framework**. Built with Kotlin and Ktor, it handles real conversations, sends rich media, reacts to messages, and presents interactive button menus — all through the Kapso WhatsApp Cloud API.

---

## What It Does

When a WhatsApp user sends a message to your number, the bot:

1. Receives it via a secure webhook (HMAC-SHA256 verified)
2. Passes it to a per-user AI agent with full conversation memory
3. The agent reasons with Gemini 2.0 Flash and picks the right response action
4. Sends back a text reply, image, document, emoji reaction, or interactive button menu

The bot understands text, images, videos, audio clips, documents, locations, reactions, stickers, and contact cards — and always responds in the **same language the user writes in**.

---

## Architecture

```
WhatsApp User
     │
     ▼
 Kapso API  ──webhook──▶  POST /webhook
                               │
                         WebhookHandler
                         (sig verify + dedup)
                               │
                           BotAgent
                         (per-user memory)
                               │
                      Gemini 2.0 Flash (Koog)
                               │
                         WhatsAppTools
                    ┌──────────┴──────────┐
               sendText            sendButtons
               sendImage           sendReaction
               sendDocument
                               │
                           KapsoClient
                               │
                           Kapso API  ──▶  WhatsApp User
```

**Key design decisions:**

- **One agent per message** — no shared mutable state across concurrent users
- **Shared HTTP executor** — connection pool and TLS handshakes reused across invocations
- **Per-user chat memory** — conversation history keyed on phone number (window: 20 messages)
- **Webhook deduplication** — in-memory idempotency cache with 5-minute TTL
- **Isolated tool thread pool** — 2× CPU cores, separate from the shared IO dispatcher

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 17 or later |
| Gradle | Included via `./gradlew` wrapper |
| Kapso account | [app.kapso.ai](https://app.kapso.ai) |
| Google AI API key | [aistudio.google.com](https://aistudio.google.com/app/apikey) |

---

## Getting Started

### 1. Clone the repository

```bash
git clone <your-repo-url>
cd whatsapp-messenger
```

### 2. Configure environment variables

Copy the example file and fill in your credentials:

```bash
cp .env.example .env
```

Open `.env` and set each value:

```dotenv
# Google AI API key — powers the Gemini 2.0 Flash model
# Get yours at https://aistudio.google.com/app/apikey
GOOGLE_API_KEY=AIza...

# Kapso API credentials
# Find these in Kapso dashboard → Settings → API Keys
KAPSO_API_KEY=kap_...

# Your Kapso phone number ID
# Found in Kapso dashboard → Phone Numbers
KAPSO_PHONE_NUMBER_ID=647015955153740

# Webhook secret for HMAC-SHA256 signature verification (strongly recommended)
# Set the same value in Kapso dashboard → Webhooks → Secret
KAPSO_WEBHOOK_SECRET=your_webhook_secret_here

# Server port (optional, defaults to 8080)
PORT=8080
```

> **Getting your Kapso credentials**
> 1. Sign up at [app.kapso.ai](https://app.kapso.ai)
> 2. Connect a WhatsApp phone number in the dashboard
> 3. Navigate to **Settings → API Keys** to get `KAPSO_API_KEY`
> 4. Navigate to **Phone Numbers** to get `KAPSO_PHONE_NUMBER_ID`
> 5. Navigate to **Webhooks** to set and copy `KAPSO_WEBHOOK_SECRET`

---

### 3. Build the project

```bash
./gradlew build
```

This compiles the Kotlin source, runs tests, and produces a fat JAR.

---

### 4. Run the server

**Option A — Gradle run (development)**

Export your variables and run:

```bash
export GOOGLE_API_KEY=AIza...
export KAPSO_API_KEY=kap_...
export KAPSO_PHONE_NUMBER_ID=647015955153740
export KAPSO_WEBHOOK_SECRET=your_webhook_secret_here

./gradlew run
```

**Option B — Fat JAR (production)**

```bash
java -jar build/libs/whatsapp-bot-1.0.0.jar
```

**Option C — With a `.env` file (using `dotenv`)**

```bash
# macOS/Linux with dotenv-cli installed
dotenv ./gradlew run
```

The server starts on `http://0.0.0.0:8080` by default. You should see:

```
INFO  Application - Responding at http://0.0.0.0:8080
```

Verify it's running:

```bash
curl http://localhost:8080/
# → {"status":"ok"}
```

---

### 5. Expose your webhook

Kapso needs to reach your server over the internet. During development, use a tunneling tool:

```bash
# Using ngrok
ngrok http 8080

# Using cloudflared
cloudflare tunnel --url http://localhost:8080
```

Copy the public HTTPS URL (e.g. `https://abc123.ngrok.io`).

---

### 6. Register the webhook in Kapso

1. Open [app.kapso.ai](https://app.kapso.ai) → **Webhooks**
2. Set the webhook URL to: `https://<your-public-url>/webhook`
3. Paste your `KAPSO_WEBHOOK_SECRET` into the **Secret** field
4. Save and verify — Kapso will send a test ping to confirm reachability

---

## Project Structure

```
whatsapp-messenger/
├── .env.example                             # Environment variable template
├── .gitignore
├── build.gradle.kts                         # Gradle build (Kotlin DSL)
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/whatsapp/bot/
    │   ├── Main.kt                          # Entry point, Ktor server setup, routes
    │   ├── agent/
    │   │   ├── BotAgent.kt                  # AI agent orchestrator + conversation memory
    │   │   └── WhatsAppTools.kt             # Tools exposed to the LLM (send/react/etc.)
    │   ├── config/
    │   │   └── Config.kt                    # Config loading and validation
    │   ├── kapso/
    │   │   ├── KapsoClient.kt               # HTTP client for Kapso REST API
    │   │   └── KapsoModels.kt               # Request/response + webhook DTOs
    │   └── webhook/
    │       └── WebhookHandler.kt            # Webhook parsing, sig verification, dedup
    └── resources/
        ├── application.yaml                 # Ktor + API key configuration
        └── logback.xml                      # Logging configuration
```

---

## Available Bot Capabilities

The AI agent can invoke these actions autonomously based on context:

| Action | Description |
|--------|-------------|
| `sendTextReply` | Send a plain text message |
| `sendImage` | Send an image from a public URL with an optional caption |
| `sendDocument` | Send a file from a public URL with a display filename |
| `sendReaction` | React to the user's message with any emoji |
| `sendButtons` | Send a message with up to 3 quick-reply buttons |

**Inbound message types understood:**

Text · Images · Videos · Audio clips · Documents · Locations · Emoji reactions · Stickers · Contact cards

---

## Configuration Reference

All configuration lives in `src/main/resources/application.yaml` and is overridden by environment variables at runtime.

| Environment Variable | Required | Default | Description |
|---------------------|----------|---------|-------------|
| `GOOGLE_API_KEY` | Yes | — | Google AI API key for Gemini |
| `KAPSO_API_KEY` | Yes | — | Kapso API bearer token |
| `KAPSO_PHONE_NUMBER_ID` | Yes | — | Your Kapso phone number ID |
| `KAPSO_WEBHOOK_SECRET` | No | — | HMAC-SHA256 webhook secret |
| `PORT` | No | `8080` | HTTP server port |

---

## Logging

The bot uses [Logback](https://logback.qos.ch/) with the following log levels:

| Logger | Level |
|--------|-------|
| `com.whatsapp.bot` | `DEBUG` |
| Ktor, Koog | `INFO` |
| Root | `INFO` |

To change log levels, edit [`src/main/resources/logback.xml`](src/main/resources/logback.xml).

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.2.0 |
| HTTP Server | [Ktor](https://ktor.io/) 3.1.2 (Netty engine) |
| HTTP Client | Ktor CIO client |
| AI Framework | [Koog](https://github.com/JetBrains/koog) 0.7.1 |
| LLM | Google Gemini 2.0 Flash |
| Serialization | kotlinx.serialization 1.8.1 |
| Concurrency | Kotlin Coroutines 1.10.2 |
| Logging | Logback 1.5.18 |
| Build | Gradle 8+ (Kotlin DSL) |
| JVM | Java 17 |

---

## Webhook Security

Incoming webhook requests are verified using **HMAC-SHA256** signatures. Kapso signs each request with your `KAPSO_WEBHOOK_SECRET` and includes the signature in the `X-Kapso-Signature` header.

If `KAPSO_WEBHOOK_SECRET` is set, the handler rejects any request with an invalid or missing signature with `403 Forbidden`. If it is not set, verification is skipped (not recommended for production).

**Deduplication:** Each event carries a unique idempotency key. The webhook handler maintains an in-memory cache of recently processed keys (5-minute TTL) and silently discards duplicates.

---

## Development Tips

**Run with hot-reload (continuous build):**

```bash
./gradlew -t build &
./gradlew run
```

**View all available Gradle tasks:**

```bash
./gradlew tasks
```

**Run tests:**

```bash
./gradlew test
```

**Inspect the fat JAR contents:**

```bash
jar tf build/libs/whatsapp-bot-1.0.0.jar | grep "Main"
```
