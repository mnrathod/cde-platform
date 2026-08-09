# ══════════════════════════════════════════════════════════════
#  CDE Platform — Spring Boot application
#
#  Document/CAD conversion lives in the separate converter image
#  (converter/Dockerfile), which carries LibreOffice, LibreDWG and
#  Tesseract. This image talks to it over HTTP via CDE_CONVERTER_URL.
# ══════════════════════════════════════════════════════════════

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy the build definition first so the dependency-resolution layer is
# cached and only re-runs when the build files actually change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
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
