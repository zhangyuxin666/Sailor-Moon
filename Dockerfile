# ---- Stage 1: 构建 React SPA 前端 ----
FROM node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 AS frontend-build
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ ./
RUN npm run build

# ---- Stage 2: 构建 Spring Boot 后端（含前端静态资源） ----
FROM maven:3.9.9-eclipse-temurin-21-alpine@sha256:5a8b906c4faa11d33f6c74758f67db8eac25441e14b0729f6d50ff78992be58a AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn/settings.xml ./.mvn/settings.xml
COPY src ./src
# 从前端构建阶段复制构建产物到 web/dist，供 Maven resources 打包进 jar
COPY --from=frontend-build /web/dist ./web/dist
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s .mvn/settings.xml -DskipTests package

# ---- Stage 3: 外部 Tomcat 运行镜像 ----
FROM tomcat:10.1-jdk21-temurin@sha256:07191205c229da87cb8cbb086c12f513601846147fcccf2d79aff945f627e9a9
WORKDIR /app
RUN rm -rf "${CATALINA_HOME}/webapps/"* \
    && mkdir -p /app/data/uploads
COPY --from=build /workspace/target/activity-assistant-*.war "${CATALINA_HOME}/webapps/ROOT.war"
EXPOSE 8080
