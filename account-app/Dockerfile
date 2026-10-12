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
COPY account-app/src account-app/src
# Maven's local repository lives in a BuildKit cache mount, not the image, so it survives
# between builds and is shared by all four service images: a code change no longer means
# re-downloading every dependency. The mount is "shared" so the four images build at the
# same time rather than queueing for it; Maven's file-lock sync context is what keeps two
# builds from writing the same artifact at once.
#
# The root POM binds an aggregate Javadoc run (which forks a compile of every module) and
# JaCoCo to the build. Neither ends up in the image, and the Javadoc alone was most of the
# build time - paid four times over, once per service. maven.test.skip rather than skipTests
# so the test sources are not compiled either: the Unit Tests stage compiles and runs them.
RUN --mount=type=cache,id=leap-maven-repo,target=/root/.m2,sharing=shared \
    mvn -B clean package -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true \
        -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav \
        -pl account-app -am
# CDS (see the runtime stage) needs a plain classpath rather than the nested jars of a fat jar.
# Unpacked here so the runtime image gets only the unpacked copy, not both.
RUN java -Djarmode=tools -jar account-app/target/*.jar extract --destination /app/extracted \
    --application-filename app.jar

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/extracted extracted
# Class Data Sharing: the JVM maps the classes Spring loads at startup from a prebuilt archive
# instead of loading and verifying each one from the jars, which takes a large share off every
# start - the recovery suite restarts these services over and over. The archive comes from a
# training run that refreshes the application context and exits before anything starts: no web
# server, schedulers, runners or Kafka clients, and no database - Hibernate is given its dialect
# so it doesn't ask one. At runtime a missing or mismatched archive only costs speed; the JVM
# starts without it.
RUN java -XX:ArchiveClassesAtExit=extracted/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off \
        -Dspring.context.exit=onRefresh \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -jar extracted/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:SharedArchiveFile=extracted/app.jsa", "-jar", "extracted/app.jar"]

