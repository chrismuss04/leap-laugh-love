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
# Maven's local repository lives in a BuildKit cache mount, not the image, so it survives
# between builds and is shared by all four service images: a code change no longer means
# re-downloading every dependency. The mount is "shared" so the four images build at the
# same time rather than queueing for it; Maven's file-lock sync context is what keeps two
# builds from writing the same artifact at once.
#
# The root POM binds an aggregate Javadoc run (which forks a compile of every module) and
# JaCoCo to the build. Neither ends up in the image, and the Javadoc alone was most of the
# build time - paid four times over, once per service.
RUN --mount=type=cache,id=leap-maven-repo,target=/root/.m2,sharing=shared \
    mvn -B clean package -DskipTests -Dmaven.javadoc.skip=true -Djacoco.skip=true \
        -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav \
        -pl market-data-app -am

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/market-data-app/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
