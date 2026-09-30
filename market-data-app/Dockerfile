# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY common-security/pom.xml common-security/
COPY iam-app/pom.xml iam-app/
COPY account-app/pom.xml account-app/
COPY order-app/pom.xml order-app/
COPY market-data-app/pom.xml market-data-app/
COPY common-security/src common-security/src
COPY market-data-app/src market-data-app/src
RUN mvn -B clean package -DskipTests -pl market-data-app -am

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/market-data-app/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
