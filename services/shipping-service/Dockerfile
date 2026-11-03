FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
