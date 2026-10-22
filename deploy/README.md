# KnowEngine NAS 部署说明

企业知识库（KnowEngine）在 NAS `<NAS_IP>` 上的部署方式与已知坑位记录。

## 部署拓扑

| 项目 | 值 |
|---|---|
| 容器名 | `llmentor-know-engine` |
| 镜像 | `eclipse-temurin:21-jre` |
| 网络 | `host`（应用直接监听 `*:8009`） |
| 访问地址 | http://<NAS_IP>:8009/login.html |
| jar | `/vol1/llmmentor/know-engine/backend/know-engine-1.0.0.jar` |
| 覆盖配置 | `/vol1/llmmentor/know-engine/backend/config/application.yml` |
| 日志 | `/vol1/llmmentor/know-engine/backend/logs/know-engine.log` |
| 备份 | `/vol1/llmmentor/know-engine/backend/backup/` |
| JVM | `-Xms512m -Xmx2048m`，约 26s 启动完成 |

## 部署命令

```bash
# 全量：打包 + 上传 + 重建容器 + 健康检查
./deploy/deploy-nas.sh

# 只更新覆盖配置并重启
./deploy/deploy-nas.sh config

# 只重启
./deploy/deploy-nas.sh restart
```

打包必须在 monorepo 根目录执行（父 POM 做 `@dashscope.api.key@` 资源过滤），且 `JAVA_HOME` 必须是 JDK 21
（Homebrew 默认 JDK 27 会让 Lombok 抛 `ExceptionInInitializerError`）。

## 依赖的中间件（全部在 NAS 本机）

| 组件 | 地址 | 说明 |
|---|---|---|
| MySQL | `127.0.0.1:3306/know_engine` | 与 ecom 的 `llmentor-ecom-mysql`(33061) 是两个实例 |
| Redis | `127.0.0.1:6379` | Redisson 分布式锁 + 对话缓存，**连不上会启动失败** |
| Elasticsearch | `127.0.0.1:9200` | 索引 `know-engine-vector`，首次向量化时自动创建 |
| MinIO | `127.0.0.1:9000` | bucket `know-engine`（已存在） |
| Neo4j | `bolt://127.0.0.1:7687` | 车型图谱（Text2Cypher 用） |
| XXL-Job | `127.0.0.1:8081/xxl-job-admin` | 失败任务补偿；注册失败只告警，不阻断启动 |
| Ollama | `<OLLAMA_HOST>:11434` | chat `qwen3.8:27b-mlx`，embedding `bge-m3`（1024 维） |

## 坑位记录

### 1. NAS 上不能用 `<NAS_IP>` 访问自己的 Docker 端口

实测 `3306/6379/9200/9000/7687/8081` 在 `127.0.0.1` 全部可达，但换成 NAS 自身 IP 全部
`Connection refused`（宿主机防火墙丢弃非 loopback 的 docker-proxy 流量）。jar 内
`application.yml` 写的是 `<NAS_IP>`，因此必须有覆盖配置把地址改成 `127.0.0.1`，
否则应用会在 Redisson 初始化阶段失败并无限重启（exit code 0 + `restart always`）。

覆盖配置通过 `--spring.config.additional-location=file:/app/config/` 生效，优先级高于 jar 内的
`application.yml`。

### 2. 中文乱码：JDBC URL 必须显式带 characterEncoding

jar 内默认 URL 只有 `?rewriteBatchedStatements=true`，实测中文按 latin1 读写，
`user_info` / `staff_info` 的姓名被写成双重编码的 `æµ‹è¯•å®¢æˆ·`。
覆盖配置已补 `useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`。

历史坏数据用下列方式还原（先把 `HEX()` 里的 `C3` 全部消掉）：

```sql
UPDATE user_info SET name = CONVERT(BINARY(CONVERT(name USING latin1)) USING utf8mb4)
 WHERE HEX(name) LIKE '%C3%';
```

### 3. 线上库是旧版表结构，需补列

NAS 上 `know_engine` 库建于 2026-09-16，比 `sql/tables.sql` 旧：
`chat_message` 缺 `cache_hit / helpful / feedback_comment / feedback_at`，
且没有 `qa_cache_entry` 表。缺列会让对话接口在保存消息时抛
`Unknown column 'cache_hit' in 'field list'`（SSE 已提交响应头，前端表现为流中断）。

补丁语句就在 `sql/tables.sql` 末尾，已执行并备份到 `backup/full_backup_before_patch_20261022.sql`。

### 4. 上传与切片接口的"可选参数"其实是必填

`POST /api/document/upload` 的 `accessibleBy` 标注 `required = false`，但
`KnowledgeDocument.create()` 直接 `RoleEnum.valueOf(accessibleBy)`，
不传会抛 `NullPointerException: Name is null`。同理 `POST /api/document/split/{id}`
的 `overlap` 不传会在 `DocumentSplitterFactory` 抛 NPE。调用时必须显式传：

```bash
-F 'accessibleBy=VISITOR'          # 或 OWNER / CUSTOMER_SERVICE
?splitType=TITLE&chunkSize=500&overlap=50&titleLevel=2
```

### 5. 会话标题生成的模型名曾是写死的云端模型

`ChatApplicationService` 里标题生成模型硬编码 `qwen3.5-flash`（DashScope 模型），
本地 Ollama 端点下必然 404，标题一直停在临时标题。已改为
`know-engine.chat.title-model`（默认跟随主对话模型）。想更快可在覆盖配置里指定
`qwen2.5:7b`。

### 6. 意图识别 prompt 仍是汽车行业

`prompts/` 下的意图识别提示词是 7 大汽车意图（售前购车 / 售后维修 / 车辆使用 / 投诉 /
营销政策 / 闲聊 / 其他），`related` 判定也以"是否与汽车领域相关"为准。
**问电商问题（如"退款多久到账"）会被判为 `related: false` 走通用对话，不会检索知识库。**
要让知识库服务电商客服，必须先改意图 prompt 与实体定义。

## 验证清单

```bash
# 页面
curl -s -o /dev/null -w '%{http_code}\n' http://<NAS_IP>:8009/login.html   # 200

# 客户端登录（种子账号）
curl -s -X POST http://<NAS_IP>:8009/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"18650567815","password":"123456"}'

# 员工登录（种子账号）
curl -s -X POST http://<NAS_IP>:8009/auth/staffLogin \
  -H 'Content-Type: application/json' \
  -d '{"empId":"TEST001","password":"123456"}'

# 种子数据写入方式（首次部署）
docker exec -i mysql mysql -uroot -p<pass> know_engine --default-character-set=utf8mb4 < sql/tables.sql
```

> 本机 curl 若设置了 `HTTP_PROXY`，访问 NAS 需加 `--noproxy '*'`，否则会得到代理返回的 502。
