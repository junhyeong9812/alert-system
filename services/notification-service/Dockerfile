# 멀티스테이지 (spec I9): Gradle 빌드 → JRE 런타임. 의존성은 BuildKit 캐시 마운트로 재사용한다.
FROM gradle:9.8.2-jdk21 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000,sharing=locked \
    gradle --no-daemon -q bootJar

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
