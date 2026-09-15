FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY gateway/src gateway/src
RUN mvn -q -pl gateway -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/gateway/target/gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
