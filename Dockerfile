FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/not-the-right-file.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
