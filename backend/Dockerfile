# ── Stage 1: Build ──────────────────────────────────────────────
FROM maven:3.9.12-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN addgroup --system spring && adduser --system --ingroup spring spring

# Attachment bytes land here (app.attachment.storage-path defaults to ./var/attachments, and the
# working directory is /app). The image runs as a non-root user against a root-owned /app, so the
# directory has to be created and handed over now — otherwise the first upload fails at runtime with
# "storage is not writable", which is exactly what happened before this line existed.
#
# Declaring it a VOLUME is not decoration: without one the bytes live in the container's writable
# layer and every redeploy silently discards every attachment while their rows survive in the
# database, leaving metadata pointing at files that no longer exist.
RUN mkdir -p /app/var/attachments && chown -R spring:spring /app/var
VOLUME ["/app/var/attachments"]

USER spring
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
