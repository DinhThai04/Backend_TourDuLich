FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
RUN mvn clean package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data && chown -R app:app /app
USER app

EXPOSE 8093
ENTRYPOINT ["sh", "-c", "exec java -Xms64m -Xmx${JAVA_XMX:-256m} -XX:MaxMetaspaceSize=192m -Xss512k -XX:+ExitOnOutOfMemoryError -Dspring.jmx.enabled=false -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
