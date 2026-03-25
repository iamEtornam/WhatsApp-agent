plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.whatsapp.bot"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/maven")
}

val ktorVersion = "3.1.2"
val koogVersion = "0.7.1"
val kotlinxSerializationVersion = "1.8.1"
val logbackVersion = "1.5.18"

dependencies {
    // Koog AI Agent framework
    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:agents-features-memory:$koogVersion")

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Ktor client (for calling Kapso REST API)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Test
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.whatsapp.bot.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("whatsapp-bot")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "com.whatsapp.bot.MainKt")
    }
    // Merge service files so Ktor/Netty service loaders are not trampled
    mergeServiceFiles()
}
