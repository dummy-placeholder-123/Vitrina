FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package \
  && cp "$(ls -1 target/*.jar | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8000
ENTRYPOINT ["java","-jar","/app/app.jar"]
