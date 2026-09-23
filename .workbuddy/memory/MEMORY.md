# CloudPlatform（车路通 DSSAD 产品云平台）长期约定

## 项目结构
```
D:\SpringBootProjects\CloudPlatform\
├─ cloud-platform-server/        Spring Boot 3.5 + Java 21 后端
│   ├─ src/main/java/com/genvict/dssad/cloud/{web,service,domain,mqtt,security,common,config}
│   ├─ src/main/resources/application{,-local,-prod}.yml   三层配置（默认 profile=local）
│   ├─ db/schema-mysql.sql               生产建表脚本（**手写，含注释与分区增强**）
│   ├─ db/schema-mysql.generated.sql     Hibernate 生成的比对基线
│   └─ perf/load_test.py                 零依赖压测脚本
├─ cloud-platform-web/           Vue3 + Vite + TS + Element Plus 前端
├─ docs/api/                     接口文档（REST + MQTT）
├─ docs/db/                      数据库设计说明书
└─ scripts/md2html.py            文档转换器（md → 深色主题单文件 HTML）
```

## 后端契约（前端必须遵守）
- 响应体 `{code, message, data}`，**业务码在 body**（`0000` 成功 / `1001` 参数 / `2001` 未授权）；HTTP 200 不代表成功。
- 令牌走 **`X-Token`** 请求头（不是 `Authorization: Bearer`）。
- 时间：`Instant` → 13 位毫秒数字；`LocalDate` → 数组 `[年,月,日]`。
- 坐标顺序一律 **`[纬度, 经度]`**（`startPoint`/`endPoint`/`currentPoint`）。
- `dispatchTask.action` 必填（`1` 开始 / `2` 结束）；结束必须复用同一 `taskId`，否则产生 `ORPHAN_FINISH` 孤儿记录。

## 数据库改表流程（改实体后必走）
```bash
cd cloud-platform-server
mvn test -Dtest=SchemaDdlGeneratorTest -Dddl.gen=true   # 重新生成基线（会先删旧文件）
diff db/schema-mysql.generated.sql db/schema-mysql.sql  # 人工把差异落到手写脚本
mysql -h$DB_HOST -u$DB_USERNAME -p $DB_NAME < db/schema-mysql.sql
java -jar target/dssad-cloud-platform.jar --spring.profiles.active=prod   # validate 通过才算一致
```
- 生产 `ddl-auto=validate`：**实体与 MySQL 不一致会启动失败**。
- `mvn test` 用 H2 + `create-drop`，**完全不校验 MySQL DDL** —— 必须跑一次真实启动。
- `validate` **不校验索引**：索引只能靠「仓储方法 ↔ 索引」人工对照发现缺口。
- 线上加索引：`ALTER TABLE ... ALGORITHM=INPLACE, LOCK=NONE`。

## 数据生命周期（三张流水表都有清理任务，配置别忘）
| 表 | 任务 | 配置 |
|---|---|---|
| `t_vehicle_track_point` | `TelemetryRetentionService`（03:30，native `DELETE ... LIMIT` 分批）| `dssad.retention.track-point-days` |
| `t_vehicle_state_snapshot` | 同上 | `dssad.retention.state-snapshot-days` |
| `t_mqtt_message_log` | `MqttAuditService.purgeExpired`（03:00）| `dssad.audit.retention-days` |
- 保留天数 **0 = 不清理**（生产禁用）；容量按「单行 178 B（轨迹）/ 1000 台车 14.3 GB/天」反推。
- 清理任务时间刻意错开：00:05 地图拉取 / 03:00 留痕 / 03:30 遥测。

## 构建与运行（本机）
```bash
# 后端（Git Bash 必须用 mvn.cmd）
export MAVEN_HOME="C:/Program Files/apache-maven-3.9.9" JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
"$MAVEN_HOME/bin/mvn.cmd" -B test
env -u SERVER__PORT java -jar target/dssad-cloud-platform.jar --server.port=8080  # 沙箱注入 SERVER__PORT，必须覆盖

# 前端
cd cloud-platform-web && npm run type-check && npm run build
```

## 文档交付规范
- 每份文档交付 **Markdown 源 + 深色主题单文件 HTML**，用 `python scripts/md2html.py docs/xx/文件.md` 转换。
- 转换后**必做六项校验**（元素计数 / 标题层级 / 样式可达性 / 锚点 / 零外部请求 / 内容探针），
  详见 skill `markdown-to-dark-html-doc`。
- 文档须包含「缺陷与待办清单」章节：上游 PDF 偏差记 D 系列，内部不一致记 I 系列，待决策记 T 系列。
