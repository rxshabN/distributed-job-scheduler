# Multi-stage: the build stage has the full JDK + Maven cache, the runtime image ships only the
# JRE and the built jar -- keeps the deployed image small, which matters on the Oracle Always Free
# VM's bounded disk/RAM (spec §12), not just as a general best practice.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies resolve into a layer that only invalidates when pom.xml changes, not on every
# source edit -- go-offline before copying src/ so `docker build` re-uses the Maven cache layer
# across rebuilds instead of re-downloading the internet every time.
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -o package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is here solely for the container HEALTHCHECK below. It is worth the ~1 MB because the
# alternative -- a bare TCP probe on 8080 -- reports healthy as soon as Tomcat binds the port,
# which happens before Flyway has finished migrating and before the datasource is proven
# reachable. docker-compose.yml gates both worker replicas on this healthcheck precisely so they
# don't start polling a schema that doesn't exist yet, so the probe has to mean "the app is
# actually serving", not "a socket is open".
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as an unprivileged user rather than root. Nothing here needs root -- the JVM binds 8080,
# which is above the privileged range -- so running as root only widens what a container escape
# would reach on a VM that also hosts Postgres and Redis.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app

COPY --from=build --chown=app:app /app/target/scheduler-service-*.jar app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# MaxRAMPercentage, not a fixed -Xmx: the JVM reads the container's cgroup memory limit, so one
# image behaves correctly under the mem_limit docker-compose.yml sets AND on a bare VM, without a
# per-environment rebuild. The default is 25%, which would leave most of an already-tight
# Always Free VM's allocation unused while the heap thrashed.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
