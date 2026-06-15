FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="realtime-trade-processing-simulator" \
      org.opencontainers.image.description="Spring Boot backend for the realtime trade processing simulator" \
      org.opencontainers.image.source="https://github.com/manuelmanalo-build/redesigned-giggle"

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build /workspace/target/realtime-trade-processing-simulator-*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENV JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl --fail --silent http://localhost:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
