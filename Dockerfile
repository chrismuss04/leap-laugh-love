FROM maven:3.9.9-eclipse-temurin-17
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests
CMD ["java", "-cp", "target/classes", "com.neueda.Main"]
