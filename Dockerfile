# Build the single-page frontend first; its output is packaged into the jar.
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S dhanwise && adduser -S dhanwise -G dhanwise
WORKDIR /app
COPY --from=build /app/target/dhanwise-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /data && chown dhanwise:dhanwise /data
USER dhanwise
ENV SUB_SCOUT_DB_PATH=/data/dhanwise.db
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
