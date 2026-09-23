# ---- Stage 1: 构建 React SPA 前端 ----
FROM node:22-alpine AS frontend-build
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- Stage 2: 构建 Spring Boot 后端（含前端静态资源） ----
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn/settings.xml ./.mvn/settings.xml
COPY src ./src
# 从前端构建阶段复制构建产物到 web/dist，供 Maven resources 打包进 jar
COPY --from=frontend-build /web/dist ./web/dist
RUN mvn -B -s .mvn/settings.xml -DskipTests package

# ---- Stage 3: 运行镜像 ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/data/uploads && chown -R app:app /app
COPY --from=build /workspace/target/activity-assistant-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
