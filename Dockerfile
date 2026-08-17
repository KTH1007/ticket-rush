# =========================================
# 1단계: 빌드
# =========================================
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Gradle 캐시를 레이어로 남기려면 wrapper와 빌드 스크립트를 먼저 복사한다.
# 소스만 바뀌면 아래 의존성 다운로드 레이어를 재사용한다.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./

# --mount=type=cache는 레이어 캐시와 별개로 살아남는다. --no-cache로 레이어를
# 전부 무효화하거나 CI 러너가 매번 새로 뜨는 환경이라도, 이 캐시 마운트이 남아있으면
# 이미 받은 의존성 jar를 다시 내려받지 않는다(실측: 758MB 캐시 재사용 확인).
# 다만 이 프로젝트는 플러그인이 많아(kapt, detekt, ktlint, querydsl, modulith...)
# `dependencies`가 구성마다 트리를 전부 resolve+렌더링하는 비용 자체가 커서,
# 벽시계 시간 단축은 크지 않다(실측 2:17 -> 2:00). 순수 네트워크 절감 목적이다.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x ./gradlew && ./gradlew dependencies --no-daemon

COPY config config
COPY src src

# 이미지 빌드에서는 검증을 돌리지 않는다.
# CI가 이미 test, detekt, ktlint를 통과시킨 산출물만 이미지로 만든다.
# 여기서 또 돌리면 빌드 시간만 두 배가 된다.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon -x test -x detektMain -x detektTest -x ktlintCheck

# =========================================
# 2단계: 레이어 추출
# =========================================
FROM eclipse-temurin:25-jre AS extractor
WORKDIR /extract
COPY --from=builder /build/build/libs/*.jar app.jar
# Spring Boot layered jar. 의존성과 애플리케이션 코드를 분리해
# 코드만 바뀌면 의존성 레이어를 재사용한다.
# 8단계에서 이미지를 반복 빌드하므로 체감 차이가 크다.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# =========================================
# 3단계: 실행
# =========================================
FROM eclipse-temurin:25-jre
WORKDIR /app

# docker-compose.app.yml의 healthcheck가 wget으로 /actuator/health/readiness를
# 찌른다. eclipse-temurin:25-jre 베이스엔 wget도 curl도 기본 포함돼 있지 않다.
RUN apt-get update && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

# 컨테이너를 root로 돌리지 않는다. 침해 시 피해 범위를 줄인다.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app app

# 변경 빈도가 낮은 순서로 복사해야 레이어 캐싱이 산다
COPY --from=extractor --chown=app:app /extract/extracted/dependencies/ ./
COPY --from=extractor --chown=app:app /extract/extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=app:app /extract/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=app:app /extract/extracted/application/ ./

# 힙 덤프를 남길 위치. 컨테이너 밖으로 볼륨을 걸어 꺼낸다.
# VOLUME 선언만으로는 마운트 지점이 root 소유로 생성돼 app 유저가 못 쓴다.
# USER 전환 전에 root로 미리 만들고 소유권을 넘긴다.
RUN mkdir -p /app/dump && chown app:app /app/dump
VOLUME ["/app/dump"]

USER app
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=75.0 \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/app/dump \
-XX:+UseG1GC \
-Duser.timezone=UTC \
-Dfile.encoding=UTF-8"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
