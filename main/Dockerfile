# --- Stage 1: build the runnable jar ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies first.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# --- Stage 2: minimal runtime ---
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/flight-booking-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
