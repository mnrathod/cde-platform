# ══════════════════════════════════════════════════════════════
#  CDE Platform — Spring Boot application
#
#  Document/CAD conversion lives in the separate converter image
#  (converter/Dockerfile), which carries LibreOffice, LibreDWG and
#  Tesseract. This image talks to it over HTTP via CDE_CONVERTER_URL.
# ══════════════════════════════════════════════════════════════

FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS builder
WORKDIR /app

# Copy the build definition first so the dependency-resolution layer is
# cached and only re-runs when the build files actually change.
#
# gradle.properties carries the Spring Boot version the plugin block reads,
# and the lockfiles are what LockMode.STRICT resolves against — a build
# without them fails naming dependency locking rather than the missing file.
# Every project's build file is copied here, and every project's sources
# below, because settings.gradle includes three of them.
COPY gradlew settings.gradle build.gradle gradle.properties gradle.lockfile ./
COPY gradle ./gradle
COPY api-conventions/build.gradle api-conventions/gradle.lockfile ./api-conventions/
COPY conversion-service/build.gradle conversion-service/gradle.lockfile ./conversion-service/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
COPY api-conventions/src ./api-conventions/src
COPY conversion-service/src ./conversion-service/src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

# Non-root. uploads/ is a mounted volume, so it must be owned by the
# runtime user or every write fails with EACCES.
RUN addgroup -g 10001 cde \
    && adduser -D -u 10001 -G cde -s /sbin/nologin cde \
    && mkdir -p /app/uploads \
    && chown -R cde:cde /app

USER cde
EXPOSE 8080

# MaxRAMPercentage lets the heap track the container memory limit
# instead of the host's, which is what makes a cgroup limit actually
# bound the JVM rather than getting it OOM-killed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
