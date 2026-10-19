FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY server ./server
COPY contracts ./contracts
RUN SCC_SERVER_ONLY=true gradle --no-daemon :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ /app/
RUN mkdir -p /app/data /app/storage
EXPOSE 4180
ENTRYPOINT ["/app/bin/server"]
