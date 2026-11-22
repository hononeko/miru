# syntax=docker/dockerfile:1

# Build the SvelteKit static PWA bundle natively on the build host
FROM --platform=$BUILDPLATFORM oven/bun:1.4.2-alpine AS web-build
WORKDIR /web

COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile

COPY web/ ./
RUN bun run build

# Bytecode is platform-independent: build natively, even for a cross-platform image
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /src

# Copy build scripts and version catalog first so dependency resolution caches cleanly
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties .java-version ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null

# Only main sources: test and detekt config changes keep this layer and the build cached
COPY src/main/ src/main/
# Keep only the jar: deleting the build output and the Gradle caches it filled in the same RUN keeps them out of the
# layer, which the GitHub Actions layer cache exports on every source change
RUN ./gradlew --no-daemon shadowJar \
    && mv build/libs/miru-all.jar /miru-all.jar \
    && rm -rf build /root/.gradle

FROM eclipse-temurin:25.0.5_8-jre-noble
ARG VERSION=dev
LABEL org.opencontainers.image.title="miru" \
      org.opencontainers.image.description="Lightweight media server telemetry, live stream monitor, playback history tracker, and library stats engine" \
      org.opencontainers.image.source="https://github.com/hononeko/miru" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

RUN groupadd --system --gid 10001 miru \
    && useradd --system --uid 10001 --gid miru --no-create-home miru \
    && mkdir -p /data /opt/miru/static \
    && chown -R 10001:10001 /data /opt/miru

COPY --from=build --chown=10001:10001 /miru-all.jar /opt/miru/miru-all.jar
COPY --from=web-build --chown=10001:10001 /web/build /opt/miru/static

ENV MIRU_JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SQLITE_PATH="/data/miru.db" \
    STATIC_WEB_DIR="/opt/miru/static"

USER 10001:10001
WORKDIR /data
EXPOSE 8080

ENTRYPOINT ["/bin/sh", "-c", "exec java --enable-native-access=ALL-UNNAMED $MIRU_JAVA_OPTS -jar /opt/miru/miru-all.jar"]
