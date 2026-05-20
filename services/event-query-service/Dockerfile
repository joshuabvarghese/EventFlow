# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /build

# Cache dependency resolution — only re-runs when pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the fat jar
COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=build /build/target/event-query-service-*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8083

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
