# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY common-security/pom.xml common-security/
COPY iam-app/pom.xml iam-app/
COPY trading-app/pom.xml trading-app/
COPY market-data-app/pom.xml market-data-app/
COPY common-security/src common-security/src
COPY trading-app/src trading-app/src
RUN mvn -B clean package -DskipTests -pl trading-app -am

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/trading-app/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
