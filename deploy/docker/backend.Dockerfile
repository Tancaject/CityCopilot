# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9.16-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-noble

FROM ${MAVEN_IMAGE} AS build
ARG MODULE
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY backend/contracts/pom.xml backend/contracts/pom.xml
COPY backend/search/pom.xml backend/search/pom.xml
COPY backend/commerce-service/pom.xml backend/commerce-service/pom.xml
COPY backend/core/pom.xml backend/core/pom.xml
COPY backend/agent/pom.xml backend/agent/pom.xml
COPY backend/mcp-server/pom.xml backend/mcp-server/pom.xml
COPY backend/eval/pom.xml backend/eval/pom.xml

RUN case "${MODULE}" in core|agent|mcp-server|commerce-service) ;; *) echo "Unsupported MODULE=${MODULE}" >&2; exit 2 ;; esac
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl "backend/${MODULE}" -am -DskipTests package && \
    cp "backend/${MODULE}"/target/*-exec.jar /workspace/application.jar

FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 citycopilot && \
    useradd --system --uid 10001 --gid citycopilot --home-dir /app --shell /usr/sbin/nologin citycopilot

WORKDIR /app
COPY --from=build --chown=citycopilot:citycopilot /workspace/application.jar /app/application.jar
RUN mkdir -p /app/uploads /app/logs/rocketmq && \
    chown -R citycopilot:citycopilot /app/uploads /app/logs

USER citycopilot
EXPOSE 8081 8082 8083 8090 9091

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Dfile.encoding=UTF-8", "-jar", "/app/application.jar"]
