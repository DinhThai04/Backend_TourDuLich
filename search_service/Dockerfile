# --- Giai đoạn 1: Build bằng Maven ---
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# --- Giai đoạn 2: Chạy ứng dụng ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Chạy bằng user không đặc quyền.
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data && chown -R app:app /app
USER app

EXPOSE 8089
# Heap mặc định 384m cho máy dev ~8 GB; override bằng JAVA_XMX khi chạy container
# (xem docker-compose.yml). Đừng đặt lớn hơn RAM còn trống của host.
ENTRYPOINT ["sh", "-c", "exec java -Xms64m -Xmx${JAVA_XMX:-384m} -XX:MaxMetaspaceSize=192m -Xss512k -XX:+ExitOnOutOfMemoryError -Dspring.jmx.enabled=false -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
