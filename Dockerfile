FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN ./gradlew resolveDependencies --no-daemon
COPY backend backend
RUN ./gradlew bootJar --no-daemon --offline
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
COPY semantic-model /app/semantic-model
EXPOSE 8080 5433
ENTRYPOINT ["java","-jar","app.jar"]
