# Multi-stage Dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
# RUN ./gradlew build --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# COPY --from=build /app/build/libs/backend-1.0-SNAPSHOT.jar app.jar
EXPOSE 4567
ENTRYPOINT ["java", "-jar", "app.jar"]
