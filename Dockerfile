# =============================================================================
# Stage 1a: Fat JAR builder — Temurin JDK (works on any JVM 21+, no GraalVM needed)
# =============================================================================
FROM eclipse-temurin:21-jdk AS jar-builder

WORKDIR /build

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && \
    ./gradlew --no-daemon dependencies --configuration compileClasspath 2>/dev/null || true

COPY compiler/           compiler/
COPY runtime/            runtime/
COPY stdlib/             stdlib/
# settings.gradle.kts includes :kobol-gradle-plugin — its dir must exist or Gradle
# fails to configure even when only :compiler:jar is requested.
COPY kobol-gradle-plugin/ kobol-gradle-plugin/

RUN ./gradlew :compiler:jar --no-daemon

# =============================================================================
# Stage 1b: GraalVM native image builder
# =============================================================================
FROM ghcr.io/graalvm/native-image-community:21-muslib AS native-builder

# The GraalVM muslib image is Oracle Linux 9 minimal and ships without `xargs`, which the
# Gradle wrapper script requires to assemble JVM args — without it gradlew aborts at launch
# ("xargs is not available") before any build runs. findutils provides xargs.
RUN microdnf install -y findutils && microdnf clean all

WORKDIR /build

# Copy Gradle wrapper and build scripts first (better layer caching)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# Resolve dependencies before copying source (cache layer)
RUN ./gradlew --no-daemon dependencies --configuration compileClasspath 2>/dev/null || true

# Copy all source
COPY compiler/           compiler/
COPY runtime/            runtime/
COPY stdlib/             stdlib/
# settings.gradle.kts includes :kobol-gradle-plugin — its dir must exist or Gradle
# fails to configure even when only :compiler:nativeCompile is requested.
COPY kobol-gradle-plugin/ kobol-gradle-plugin/

# Build the native image
RUN ./gradlew :compiler:nativeCompile --no-daemon -Pnative

# =============================================================================
# Target: native (default) — self-contained binary, no JVM required
#   docker build --target native -t kobol:native .
# =============================================================================
FROM alpine:3.20 AS native

RUN apk add --no-cache libstdc++ libgcc

COPY --from=native-builder /build/compiler/build/native/nativeCompile/kobol /usr/local/bin/kobol

ENTRYPOINT ["kobol"]
CMD ["--help"]

# =============================================================================
# Target: jvm-temurin — Eclipse Temurin (Adoptium) HotSpot JVM
#   docker build --target jvm-temurin -t kobol:temurin .
# =============================================================================
FROM eclipse-temurin:21-jre AS jvm-temurin

WORKDIR /opt/kobol
COPY --from=jar-builder /build/compiler/build/libs/kobolc.jar ./kobolc.jar

ENTRYPOINT ["java", "-jar", "/opt/kobol/kobolc.jar"]
CMD ["--help"]

# =============================================================================
# Target: jvm-openj9 — Eclipse OpenJ9 (IBM Semeru Runtime)
#   docker build --target jvm-openj9 -t kobol:openj9 .
#
# OpenJ9 typically uses 40-60% less heap than HotSpot.
# The -Xshareclasses flag enables class data sharing across containers.
# =============================================================================
FROM ibm-semeru-runtimes:open-21-jre AS jvm-openj9

WORKDIR /opt/kobol
COPY --from=jar-builder /build/compiler/build/libs/kobolc.jar ./kobolc.jar

# Pre-create shared class cache directory
RUN mkdir -p /opt/kobol/.scc

ENTRYPOINT ["java", "-Xshareclasses:cacheDir=/opt/kobol/.scc,name=kobol", \
            "-jar", "/opt/kobol/kobolc.jar"]
CMD ["--help"]

# Usage examples:
#
#   # GraalVM native binary (fastest startup, no JVM needed)
#   docker build --target native -t kobol:native .
#   docker run --rm -v "$PWD:/work" -w /work kobol:native run Main.kbl
#
#   # Eclipse Temurin / Adoptium JVM (standard HotSpot)
#   docker build --target jvm-temurin -t kobol:temurin .
#   docker run --rm -v "$PWD:/work" -w /work kobol:temurin run Main.kbl
#
#   # Eclipse OpenJ9 / IBM Semeru (low-memory alternative JVM)
#   docker build --target jvm-openj9 -t kobol:openj9 .
#   docker run --rm -v "$PWD:/work" -w /work kobol:openj9 run Main.kbl
#
#   # Fat JAR (runs on any JVM 21+):
#   docker run --rm -v "$PWD:/work" -w /work -e JAVA_HOME=... kobol:temurin run Main.kbl
