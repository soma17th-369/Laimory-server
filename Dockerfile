# ===== build stage =====
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src ./src
RUN ./gradlew clean bootJar --no-daemon -x test

# ===== runtime stage =====
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN useradd -r -u 1001 appuser
# OpenTelemetry javaagent(버전·sha256 고정, BuildKit ADD가 다운로드 시 체크섬 검증). jar만 탑재하고
# 활성화는 배포 환경 .env의 JAVA_TOOL_OPTIONS가 소유한다 — env 부재면 완전 inert(local/integration 무영향).
ADD --checksum=sha256:9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d --chmod=444 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar \
    /otel/opentelemetry-javaagent.jar
# ADD --chmod는 암묵 생성된 부모 디렉터리에도 444를 적용해 non-root가 탐색(x)할 수 없다 —
# appuser(UID 1001)가 -javaagent로 읽을 수 있게 디렉터리만 0755로 되돌린다.
RUN chmod 0755 /otel
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
