# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder

ARG GRADLE_USER_HOME=/root/.gradle
ENV GRADLE_USER_HOME=$GRADLE_USER_HOME

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle

RUN --mount=type=cache,target=${GRADLE_USER_HOME} \
    --mount=type=cache,target=${GRADLE_USER_HOME}/wrapper \
    chmod +x ./gradlew && ./gradlew --version --no-daemon

COPY settings.gradle ./
COPY *lockfile* ./
COPY build.gradle ./

RUN --mount=type=cache,target=${GRADLE_USER_HOME} \
    --mount=type=cache,target=${GRADLE_USER_HOME}/wrapper \
    ./gradlew dependencies --no-daemon --console=plain

COPY src ./src
COPY LICENSE_HEADER .

RUN --mount=type=cache,target=${GRADLE_USER_HOME} \
    --mount=type=cache,target=${GRADLE_USER_HOME}/wrapper \
    ./gradlew installDist --no-daemon -x test --console=plain

FROM eclipse-temurin:25-jre-jammy AS runtime

WORKDIR /app

COPY --from=builder /app/build/install/message-service /app

EXPOSE 8082

ENV JAVA_OPTS="-XX:+UseZGC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["/app/bin/message-service"]