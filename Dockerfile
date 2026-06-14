# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY ssolv-api-common ./ssolv-api-common
COPY ssolv-api-core ./ssolv-api-core
COPY ssolv-api-place ./ssolv-api-place
COPY ssolv-batch ./ssolv-batch
COPY ssolv-domain ./ssolv-domain
COPY ssolv-global-utils ./ssolv-global-utils
COPY ssolv-infrastructure ./ssolv-infrastructure

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :ssolv-batch:bootJar -x test \
    && cp "$(find ssolv-batch/build/libs -maxdepth 1 -type f -name 'ssolv-batch-*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --create-home --uid 10001 appuser

COPY --from=builder /workspace/app.jar /app/app.jar

ENV TZ=Asia/Seoul
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

USER appuser

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar \"$@\"", "--"]
