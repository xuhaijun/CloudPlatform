# ============================================================================
#  车路通 DSSAD 云平台 —— 后端服务镜像（多阶段构建）
#
#  构建（在仓库根目录执行，构建上下文必须是仓库根）：
#      docker build -t dssad-cloud-platform:local .
#
#  运行：
#      docker run --rm -p 8080:8080 \
#        -e SPRING_PROFILES_ACTIVE=prod \
#        -e DB_HOST=host.docker.internal -e DB_USERNAME=dssad -e DB_PASSWORD=*** \
#        -e DSSAD_ENTERPRISE_ID=DSSAD-ENT-0001 \
#        -e DSSAD_MQTT_BROKER_URL=tcp://broker:1883 \
#        -e DSSAD_REGULATORY_BASE_URL=https://reg.example.com \
#        -e DSSAD_STORAGE_PUBLIC_URL=https://dssad.example.com/media \
#        -e DSSAD_SRS_URL=https://dssad.example.com \
#        dssad-cloud-platform:local
#
#  为什么运行镜像用 alpine 而不是 ubuntu 基础镜像：
#      Temurin 的 ubuntu 镜像**不含 curl/wget**，下面的 HEALTHCHECK 会静默失效
#      （容器永远显示 healthy 或永远 unhealthy，且没有任何报错）。
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段 1：构建
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 拉依赖：改业务代码时不会触发重新下载全部依赖（构建从分钟级降到秒级）
COPY cloud-platform-server/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY cloud-platform-server/src ./src

# 镜像构建阶段跳过测试：测试是 CI 的门禁，不应与镜像构建耦合
# （否则一个随机失败的用例会让「只改配置」的发版也构建不出来）。
# CI 流水线里先跑 `mvn test` 再 docker build。
RUN mvn -B package -DskipTests

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# tzdata 不能省：镜像默认 UTC，日志与「按天统计」会整体错位 8 小时
RUN apk add --no-cache curl tzdata \
    && addgroup -S dssad \
    && adduser -S -G dssad dssad
ENV TZ=Asia/Shanghai

WORKDIR /app
COPY --from=build /build/target/dssad-cloud-platform.jar ./app.jar

# 媒体存储与日志目录（prod 默认值分别是 /data/dssad/media 与 /var/log/dssad 下）
RUN mkdir -p /data/dssad/media /var/log/dssad \
    && chown -R dssad:dssad /app /data/dssad /var/log/dssad

# 非 root 运行：即使应用被攻破也拿不到容器内的 root
USER dssad

EXPOSE 8080

# 探测 liveness 而不是整体 health：
#   整体 health 会把依赖（MySQL/Redis/MQTT）也算进去，依赖抖动会让编排反复重启容器，
#   而依赖本来就该「应用活着 → 依赖恢复后自动重连」。
# 注意 liveness 端点默认只在 K8s 环境开启，本项目已在 application.yml 显式打开
# （management.endpoint.health.probes.enabled=true），否则这里会 404 而健康检查形同失效。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

# 用 exec 让 java 成为 PID 1：否则 sh 收不到 SIGTERM，优雅停机（释放数据库连接、
# 提交 MQTT 在途消息）永远不会执行，容器只能等超时被 SIGKILL。
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
