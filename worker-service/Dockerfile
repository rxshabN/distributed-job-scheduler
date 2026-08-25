# See scheduler-service/Dockerfile for why this is multi-stage and why dependency resolution
# happens before copying src/.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -o package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# See scheduler-service/Dockerfile for the curl/non-root/MaxRAMPercentage rationale -- identical
# reasoning, kept in both files rather than shared, since the two services build independently.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

COPY --from=build --chown=app:app /app/target/worker-service-*.jar app.jar

USER app
# Matches application.yml's server.port default -- distinct from scheduler-service's 8080 so the
# two can run side by side outside Docker. Compose never publishes a host port for either worker
# replica, so this value only has to be internally consistent with the HEALTHCHECK below.
EXPOSE 8081

# The worker exposes no public API, but its actuator health endpoint is still the honest liveness
# signal: it covers the datasource and Redis connections the poll loop depends on, which a process
# check would miss entirely (a worker whose datasource pool is exhausted is a live process that
# claims nothing).
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
