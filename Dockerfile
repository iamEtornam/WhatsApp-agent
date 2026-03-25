# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Use the official Gradle image that ships with JDK 17 to compile the project.
FROM gradle:8.10-jdk17 AS builder

WORKDIR /app

# Copy dependency descriptors first so Gradle's layer cache is reused when only
# source files change (not the build scripts).
COPY build.gradle.kts settings.gradle.kts ./

# Pre-fetch all dependencies (cache layer)
RUN gradle dependencies --no-daemon --quiet || true

# Copy the rest of the source and build the fat JAR
COPY src/ src/
RUN gradle shadowJar --no-daemon --quiet

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Eclipse Temurin 17 JRE slim is ~180 MB vs the full JDK (~600 MB).
FROM eclipse-temurin:17-jre-jammy AS runtime

# Non-root user for security hardening
RUN groupadd -r botgroup && useradd -r -g botgroup botuser
USER botuser

WORKDIR /app

# Copy only the assembled JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Document the port the Ktor server listens on (overridable via PORT env var)
EXPOSE 8080

# JVM flags tuned for container-aware memory management
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
