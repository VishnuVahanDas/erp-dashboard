# Vyom web dashboard backend: Java API server.
# Device sync is not done here: punches arrive from the Windows sync agent
# (sdk repo) writing into the same PostgreSQL.

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Resolve dependencies first so code-only changes reuse this cached layer.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 vyom
COPY --from=build /src/target/vyom-erp.jar /app/vyom-erp.jar
USER vyom
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/vyom-erp.jar"]
