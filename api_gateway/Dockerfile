# --- Giai đoạn 1: Build bằng Maven ---
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
# Build và bỏ qua test (để chạy cho nhanh và đỡ lỗi test vặt)
RUN mvn clean package -DskipTests

# --- Giai đoạn 2: Chạy ứng dụng ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Copy file .jar từ giai đoạn build
# Lưu ý: *.jar sẽ lấy file jar bất kỳ, tiện lợi không cần biết tên chính xác
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]