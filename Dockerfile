# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Download dependencies first so this layer is cached until pom.xml changes
COPY pom.xml lombok.config ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (GitHub Actions); skip them here to keep image builds fast
RUN mvn -B -q package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as an unprivileged user
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080

# Tuned for small free-tier containers (~512 MB):
#  - MaxRAMPercentage: size the heap from the container's memory limit
#  - SerialGC: lowest memory overhead for a single small service
#  - Xss512k: smaller thread stacks
#  - TieredStopAtLevel=1: faster startup (helps free-tier cold starts) at some peak-throughput cost
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseSerialGC", "-Xss512k", "-XX:TieredStopAtLevel=1", "-jar", "app.jar"]
