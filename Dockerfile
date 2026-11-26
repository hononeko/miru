# syntax=docker/dockerfile:1

# Build the SvelteKit static PWA bundle natively on the build host
FROM --platform=$BUILDPLATFORM oven/bun:1.4.5-alpine AS web-build
WORKDIR /web

COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile

COPY web/ ./
RUN bun run build

# Bytecode is platform-independent: build natively, even for a cross-platform image
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /src

# Copy only the build scripts and version catalog, then download every jar the build needs. This layer changes only
# with the build scripts, so a source-only change reuses it from the layer cache and builds without downloading
# anything (Maven Central rate-limits CI runners that download on every build).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties .java-version ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --quiet downloadDependencies

# Only main sources: test and detekt config changes keep this layer and the build cached
COPY src/main/ src/main/
# Keep only the jar: deleting the build output and the Gradle caches it filled in the same RUN keeps them out of the
# layer, which the GitHub Actions layer cache exports on every source change
RUN ./gradlew --no-daemon shadowJar \
    && mv build/libs/miru-all.jar /miru-all.jar \
    && rm -rf build /root/.gradle

# Pre-extract the target architecture's SQLite native library into a read-only directory and strip
# unused cross-platform native libraries (other OS/arches + Netty HTTP/3 Quiche) from the container JAR.
# This lets runtime containers mount /tmp with noexec,nosuid,nodev while shrinking the image.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.5.1_2-jdk-noble AS app-prepare
ARG TARGETARCH
WORKDIR /work
COPY --from=build /miru-all.jar /in/miru-all.jar
RUN set -eu; \
    case "${TARGETARCH}" in \
        amd64) sqlite_arch="x86_64" ;; \
        arm64) sqlite_arch="aarch64" ;; \
        *) echo "Unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    mkdir -p /out/native; \
    jar xf /in/miru-all.jar; \
    grep -q "^Main-Class: app.hononeko.miru.ApplicationKt" META-INF/MANIFEST.MF; \
    cp "org/sqlite/native/Linux/${sqlite_arch}/libsqlitejdbc.so" /out/native/libsqlitejdbc.so; \
    chmod 0555 /out/native/libsqlitejdbc.so; \
    rm -rf org/sqlite/native META-INF/native/libnetty_quiche42_* META-INF/native/netty_quiche42_*; \
    mv META-INF/MANIFEST.MF /tmp/MANIFEST.MF; \
    jar cfm /out/miru-all.jar /tmp/MANIFEST.MF .; \
    chmod 0444 /out/miru-all.jar; \
    rm -rf /work/* /in /tmp/MANIFEST.MF

FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS jre-build
RUN jlink \
    --add-modules java.base,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.management,jdk.unsupported,jdk.zipfs \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /opt/jre \
    && mkdir -p /data \
    && chmod 0755 /data

# Hardened shell-less, package-manager-less, OpenSSL-less Distroless glibc base (7 Debian 13 packages).
# Pinned by multi-arch OCI index digest (amd64 + arm64).
FROM gcr.io/distroless/base-nossl-debian13:nonroot@sha256:4006ac92504be607b70de01d1f64f38288c69fcd8faa820f6d3aed266ea32ac5
ARG VERSION=dev
LABEL org.opencontainers.image.title="miru" \
      org.opencontainers.image.description="Lightweight media server telemetry, live stream monitor, playback history tracker, and library stats engine" \
      org.opencontainers.image.source="https://github.com/hononeko/miru" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

# G1PeriodicGCSystemLoadThreshold=0.0 disables the host-wide getloadavg() gate so idle Miru containers
# still uncommit heap after 30s of GC inactivity even when other workloads are active on the host.
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}" \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=65536 \
    JDK_JAVA_OPTIONS="-XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:InitialRAMPercentage=10.0 -XX:MaxRAMPercentage=50.0 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 -XX:G1PeriodicGCInterval=30000 -XX:-G1PeriodicGCInvokesConcurrent -XX:G1PeriodicGCSystemLoadThreshold=0.0 -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=48m -XX:+ExitOnOutOfMemoryError" \
    SQLITE_PATH="/data/miru.db" \
    STATIC_WEB_DIR="/opt/miru/static" \
    MIRU_VERSION="${VERSION}"

# App code, JRE, static assets, and native library are owned by root (read-only to UID 10001).
# Only /data is owned by UID/GID 10001 so named volumes keep existing ownership compatibility.
COPY --from=jre-build --chown=0:0 /opt/jre /opt/java/openjdk
COPY --from=jre-build --chown=10001:10001 /data /data
COPY --from=app-prepare --chown=0:0 /out/miru-all.jar /opt/miru/miru-all.jar
COPY --from=app-prepare --chown=0:0 /out/native /opt/miru/native
COPY --from=web-build --chown=0:0 /web/build /opt/miru/static

USER 10001:10001
WORKDIR /data
EXPOSE 8080

ENTRYPOINT ["/opt/java/openjdk/bin/java", "--enable-native-access=ALL-UNNAMED", "-Dorg.sqlite.lib.path=/opt/miru/native", "-Dorg.sqlite.lib.name=libsqlitejdbc.so", "-jar", "/opt/miru/miru-all.jar"]
