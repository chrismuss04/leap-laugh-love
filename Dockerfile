# ── Stage 1: Build ─────────────────────────────────────────────────────────────
# Official Maven + JDK image so we have everything needed to compile, naming it build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first, before source to avoid re-downloading Maven depenencies on every code change. 
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Now copy source and compile. This layer is re-run only when src/ changes.
COPY src/ src/
RUN mvn -B clean package -DskipTests

# ── Stage 2: Runtime ───────────────────────────────────────────────────────────
# Fresh base image: JRE-only Alpine variant. The JDK, Maven, source code,
# and build cache from Stage 1 are NOT copied here — they never exist in the
# final image. That's why the multi-stage image is significantly smaller.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a non-root group and user, then switch to it.
# Running as root inside a container is unnecessary and increases blast radius
# if the container is compromised.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy only the compiled jar from the build stage. Nothing else.
COPY --from=build /app/target/team-skeleton.jar app.jar

EXPOSE 8080
# Lets `docker ps` / compose report actual container health instead of just "running".
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
