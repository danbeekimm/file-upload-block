# ---- build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# 빌드 스크립트와 Gradle 래퍼만 먼저 복사해 의존성 레이어를 캐시
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar

# ---- runtime ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# 비루트 사용자로 실행 (업로드 처리 프로세스의 권한 최소화)
RUN useradd --system --create-home appuser
USER appuser
COPY --from=build /app/build/libs/file-upload-block-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
