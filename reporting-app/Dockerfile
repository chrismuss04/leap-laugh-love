# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY common-security/pom.xml common-security/
COPY iam-app/pom.xml iam-app/
COPY account-app/pom.xml account-app/
COPY order-app/pom.xml order-app/
COPY market-data-app/pom.xml market-data-app/
COPY reporting-app/pom.xml reporting-app/
COPY common-security/src common-security/src
COPY reporting-app/src reporting-app/src
# Same build as the other service images: see market-data-app/Dockerfile for why the Maven
# repository is a shared cache mount and why tests, Javadoc and JaCoCo are skipped here.
RUN --mount=type=cache,id=leap-maven-repo,target=/root/.m2,sharing=shared \
    mvn -B clean package -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true \
        -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav \
        -pl reporting-app -am
# CDS (see the runtime stage) needs a plain classpath rather than the nested jars of a fat jar.
RUN java -Djarmode=tools -jar reporting-app/target/*.jar extract --destination /app/extracted \
    --application-filename app.jar

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/extracted extracted
# Class Data Sharing archive from a training run that refreshes the context and exits. There is
# no JPA here, and the connection pool only connects on first use, so it needs no database.
RUN java -XX:ArchiveClassesAtExit=extracted/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off \
        -Dspring.context.exit=onRefresh \
        -jar extracted/app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-XX:SharedArchiveFile=extracted/app.jsa", "-jar", "extracted/app.jar"]
