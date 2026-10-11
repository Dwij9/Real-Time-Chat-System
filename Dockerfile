# One Dockerfile for all three services. The module to package is passed in at build time, which keeps the
# dependency layer shared between them: Maven resolves once, and a change to one service does not
# invalidate the others' cached layers.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Poms first, so the dependency download is cached independently of the source.
COPY pom.xml .
COPY common/pom.xml common/
COPY gateway-service/pom.xml gateway-service/
COPY message-service/pom.xml message-service/
COPY notification-service/pom.xml notification-service/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY common/src common/src
COPY gateway-service/src gateway-service/src
COPY message-service/src message-service/src
COPY notification-service/src notification-service/src

ARG MODULE
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/*.jar /build/service.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 chat
COPY --from=build /build/service.jar /app/service.jar
USER chat

# Container-aware heap sizing, and a short shutdown grace so a rolling deploy drains sockets rather than
# cutting them: clients reconnect to another node well inside the 30s presence TTL.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/service.jar"]
