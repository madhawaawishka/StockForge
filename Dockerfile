# syntax=docker/dockerfile:1.7
#
# One image definition for every StockForge service (spec §30, W6-02). The services share the reactor's libraries
# (proto, event-schemas, messaging), so each image is built from the repository root:
#
#   docker build --build-arg SERVICE=inventory-service -t stockforge/inventory-service .
#
# Stage 1 compiles the service with the Maven wrapper (tests run in CI, not here). Stage 2 splits the Spring Boot jar
# into layers ordered from least to most frequently changed, so a code change rebuilds only the last, small layer.
# Stage 3 is the runtime: a JRE only (no compiler, no Maven, no sources), running as an unprivileged user.

# Temurin patch release: base-image OS packages (OpenSSL, GnuPG) are patched with it; Dependabot proposes updates.
ARG JAVA_VERSION=21.0.12.1_1

FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble AS build
ARG SERVICE
RUN test -n "${SERVICE}" || (echo "Build argument SERVICE is required, e.g. --build-arg SERVICE=inventory-service" && false)
WORKDIR /workspace
COPY . .
# The Maven repository is a BuildKit cache mount: dependencies are downloaded once per builder, not once per image.
# "locked" serializes builds that share it, because concurrent Maven builds can corrupt a local repository.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress --projects "services/${SERVICE}" --also-make package \
        -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
        -Daether.connector.http.retryHandler.count=5 -Daether.connector.http.retryHandler.requestSentEnabled=true \
    && cp "services/${SERVICE}/target/${SERVICE}"-*.jar /workspace/application.jar

FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble AS layers
WORKDIR /layers
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre-noble AS runtime
ARG SERVICE
LABEL org.opencontainers.image.title="stockforge-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/madhawaawishka/StockForge"
# A dedicated system account without a login shell, home directory or extra groups (the base image's "ubuntu" user is
# in the sudo group). The application files stay owned by root, so the process cannot modify its own code.
RUN groupadd --system --gid 10001 stockforge \
    && useradd --system --uid 10001 --gid stockforge --no-create-home --shell /usr/sbin/nologin stockforge
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001:10001
# Size the heap from the container's memory limit, and let the orchestrator restart a JVM that ran out of memory
# rather than keep a half-broken one alive. G1 is chosen explicitly: with fewer than 2 CPUs or 1792 MB the JVM would
# silently pick the stop-the-world Serial collector (found by profiling the gateway, docs/benchmarks/README.md).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# Exec form: java is PID 1 and receives SIGTERM directly, which starts Spring's graceful shutdown (spec §33).
ENTRYPOINT ["java", "-jar", "application.jar"]
