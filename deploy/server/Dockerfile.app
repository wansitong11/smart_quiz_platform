# 低内存服务器用的运行时镜像：只复制本地已构建好的 jar，容器内不做任何编译。
# 2G 级内存的服务器必须在本地/CI 编译，否则 Gradle 构建会 OOM。
FROM eclipse-temurin:25-jre
WORKDIR /app
# 本地构建产物：app/build/libs/*.jar（传上去时重命名为 app.jar）
COPY app/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
