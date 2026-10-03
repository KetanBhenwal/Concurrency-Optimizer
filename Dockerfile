FROM node:22-alpine AS ui-build
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
COPY ui ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
COPY --from=ui-build /ui/dist/ ./src/main/resources/static/
RUN mvn -B package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/seat-reservation-service-0.1.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "case \"${DATABASE_URL:-}\" in postgresql://*) export DATABASE_URL=\"jdbc:${DATABASE_URL}\" ;; postgres://*) export DATABASE_URL=\"jdbc:postgresql://${DATABASE_URL#postgres://}\" ;; esac; exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar"]