# ---- Build aşaması ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

ARG GIT_SHA=unknown

COPY pom.xml ./pom.xml
COPY services ./services

RUN chmod +x services/LabResultService/mvnw \
 && services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am \
    -DskipTests -Dgit.sha=${GIT_SHA} package

# ---- Çalışma aşaması ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --no-create-home appuser
COPY --from=build /workspace/services/LabResultService/target/lab-result-service-*.jar /app/app.jar
USER appuser

EXPOSE 8080 8081 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]