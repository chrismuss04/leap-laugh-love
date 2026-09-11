# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY iam-app/pom.xml iam-app/
COPY trading-app/pom.xml trading-app/
COPY market-data-app/pom.xml market-data-app/
COPY iam-app/src iam-app/src
RUN mvn -B clean package -DskipTests -pl iam-app -am

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/iam-app/target/*-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
