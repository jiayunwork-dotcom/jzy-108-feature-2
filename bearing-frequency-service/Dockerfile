# ---- 构建阶段：编译并跑测试，产出可执行 jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

# ---- 运行阶段：仅携带 JRE 与产物 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/bearing-frequency-service-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
