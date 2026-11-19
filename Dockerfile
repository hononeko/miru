# syntax=docker/dockerfile:1

# Bytecode is platform-independent: build natively, even for a cross-platform image
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /src

# Copy build scripts and version catalog first so dependency resolution caches cleanly
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties .java-version ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null

COPY config/ config/
COPY src/ src/
RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:25.0.5_8-jre-noble
ARG VERSION=dev
LABEL org.opencontainers.image.title="miru" \
      org.opencontainers.image.description="Lightweight media server telemetry, live stream monitor, playback history tracker, and library stats engine" \
      org.opencontainers.image.source="https://github.com/hononeko/miru" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

RUN groupadd --system --gid 10001 miru \
    && useradd --system --uid 10001 --gid miru --no-create-home miru \
    && mkdir -p /data /opt/miru \
    && chown -R 10001:10001 /data /opt/miru

COPY --from=build --chown=10001:10001 /src/build/libs/miru-all.jar /opt/miru/miru-all.jar

ENV MIRU_JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SQLITE_PATH="/data/miru.db"

USER 10001:10001
WORKDIR /data
EXPOSE 8080

ENTRYPOINT ["/bin/sh", "-c", "exec java --enable-native-access=ALL-UNNAMED $MIRU_JAVA_OPTS -jar /opt/miru/miru-all.jar"]
