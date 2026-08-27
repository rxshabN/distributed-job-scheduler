FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -o package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

COPY --from=build --chown=app:app /app/target/scheduler-service-*.jar app.jar

USER app
EXPOSE 8080

# start-period=600s: on the deployed 1 GB E2.1.Micro (1/8 OCPU), a cold JVM start through
# Tomcat + Hibernate + Flyway has been observed taking 330s+ even when healthy -- the original
# 60s start-period + 6*10s retries (120s total) was tuned against local/A1 hardware and declared
# the container unhealthy while it was still legitimately starting. 600s + 8*15s (~720s total)
# gives real cold starts room without waiting forever on a genuinely hung container.
HEALTHCHECK --interval=15s --timeout=5s --start-period=600s --retries=8 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
