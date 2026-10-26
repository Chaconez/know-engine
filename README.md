# KnowEngine — 企业级智能知识引擎

> 基于 RAG 架构的企业知识管理与智能问答引擎，实现从文档接入、智能切片、向量化存储到多源检索、意图路由、流式对话的全链路闭环。

**作者：章文杰 · 技术演示项目**

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.11.0-blue)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## 架构总览

### 对话流程（Query Path）

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            用户交互层 (SSE)                                        │
│       ChatController · upload.html · DingTalkBot(钉钉机器人)                     │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                │
                   ┌────────────▼────────────┐
                   │     异步标题生成          │
                   │  VirtualThread + flash  │
                   └─────────────────────────┘
                                │
              ┌─────────────────▼──────────────────┐
              │          1. 意图识别                 │
              │   IntentRecognitionService (LLM)    │
              │   Structured Output → Record        │
              │   · related? → 通用对话              │
              │   · intent? → Prompt 路由           │
              │   · entities → 结构化实体            │
              └────┬─────────────────────┬─────────┘
                   │ 不相关               │ 相关
           ┌───────▼───────┐    ┌────────▼─────────────────────────────────────┐
           │  通用对话      │    │           2. 查询改写                          │
           │  CommonChat   │    │  KnowEngineQueryTransformer (LLM)            │
           │  Service      │    │  4维策略: 简洁/抽象/纠错/标准化                  │
           └───────┬───────┘    │  改写结果 VirtualThread 异步回写 DB             │
                   │            └────────┬──────────────────────────────────────┘
                   │                     │
                   │         ┌───────────▼──────────────────────────────────────┐
                   │         │           3. 查询路由                             │
                   │         │  KnowEngineQueryRouter (LLM)                     │
                   │         │  {intent, strategy, confidence} → 路由决策        │
                   │         └──┬────────────┬────────────┬─────────────────────┘
                   │            │            │            │
                   │    ┌───────▼──┐   ┌─────▼────┐  ┌──▼──────────┐
                   │    │ knowledge│   │relational│  │  graph_db   │
                   │    │   _base  │   │   _db    │  │             │
                   │    └───────┬──┘   └─────┬────┘  └──┬──────────┘
                   │            │            │           │
    ┌──────────────┼────────────▼────────────▼───────────▼────────────────────────┐
    │              │            4. 多源内容检索 (ProgressAware)                    │
    │              │                                                              │
    │    ┌─────────▼─────────┐  ┌────────▼────────┐  ┌──────────▼──────────────┐  │
    │    │  ES Content       │  │  Text2SQL        │  │  Text2Cypher           │  │
    │    │  Retriever        │  │  Retriever       │  │  Retriever             │  │
    │    │                   │  │                  │  │                        │  │
    │    │  · KNN 向量检索     │  │  · LLM→SQL      │  │  · LLM→Cypher          │  │
    │    │  · 全文检索         │  │  · JDBC 执行     │  │  · Neo4j 执行          │  │
    │    │  · 混合检索         │  │  · table_meta   │  │  · Schema 自动感知      │  │
    │    │  · 父分段回溯       │  │  · Schema 驱动   │  │  · 降级回退 ES          │  │
    │    │  · 兄弟分段补全     │  │              │  │                         │  │
    │    └─────────┬─────────┘  └────────┬────────┘  └──────────┬──────────────┘  │
    │              │                     │                      │                  │
    │              └─────────────┬───────┘──────────────────────┘                  │
    │                            │                                                │
    │              ┌─────────────▼──────────────────────────────────────────────┐  │
    │              │           5. Reranking + 聚合                               │  │
    │              │  ProgressAwareContentAggregator                            │  │
    │              │  · BGE-RERANKER (ONNX 本地推理)                             │  │
    │              │  · 引用溯源 → RagReference → [REFERENCE] 事件               │  │
    │              └─────────────┬──────────────────────────────────────────────┘  │
    │                            │                                                │
    │              ┌─────────────▼──────────────────────────────────────────────┐  │
    │              │         6. LLM 流式生成 + 卡片输出                           │  │
    │              │  · Prompt 动态注入 (意图→领域 Prompt)                        │  │
    │              │  · Reactor Flux SSE 逐 Token 推送                           │  │
    │              │  · [CARD] 结构化卡片事件 (车型/订单)                           │  │
    │              └────────────────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────────────────────────────┘
```

### 知识入库流程（Ingestion Path）

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          知识管理管道 (Ingestion Pipeline)                         │
└──────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────┐    ┌──────────────┐    ┌──────────────────────┐    ┌──────────────┐    ┌──────────────┐
  │ 文件上传   │    │ 格式转换      │    │ 智能切片               │    │ 向量化嵌入     │    │ ES 索引存储   │
  │          │    │              │    │                      │    │              │    │              │
  │ · PDF    │    │ · MinerU     │    │ DocumentSplitter     │    │ bge-m3       │    │ · KNN 索引   │
  │ · Word   │───▶│   深度解析    │───▶│   Factory            │───▶│ (Ollama      │───▶│ · 全文索引   │
  │ · Excel  │    │ · Word→MD   │    │                      │    │  1024d)      │    │ · 混合索引   │
  │ · MD/TXT │    │ · Excel直通  │    │ · TITLE  → Parent   │    │              │    │              │
  │ · CSV    │    │ · Tika 类型  │    │ · SMART  → Auto     │    │ 跳过:         │    │              │
  │          │    │   检测       │    │ · LENGTH → ByWord   │    │ skipEmbedding │    │              │
  └──────────┘    └──────┬───────┘    │ · REGEX  → ByRegex  │    │ =1 的父分段   │    │              │
                         │            │ · SEPARATOR         │    └──────────────┘    └──────────────┘
                         │            │ Excel → ExcelSplitter│
                         │            │   · 键值对模式        │
                         │            │   · HTML 表格模式     │
                         │            │ 雪花ID → chunkId     │
                         │            └──────────┬───────────┘
                         │                       │
                         │         ┌─────────────▼──────────────┐
                         │         │    Spring Event 事件驱动     │
                         │         │                            │
                         │         │  ConvertedEvent ──────────▶ │
                         │         │      ↓ 自动触发切片         │
                         │         │  ChunkedEvent ───────────▶ │
                         │         │      ↓ 自动触发向量化       │
                         │         │                            │
                         │         │  @DistributeLock 并发保护   │
                         │         │  XXL-Job 补偿 → 最终一致    │
                         │         └────────────────────────────┘
```

### 数据与基础设施层

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           数据存储层                                               │
├──────────────┬────────────────┬──────────────┬──────────────┬─────────────────────┤
│              │                │              │              │                     │
│   MySQL      │  Elasticsearch │    Neo4j     │    MinIO     │      Redis          │
│              │                │              │              │                     │
│ · 业务数据    │  · KNN 向量     │  · 车型图谱    │  · 原始文档  │  · 三级缓存 L2        │
│ · Text2SQL   │  · 全文索引     │  · 零部件      │  · 转换文档  │  · 分布式锁           │
│ · table_meta │  · 混合检索     │  · 故障链      │  · ZIP 包   │  · Redisson         │
│ · 会话/消息   │  · 父子分段     │  · APOC        │  · 多版本   │  · 父分段缓存         │
│ · 版本管理    │  · 权限过滤     │  · Text2Cypher│             │  · 权限缓存           │
│ · 权限元数据   │  · 版本过滤     │               │             │                     │
│ · 乐观锁      │  · 元数据过滤   │               │              │                    │
│ · 逻辑删除    │               │                │              │                    │
└──────────────┴───────────────┴────────────────┴──────────────┴────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────┐
│                           基础设施层                                               │
├──────────────┬───────────────┬──────────────┬──────────────┬─────────────────────┤
│              │               │              │              │                     │
│ SnowflakeId  │ @DistributeLock│  JsonUtil   │ VirtualThread│     XXL-Job        │
│              │               │              │              │                     │
│ · chunkId    │  · Redisson   │  · 7步修复    │  · 标题生成   │  · 补偿任务         │
│ · parent     │  · SpEL Key   │  · Markdown  │  · 改写回写   │  · 定时兜底         │
│ · brother    │  · 自动续期    │  · 引号/逗号   │  · 钉钉回调   │  · 失败重试         │
│ · 版本链路    │  · waitTime=0 │  · 容错包装   │  · Java 21   │  · 最终一致         │
│ · 全链路唯一   │  · 权限更新锁  │              │  · 零阻塞    │                     │
└──────────────┴───────────────┴──────────────┴──────────────┴─────────────────────┘
```

---

## 核心功能

### 1. 全链路知识接入 (Ingestion Pipeline)

从原始文档到可检索知识，全程自动化：

- **多格式解析**：支持 PDF（MinerU 深度解析）、Word、Excel、Markdown、TXT、Excel等
- **智能切片**（5 种策略，工厂模式按需选择）：
  - `MarkdownHeaderParentTextSplitter` — 基于标题层级切片，保留父子分段关系（Parent-Child Chunking），支持 chunkSize / overlap 精细控制、代码块保护、超出 chunkSize 自动二次切割
  - `MarkdownHeaderBrotherTextSplitter` — 兄弟分段关联，检索时自动补全同级上下文
  - `ExcelSplitter` — 针对结构化数据的双模式切片（键值对模式 & HTML 表格模式），按 chunkSize 智能分块，确保同一行数据不被拆分
  - `DocumentByWordSplitter` / `DocumentByRegexSplitter` — 按长度 / 正则 / 分隔符切分，满足不同文档结构需求
  - `SMART` 智能切分 — 自动使用 MarkdownHeaderParentTextSplitter，overlap 为 chunkSize 的 10%
- **事件驱动处理**：Spring `ApplicationEvent` 串联 文档转换 → 切片 → 向量化，每个阶段独立解耦
- **补偿机制**：XXL-Job 定时扫表，自动重试失败任务，保障最终一致性

### 2. 关键检索增强

- 查询改写（`KnowEngineQueryTransformer`）：4 维改写策略 — 简洁改写、抽象概念改写、错别字纠正、车型信息标准化。改写结果还通过 Java 21 的虚拟线程异步回写到数据库，不阻塞主流程。
- 父子分段扩展（`KnowEngineElasticsearchContentRetriever`）：命中子分段时，自动回溯父分段获取完整语义上下文。
- 本地 Reranking（`BgeScoringModel`）：基于 ONNX Runtime 加载 BGE-RERANKER 模型，进程内推理，零网络延迟
- 查询路由（`KnowEngineQueryRouter`）：三路由检索架构，由 LLM 智能识别问题，判断通过不同的数据源做检索
- **Text2SQL**（`SqlDatabaseContentRetriever`）：LLM 根据数据库 Schema 自动生成 SQL 并通过 JDBC 执行查询，支持结构化业务数据（订单、保险、召回信息等）的自然语言查询。基于 `table_meta` 表存储的表结构元数据驱动 Schema 感知，自动注入当前日期等上下文变量；查询结果以自然语言格式返回
- **Text2Cypher**（`Neo4jText2CypherRetriever`）：LLM 根据图数据库 Schema 自动生成 Cypher 查询语句，在 Neo4j 中执行实体关系检索，适用于车型图谱、零部件供应链、故障影响链等关系型场景。Cypher 查询失败时自动降级回退到 ES 检索，保障系统鲁棒性；查询结果与 ES 向量检索结果通过 `ContentAggregator` 统一聚合，经 Reranking 后共同参与最终生成

| 数据源 | 检索方式 | 适用场景 |
|--------|---------|--------|
| **Elasticsearch** | KNN 向量检索 + 全文检索 + 混合检索 | 语义相似性匹配、非结构化知识 |
| **MySQL** | Text2SQL（LLM 生成 SQL） | 结构化数据查询，如订单、保险、召回信息 |
| **Neo4j** | Text2Cypher（LLM 生成 Cypher + 降级回退） | 实体关系查询，如车型图谱、零部件供应链、故障影响链 |

### 3. 意图识别与动态 Prompt

- **6 大意图分类**：售前咨询与购买、售后维修与保养、车辆使用与技术指导、投诉与维权、汽车营销政策、其他
- **相关度判断**：LLM 首先判断用户问题是否与汽车领域相关，不相关问题直接走通用对话，避免 RAG 误检索
- **结构化实体提取**：车型、订单号、经销商、故障描述、预约时间、零部件、车辆功能
- **Prompt 动态路由**：根据识别结果加载对应的领域 Prompt 文件，Prompt 使用 `ConcurrentHashMap` 本地缓存，避免重复 IO
- **LangChain4j Structured Output**：通过 `@JsonPropertyDescription` 注解约束 LLM 输出为 `IntentRecognitionResult` Record，确保意图识别结果结构化、可编程处理

### 4. 流式对话体验

- **SSE 实时推送**：基于 Reactor `Flux` 的全链路流式输出，Token 级逐字推送
- **进度可见**：RAG 管道每个环节（意图识别 → 问题改写 → 问题路由 → 排序筛选 → 生成回答）均向前端推送 `[PROGRESS]` 事件，消除等待焦虑
- **RAG 引用溯源**：检索结果携带文档来源、分片内容、Rerank 分数，支持可解释性审查
- **异步标题生成**：新对话自动以 Java 21 虚拟线程 + 本地 Ollama 模型（`know-engine.title.chat-model`）异步生成摘要标题，不阻塞首 Token 延迟；失败时保留临时标题，保障主流程不受影响
- **交互卡片输出**：SSE 流式响应中支持 `[CARD]` 结构化卡片事件，将车型对比、订单详情、召回信息等结构化数据以卡片形式呈现。LLM 通过 Prompt 引导在特定场景输出结构化 JSON 标记，后端解析后转换为 `[CARD]` 事件，前端根据卡片类型（`vehicle_info` / `order_detail` / `recall_notice` / `comparison_table`）渲染对应 UI 组件，支持点击展开详情、跳转链接等交互操作
- **钉钉机器人接入**：接入钉钉机器人，支持单聊和群聊 @机器人两种模式。钉钉消息回调经签名验证后转换为 KnowEngine 统一对话接口调用，流式收集完整回复后通过钉钉 API 回复；群聊模式自动识别 @内容 作为用户问题

### 5. 知识库管理

- **双类型知识库**：`DOCUMENT_SEARCH`（语义检索型）和 `DATA_QUERY`（数据查询型）
- **完整文档生命周期**：INIT → UPLOADED → CONVERTING → CONVERTED → CHUNKED → VECTOR_STORED
- **文档多版本管理**：支持同一知识文档的多版本共存，`knowledge_document.extension` JSON 字段存储版本元数据（`version`、`parentVersionDocId`、`changeLog`）。新版本发布后旧版本自动归档，检索时默认使用最新版本；版本切换通过修改 ES 中 `docVersion` 的 Filter 条件实现热切换，无需重新向量化；`expire_date` 字段支持文档到期后自动从检索结果中过滤；版本回滚一键切回历史版本，ES Filter 实时生效
- **文档检索权限体系**：基于角色和组织的文档访问控制，`knowledge_document.accessible_by` 字段存储文档可见范围。切片时将 `accessibleBy` 写入 `knowledge_segment.metadata`，向量化时同步到 ES 索引，检索时在 `EmbeddingSearchRequest` 中注入 `Filter` 条件按当前用户身份过滤。
- **MinIO 对象存储**：文档文件统一存储，支持 URL 直接访问
- **乐观锁 + 逻辑删除**：数据安全，防并发冲突

---

## 技术亮点

### 🔥 本地 Reranking — 零外部依赖的精排方案

基于 ONNX Runtime 在 JVM 进程内直接运行 BGE-RERANKER 模型，无需部署独立的 Reranking 微服务。采用双重检查锁单例模式，模型仅加载一次，全生命周期复用。解决了 macOS Monterey 兼容性问题（降级 onnxruntime 至 1.17.1），并支持 JAR 包内模型文件的 classpath 自动解析与临时文件释放。

### 🔥 父子分段检索 — 语义完整性的关键保障

检索时命中细粒度子分段后，自动通过 `parentChunkId` 回溯到父分段的完整文本进行替换，同时通过 `brotherChunkId` 补全同级的兄弟分段。这一机制解决了"切片后语义截断"的 RAG 经典痛点，在保持检索精度的同时，为 LLM 提供完整的上下文窗口。

### 🔥 三源智能路由 — 一句话自动选数据源

`KnowEngineQueryRouter` 利用 LLM 对用户 Query 进行语义分析，输出 `{intent, strategy, confidence}` 结构化决策，自动路由到 Elasticsearch / MySQL / Neo4j 中最合适的数据源。路由失败自动降级为空结果，保障系统鲁棒性。

**Text2SQL 路径**：当 `strategy = relational_db` 时，路由到 `SqlDatabaseContentRetriever`，LLM 根据 `table_meta` 表存储的表结构元数据生成 SQL，自动注入当前日期等上下文变量。

**Text2Cypher 路径**：当 `strategy = graph_db` 时，路由到 `Neo4jText2CypherRetriever`，LLM 根据图数据库 Schema 自动生成 Cypher 查询，无需预先编写查询模板。Cypher 查询失败时自动降级回退到 ES 检索

### 🔥 流式 RAG 管道 — 进度感知的端到端流式架构

在 `DefaultRetrievalAugmentor` 的标准流程中，通过装饰器模式（`ProgressAwareContentRetriever` / `ProgressAwareContentAggregator`）注入进度回调，将阻塞式 RAG 操作调度到 `Schedulers.boundedElastic()`，通过 `Flux.create()` + `publishOn(Schedulers.parallel())` 实现进度消息与 LLM Token 的有序混合推送。

SSE 事件类型扩展为 4 类：`[PROGRESS]`（处理进度通知）、`[REFERENCE]`（RAG 引用溯源）、`[CARD]`（结构化交互卡片）、Token（逐字文本）。其中 `[CARD]` 事件由 LLM 在特定场景输出结构化 JSON 标记触发，后端解析后转换为 `[CARD]:{"type":"vehicle_info", "data":{...}}` 格式推送，前端根据卡片类型渲染对应 UI 组件（表格、时间线、对比视图等），支持点击展开详情、跳转链接等交互操作。

钉钉机器人接入同一流式管道：钉钉消息回调经签名验证后，调用 `ChatApplicationService.streamChat()` 流式收集完整回复，通过钉钉 API 回复消息；长回复自动分片发送，`[CARD]` 事件自动转为钉钉 ActionCard 消息格式，`[REFERENCE]` 引用来源附带在回复中提升可信度。

### 🔥 Spring 事件驱动 — 文档处理的优雅编排

文档从上传到入库全程通过 Spring `ApplicationEvent` 串联，Controller 只负责发布事件，业务逻辑完全由 EventListener 异步驱动。结合 XXL-Job 补偿任务，实现"事件驱动 + 定时兜底"的最终一致性保障。

### 🔥 分布式锁注解 — 声明式并发控制

基于 Redisson + AOP 实现的 `@DistributeLock` 注解，支持 SpEL 表达式动态 Key、超时自动续期、等待超时等策略，一行注解即可保护关键业务操作（如文档上传、切片）的幂等性。

### 🔥 多格式文档的智能切片能力

针对 Markdown，MarkdownHeaderParentTextSplitter 基于标题层级进行 Parent-Child Chunking，支持代码块保护、按 chunkSize 二次切割、以及父子关系元数据注入。针对 Excel/CSV，ExcelSplitter 实现了 RAGFlow 风格的双模式输出（键值对模式和 HTML 表格模式），并且支持按字符数智能分块，确保同一行数据不会被拆到不同分片中。文件类型检测则通过文件头魔数实现，避免依赖文件扩展名。

### 🔥 RAG 引用溯源

ProgressAwareContentAggregator 在重排序完成后，会从检索结果中提取文档来源、分片内容、Rerank 分数等元数据，组装成 RagReference 结构，既异步持久化到数据库，也实时通过 [REFERENCE] 事件推送给前端，让每一次回答都有据可查。

### 🔥 雪花算法 ChunkId — 分布式唯一标识

所有知识分段的 `chunkId` 均通过自研 `SnowflakeIdGenerator` 生成，基于雪花算法（64 位：1 位符号位 + 41 位时间戳 + 10 位工作机器 ID + 12 位序列号），确保在分布式环境下全局唯一、趋势递增。相比数据库自增 ID，雪花 ID 天然支持分库分表、跨服务关联；相比 UUID，雪花 ID 更短、可排序、对索引更友好。`parentChunkId` 和 `brotherChunkId` 同样使用雪花 ID，贯穿切片 → 存储 → 检索 → 扩展全链路。

在文档多版本管理场景中，`parentVersionDocId` 通过雪花 ID 关联历史版本链，`knowledge_segment.metadata` 中的 `docVersion` 字段与雪花 ID 配合，实现版本热切换时的精准过滤——修改 ES 中 `docVersion` 的 Filter 条件即可切换活跃版本，无需重新向量化，雪花 ID 的趋势递增特性确保版本时序可追溯。`expire_date` 字段与版本元数据联动，文档到期后自动从检索结果中过滤。

### 🔥 分布式锁 — 文档处理并发控制

基于 Redisson + AOP 实现的 `@DistributeLock` 注解，支持 SpEL 表达式动态 Key（如 `#document.docId`）、场景隔离（`scene`）、超时自动续期、等待超时等策略。在文档切片（`split`）和向量化（`embedAndStore`）等关键操作上，通过 `@DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)` 实现同一文档的互斥处理：当 `waitTime = 0` 时，已持有锁的请求直接失败，避免重复切片或并发写入导致数据不一致。

### 🔥 Excel 双模式智能切片 — 表格数据的 RAG 适配

针对 Excel/CSV 的结构化数据特点，`ExcelSplitter` 提供两种输出模式：
- **键值对模式**（默认）：将每行数据转换为 `表头1：值1; 表头2：值2; ...` 格式，语义自包含，适合直接进行向量检索
- **HTML 表格模式**：保留原始表格结构，按 `chunkSize` 智能分块输出 `<table>` 片段，每个分块自动携带表头，确保独立可读

HTML 模式下采用行级完整性保障：同一行数据绝不会被拆分到不同分片中，即使该行超过 `chunkSize`，也保证至少包含一行。分块间通过共享表头实现上下文自足，解决了大表格跨分片后的信息丢失问题。

文件格式检测通过文件头魔数实现（ZIP 头 → `.xlsx`，OLE 头 → `.xls`，逗号+换行 → `.csv`），不依赖文件扩展名，防止用户重命名导致的解析错误。同时支持 BOM 编码自动检测（UTF-8/UTF-16BE/UTF-16LE）。

### 🔥 表格与图片跨分片完整性保障

Markdown 文档中的表格和图片在被 `chunkSize` 二次切割时面临语义截断风险，本系统通过以下机制解决：
- **父子分段保留完整原文**：超出 `chunkSize` 的分片会保留一份完整原文（标记 `skipEmbedding = 1`，不参与向量检索），同时生成多个子分片（携带 `parentChunkId`）。检索时命中子分片后，自动通过 `parentChunkId` 回溯到 Redis 缓存中的父分片完整文本进行替换，确保表格和图片的上下文完整性
- **兄弟分段自动补全**：通过 `brotherChunkId` + `brotherChunkIndex` + `brotherChunkTotal` 元数据，检索命中任一子分片时自动补全同级所有兄弟分段，拼出完整内容
- **代码块保护**：Markdown 切片器识别 ``` 代码围栏，将整个代码块作为一个不可分割的单元处理，避免代码被截断

### 🔥 虚拟线程 + 轻量模型 — 异步标题生成零延迟

新会话首条消息发出后，系统通过 `Thread.ofVirtual().name("title-summary-" + conversationId).start(...)` 启动 Java 21 虚拟线程，异步调用本地 Ollama 模型（`know-engine.title.chat-model`）生成对话标题并回写数据库。关键设计：
- **零阻塞**：虚拟线程不占用平台线程，不阻塞 SSE 流式响应的首 Token 输出
- **模型选型**：标题生成任务对推理能力要求低，可用 `know-engine.title.chat-model` 单独指向更小的本地模型，进一步降低延迟
- **关闭思考模式**：通过 `customParameters(Map.of("enable_thinking", false))` 关闭思考型模型的 CoT 过程，进一步降低延迟
- **优雅降级**：标题生成失败时保留临时标题，不影响对话功能

### 🔥 意图识别 — LLM Structured Output 约束

通过 LangChain4j 的 `@AiService` + `@SystemMessage` + `@JsonPropertyDescription` 注解体系，将意图识别结果约束为 `IntentRecognitionResult` Record 类型，包含 `reasoning`（推理过程）、`related`（是否相关）、`intent`（意图分类）、`entities`（结构化实体）四个字段。LLM 输出被强制对齐到 Java 类型系统，避免了自由文本输出的不确定性。识别流程在 RAG 管道前端通过 `Mono.fromCallable().subscribeOn(Schedulers.boundedElastic())` 调度，不阻塞 WebFlux 事件循环。

### 🔥 多级缓存 Chunk 检索 — HashMap + Redis + MySQL 三级缓存

`KnowledgeSegmentServiceImpl.getTextByChunkId()` 实现了 HashMap → Redis → MySQL 的三级缓存策略，用于父子分段检索时快速获取父分段完整文本：
- **L1 缓存（HashMap）**：以 `chunkId` 为 Key，在当前线程缓存父分段完整文本，避免同一次检索中重复查询 Redis 和数据库
- **L2 缓存（Redis）**：以 `chunkId` 为 Key，30 秒 TTL，命中即返回
- **L3 回源（MySQL）**：Redis 未命中时查询 `knowledge_segment` 表，结果写入 Redis
- **防缓存击穿**：查询结果为空时缓存空字符串，避免同一不存在的 `chunkId` 反复穿透到数据库

这一机制在父子分段检索场景下尤为关键：每条检索结果都可能触发父分段回溯，三级缓存将数据库查询压力降低一个量级。

在文档检索权限体系中，L2 缓存同时缓存权限元数据（`accessibleBy`），检索时在 `EmbeddingSearchRequest` 中注入 `Filter` 条件按当前用户身份过滤，权限变更时通过事件驱动批量失效关联缓存并更新 ES 索引，确保权限变更实时生效。权限过滤与版本过滤（`docVersion`）均通过 ES Filter 条件实现，与三级缓存协同工作，在保障数据安全的同时不影响检索性能。

### 🔥 LLM JSON 输出修复 — 7 步容错管道

LLM 在结构化输出场景中经常产生不符合 JSON 规范的结果（如包裹 Markdown 代码块、使用中文引号、尾部多余逗号等）。`JsonUtil.fixJson()` 实现了 7 步修复管道：
1. **Markdown 代码块提取**：剥离 ` ```json ... ``` ` 包裹
2. **前后垃圾字符移除**：定位首个 `{` / `[` 到末尾 `}` / `]` 之间的有效 JSON
3. **引号修复**：中文引号 → 英文引号，单引号 → 双引号
4. **尾部逗号修复**：移除 `},` / `],` 中的多余逗号
5. **缺失引号修复**：为无引号的键名自动添加双引号
6. **转义字符修复**：清理字符串中的非法换行符、制表符
7. **最终容错**：以上修复后仍无效，将原始文本包装为 `{"content": "..."}` 结构

修复后通过 `ObjectMapper.readTree()` 验证有效性，确保下游代码始终拿到合法 JSON。该工具广泛用于意图识别、查询路由、查询改写等所有依赖 LLM 结构化输出的环节。

### 🔥 工厂模式多策略切片 — DocumentSplitterFactory

`DocumentSplitterFactory` 通过工厂模式封装 5 种切片策略，根据用户选择的 `SplitType` 动态创建对应的 `DocumentSplitter`：

| 切片策略 | 实现类 | 适用场景 |
|---------|--------|--------|
| LENGTH | DocumentByWordSplitter | 纯文本按字数切分 |
| TITLE | MarkdownHeaderParentTextSplitter | Markdown 按标题层级切分，保留父子关系 |
| REGEX | DocumentByRegexSplitter | 自定义正则表达式切分 |
| SEPARATOR | DocumentByRegexSplitter | 按指定分隔符切分 |
| SMART | MarkdownHeaderParentTextSplitter | 智能切分，自动计算 overlap（chunkSize × 10%） |

Excel/CSV 类型文档则独立走 `ExcelSplitter` 处理，不经过工厂模式。前端 `upload.html` 根据选择的切片方式动态显示/隐藏参数组（overlap、titleLevel、regex、separator），实现配置与策略的联动。

### 🔥 查询改写异步回写 — 虚拟线程不阻塞主流程

`KnowEngineQueryTransformer` 在完成查询改写后，通过 `Thread.ofVirtual().name("query-transform-" + chatMessageId).start(...)` 将改写结果异步回写到 `chat_message` 表的 `transformContent` 字段。回写操作与主流程完全解耦：即使回写失败，也不影响改写后的 Query 继续进入检索和生成环节。这一设计使得查询改写的 4 维策略（简洁改写、抽象概念改写、错别字纠正、车型信息标准化）在提升检索质量的同时，对端到端延迟零贡献。

---

## 技术栈

| 分类            | 技术                                        | 说明                      |
|---------------|-------------------------------------------|-------------------------|
| **语言/框架**     | Java 21 + Spring Boot 3.5                 | 虚拟线程、Record 模式匹配        |
| **AI 框架**     | LangChain4j 1.11.0                        | RAG 管道、AI Services、流式对话 |
| **LLM**       | Ollama qwen3.8:27b-mlx（本地）              | 意图识别、查询改写、路由决策、流式生成     |
| **多模态**      | Ollama qwen2.5vl:7b（本地）                 | 文档图片描述                     |
| **向量存储**      | Elasticsearch                             | KNN / 全文 / 混合检索         |
| **图数据库**      | Neo4j + APOC                              | Text2Cypher 实体关系查询      |
| **关系数据库**     | MySQL + MyBatis-Plus                      | 业务数据 + Text2SQL 结构化查询   |
| **对象存储**      | MinIO                                     | 文档文件存储                  |
| **缓存**        | Redis + Redisson                          | 分布式锁、父分段缓存              |
| **Reranking** | BGE-RERANKER (ONNX Runtime)               | 进程内本地推理，零网络开销           |
| **Embedding** | Ollama bge-m3（本地）                        | 1024 维向量                 |
| **文件解析**      | MinerU (PDF)、Apache Tika (类型检测)、EasyExcel | 多格式文档解析                 |
| **任务调度**      | XXL-Job 2.4                               | 补偿任务定时触发                |
| **响应式**       | Project Reactor                           | SSE 流式推送、非阻塞调度          |
| **连接池**       | Druid                                     | SQL 监控、慢查询检测            |

---

## 项目结构

```
know-engine/
├── ai/                          # AI 能力层
│   ├── config/                  #   Memory 配置
│   ├── constant/                #   意图枚举
│   ├── model/                   #   意图识别结果模型
│   └── service/                 #   意图识别、通用对话、Prompt 路由、标题摘要
├── chat/                        # 对话管理层
│   ├── constant/                #   会话状态、消息类型、检索来源
│   ├── controller/              #   对话/会话/消息 REST 接口
│   ├── entity/                  #   会话与消息实体
│   ├── mapper/                  #   MyBatis-Plus Mapper
│   └── service/                 #   对话应用服务（RAG 管道编排）
├── config/                      # 全局配置
│   ├── AsyncConfig              #   线程池配置
│   ├── MyMetaObjectHandler      #   自动填充时间戳
│   ├── MybatisPlusConfig        #   分页插件、乐观锁插件
│   └── XxlJobConfig             #   XXL-Job 执行器配置
├── document/                    # 知识文档管理层
│   ├── config/                  #   MinIO 配置
│   ├── constant/                #   文档状态、文件类型、知识库类型
│   ├── controller/              #   文档/切片 REST 接口
│   ├── entity/                  #   文档与切片实体
│   ├── event/                   #   Spring 事件（Converted/Chunked）
│   ├── job/                     #   XXL-Job 补偿任务
│   ├── mapper/                  #   MyBatis-Plus Mapper
│   ├── service/                 #   文档处理、切片、向量化、文件存储
│   └── util/                    #   文件类型检测 (Tika)
├── infra/                       # 基础设施
│   ├── json/                    #   JSON 工具
│   ├── lock/                    #   分布式锁注解 + AOP 切面
│   └── snowflake/               #   雪花 ID 生成器
└── rag/                         # RAG 检索增强层
    ├── config/                   #   ES / Neo4j 配置
    ├── constant/                 #   元数据 Key 常量
    ├── controller/               #   检索测试接口
    ├── model/                    #   路由结果模型
    └── modules/                  #   RAG 核心模块
        ├── KnowEngineQueryTransformer   # 查询改写器
        ├── KnowEngineQueryRouter        # 三源路由器
        ├── KnowEngineElasticsearchContentRetriever  # ES 检索器（父子分段扩展）
        ├── ProgressAwareContentAggregator          # 进度感知聚合器
        ├── reranker/              #   BGE-RERANKER 本地单例
        └── splitter/              #   文档切片器（Markdown/Excel）
```

---

## 快速开始

### 环境依赖

- JDK 21+
- MySQL 8.0+
- Redis 7.0+
- Elasticsearch 8.x（需开启 KNN 向量检索）
- Neo4j 5.x（需安装 APOC 插件）
- MinIO
- XXL-Job Admin 2.4+

### 配置

所有中间件连接信息和密钥均通过环境变量注入，默认值面向本机 `localhost`，按需覆盖：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `OLLAMA_BASE_URL` | 本地 Ollama OpenAI 兼容端点（全部模型均走此端点） | `http://localhost:11434` |
| `OLLAMA_API_KEY` | Ollama 不校验 Key，填任意非空值即可 | `ollama` |
| `OLLAMA_CHAT_MODEL` | 本地对话模型（意图识别、改写、路由、RAG 生成、标题） | `qwen3.8:27b-mlx` |
| `OLLAMA_EMBEDDING_MODEL` | 本地 embedding 模型 | `bge-m3` |
| `OLLAMA_VISION_MODEL` | 本地多模态视觉模型（文档图片描述） | `qwen2.5vl:7b` |
| `MYSQL_HOST` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | MySQL 连接 | `localhost` / `root` / 空 |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis 连接 | `localhost` / 空 |
| `NEO4J_URI` / `NEO4J_USERNAME` / `NEO4J_PASSWORD` | Neo4j 连接 | `bolt://localhost:7687` / `neo4j` / `password` |
| `ES_HOST` | Elasticsearch 地址 | `http://localhost:9200` |
| `MINIO_URL` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 对象存储 | `http://localhost:9000` / `minioadmin` / `minioadmin` |
| `OLLAMA_BASE_URL` | 本地 Ollama OpenAI 兼容端点 | `http://localhost:11434` |
| `XXL_JOB_ADMIN` | XXL-Job Admin 地址 | `http://localhost:8081/xxl-job-admin` |
| `FILE_PARSE_API_URL` | 文档解析服务地址 | `http://localhost:8000` |
| `DINGTALK_APP_KEY` / `DINGTALK_APP_SECRET` / `DINGTALK_ROBOT_CODE` | 钉钉机器人（`DINGTALK_ENABLED=true` 时启用） | 空 |

1. 配置环境变量（或直接修改 `application.yml`）
2. 执行 `src/main/resources/sql/tables.sql` 初始化数据库表

### 本地模型准备（必需）

项目所有模型均通过本地 Ollama 的 OpenAI 兼容端点调用（默认 `http://localhost:11434/v1`），不依赖任何云端大模型服务。启动前先拉取：

```bash
ollama pull qwen3.8:27b-mlx   # 主对话：意图识别、查询改写、路由、RAG 生成、会话标题
ollama pull bge-m3            # embedding：1024 维
ollama pull qwen2.5vl:7b      # 多模态：文档图片描述
```

如需换模型，用 `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBEDDING_MODEL` / `OLLAMA_VISION_MODEL` 覆盖即可；也可按用途在 `application.yml` 的 `know-engine.rag/title/vision.chat-model` 单独指定。
注意：**更换 embedding 模型后向量维度可能变化，必须重建 ES 索引并重新向量化**。

### 本地 Reranker 模型（可选）

仓库不含模型文件（`model_quantized.onnx` 约 544MB，超出 GitHub 单文件 100MB 限制）。一条命令自动下载到正确位置：

```bash
./scripts/download-reranker.sh
# 默认走 hf-mirror.com 镜像；如需官方源：
# HF_ENDPOINT=https://huggingface.co ./scripts/download-reranker.sh
```

脚本会拉取 [onnx-community/bge-reranker-v2-m3-ONNX](https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX) 的量化模型与 tokenizer，并校验完整性，落地到：

```
src/main/resources/model/bge-reranker-model/
├── model_quantized.onnx
└── tokenizer.json
```

未下载时服务仍可启动，检索退化为不加 rerank 的融合排序。

### 构建 & 运行

```bash
mvn clean package -DskipTests
java -jar target/know-engine-1.0.0.jar
```

启动后访问 `http://localhost:8009`。

### 演示账号

| 入口 | 地址 | 账号 | 密码 |
|------|------|------|------|
| 客户端问答 | `http://localhost:8009/login.html` | 手机号 `18650567815` | `123456` |
| 员工后台 | `http://localhost:8009/staff-login.html` | 工号 `TEST001` | `123456` |
| Druid 监控 | `http://localhost:8009/druid/` | `admin` | `admin` |

根路径 `http://localhost:8009/` 会自动跳转到登录页。

### 容器化部署

`deploy/` 下有可直接用的 Docker 部署脚本与踩坑记录（NAS 上 host 网络必须走 `127.0.0.1`、
中文乱码、Druid 配置层级、模型外挂等 9 项），见 [deploy/README.md](deploy/README.md)。
脚本对模型、静态页做外挂挂载，改 HTML 不必重打 jar。

### 接口速览

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat/send` | POST | 流式对话（SSE），支持进度推送和 RAG 引用溯源 |
| `/chat/list` | GET | 查询用户会话列表 |
| `/chat/messages` | GET | 查询会话消息历史 |
| `/api/document/upload` | POST | 上传知识文档 |
| `/api/document/split/{id}` | POST | 手动触发文档切片 |
| `/api/document/embedding` | POST | 手动触发向量化 |

---

## 设计原则

- **事件驱动优于直接调用**：文档处理流程通过 Spring Event 解耦，Controller 瘦身，职责清晰；权限变更同样通过事件驱动批量更新 ES 索引；权限变更同样通过事件驱动批量更新 ES 索引
- **最终一致性**：事件驱动 + XXL-Job 补偿，“异步优先，定时兜底”
- **流式优先**：全链路 Reactor 响应式，SSE 逐 Token 推送，进度可见；多渠道输出（Web/钉钉）共享同一流式管道；多渠道输出（Web/钉钉）共享同一流式管道
- **声明式并发控制**：`@DistributeLock` 注解一行搞定幂等保护
- **本地推理优先**：Reranking 在 JVM 进程内完成，省去网络往返，降低延迟
- **虚拟线程优先**：所有 LLM 辅助调用（标题生成、查询改写回写）均使用虚拟线程，零阻塞主流程
- **容错优先**：LLM JSON 输出修复管道、缓存空值防击穿、标题生成失败保留临时标题、Text2Cypher 降级回退 ES，处处留有余地
- **分布式 ID 全链路**：雪花算法生成的 ChunkId 贯穿切片 → 存储 → 检索 → 扩展，全局唯一可追溯；版本链路通过 `parentVersionDocId` 关联，时序可追溯
- **权限内建**：权限信息从文档写入切片 metadata → ES 索引 → 检索 Filter，全链路内建，变更实时生效
---


## 审核式问答缓存

在统一 `ChatApplicationService.chat` 入口增加语义问答缓存，网页和钉钉共用。流程为：

1. `qaCacheCurate` 直接读取最近 7 天的合格历史问答，先将问题文本和 ID 交给模型按语义归组，再由代码统计各组独立会话频次。达到默认 3 次的高频问题才交给模型整理答案，质量评分须 ≥ 0.9。已有差评的答案不计频次、不进入模型输入，候选保存前再次检查。分数只作初筛，准确性和公开适用性必须由人工核实。
2. 标准问答及原始问答、RAG 引用快照写入 MySQL `qa_cache_entry`，状态 `PENDING`。来源 ID 必须实际存在，频次由数据库按归组后的独立会话计数；同一问题存在待审、待发布或已发布条目时不重复提取，同一问题和来源集合使用指纹幂等去重。
3. 审核员在 `/qa-cache.html`（文档管理页有入口）查看来源、修改问答、填写审核意见及 1–90 天有效期，并确认可以公开复用。通过后先保存为 `APPROVED`，随即发布当前条目，成功后变为 `ACTIVE`；发布失败保留 `APPROVED` 供自动重试，页面显示实际结果。拒绝后为 `REJECTED`。
4. 审核接口立即将已批准问题 embedding 到独立 Elasticsearch 索引 `know-engine-qa-cache-v1`。向量内容只有问题、条目 ID；答案以 MySQL 为准。发布前后均检查来源版本，向量写入成功后才变为 `ACTIVE`，失败保留待发布状态并记录错误，后续 `qaCachePublish` 任务使用固定 ID 重试。
5. 用户问题先检索问题向量，默认最低相似度 0.95；数据库再次确认 `ACTIVE` 且未过期后直接返回答案、保存聊天记录并发出原有 `[DONE]` 事件。未命中或缓存查询异常时走原来的意图识别/RAG 流程。审核员可下线待发布或已发布条目，数据库状态立即阻止后续命中。

### 启用步骤

本功能默认关闭，不会自动修改数据库，也不会在应用启动时调用真实模型整理历史记录。

1. 所有新增表结构统一位于 `src/main/resources/sql/tables.sql` 的「问答缓存与统一评价」部分：`qa_cache_entry`、`qa_cache_lookup`。按该定义初始化相关表，并为 `chat_message` 增加 `helpful`、`feedback_comment`、`feedback_at` 三个评价字段，以及 `cache_hit` 缓存命中标记（默认 0，命中时为 1）。
2. 配置 `QA_CACHE_ENABLED=true` 和 `QA_CACHE_REVIEWER_IDS=staff_实际工号`，多个审核员用逗号分隔。审核员必须是已登录、在职且出现在此配置中的员工；未配置审核员时无人可访问来源数据或审批接口。
3. 在现有 XXL-Job 执行器添加两个 BEAN 任务：

   | JobHandler | 建议 Cron | 用途 |
   |---|---|---|
   | `qaCacheCurate` | `0 0 3 ? * MON` | 每周一凌晨 03:00 整理最近 7 天问答，生成待审候选 |
   | `qaCachePublish` | `0 */5 * * * ?` | 每 5 分钟执行准出、清理下线向量、补偿未发布成功的批准条目 |

   执行时间由 XXL-Job 控制台配置（按调度中心时区）；已有整理任务需将 Cron 改为上述每周配置。两个任务各配置一个任务记录，路由策略使用“第一个”等单机路由，阻塞策略使用“单机串行”，不使用分片广播。整理任务失败由 XXL-Job 报告；发布任务的逐条错误在审核页面可见。发布任务同时执行准出扫描，无需额外创建调度任务。
4. 登录员工账号，打开 `/qa-cache.html` 完成审核。通过后页面显示发布结果；未发布成功的条目可在「待发布」列表查看原因，系统自动重试。切换「已发布」可查看有效期、命中次数及执行下线。
5. 用独立的新会话询问批准的问题，确认直接返回批准答案，页面不显示缓存命中提示；通过审核页命中计数核验缓存生效；再验证拒绝、下线、过期问答不会命中。

`qa-cache` 只保留 5 个配置：`enabled`（开关）、`index-name`（索引名）、`min-score`（命中相似度）、`min-frequency`（高频会话数）和 `reviewer-ids`（审核员）。候选质量门槛固定为 0.9；每组默认最多向模型提供 100 个会话的证据，且不少于频次阈值。准出规则固定为下文表格中的数值，定义在对应 Service 的具名常量中。任务固定分页读取执行时刻前 7 天的合格历史，频次至少 2 个独立会话，不保存跨次处理进度。相似度是当前 embedding/Elasticsearch 的匹配分数，不是准确率，需要用业务问题集校准。更换 embedding 模型或维度时使用新索引并重新整理、审核发布，避免混用向量空间。

### 手动触发缓存任务

无需等待 XXL-Job，可使用已授权、在职的审核员账号调用以下同步接口（缓存须已启用）：

| 接口 | 行为 | 返回值 |
|---|---|---|
| `POST /api/qa-cache/curate` | 整理最近 7 天的高频问答，对应 `qaCacheCurate` | 本次新增待审核条目数 |
| `POST /api/qa-cache/maintain` | 执行准出、清理下线向量、补偿发布，对应 `qaCachePublish` | 执行完成后返回 HTTP 200 |

两个接口均不需要请求体，携带员工登录后的 `satoken` 请求头或 Cookie 即可。整理结果仍须人工审核，调用会等待方法执行完成；补偿发布中的逐条失败仍保留待重试状态，并可在审核页查看。手动执行时避免与对应的 XXL-Job 任务重叠运行，尤其是依赖串行执行的问答整理任务。

### 当前边界

- 首版只收集、命中会话第一问，避免将有上下文的追问当作独立问题。历史必须是相邻 USER/ASSISTANT 消息且回答有正文和 RAG 引用。未完成答案在历史消息中保留，完成后参与后续统计；失败、差评、删除消息及缓存命中不会作为提取证据。频次是窗口内合格问答的独立会话数。
- 缓存面向所有访客，只允许稳定的公开通用问答；个人订单、车辆状态、内部资料、实时营销/价格等不得批准。原始问题保留车型、年款和条件，人工审核承担最终质量判断。
- 下线/过期条目由数据库状态立即阻止返回，`qaCachePublish` 准出扫描后清理 ES 向量，失败保留待清理标记并重试。每轮最多清理 100 条，积压期间仍可能降低命中率，但不会返回已下线答案。
- 批准后不能直接编辑已发布答案。需要修改时下线旧条目，由后续整理生成新候选后重新审核。相同来源集合不会重复生成（包括已拒绝/下线条目）；新增来源会形成新的候选集合。
- 数据库审批使用带状态条件的原子更新，重复/并发处理返回 HTTP 409。发布过程不持有数据库事务跨调用 Elasticsearch；发布中被下线的条目不会被重新激活。

### 验证

使用 JDK 21：`mvn -Dtest='QaCache*Test,QaMiningTest' test`。测试使用模拟依赖和 H2，覆盖审核权限、公开确认、并发状态冲突、来源与频次验证、缓存门禁、故障回退、发布重试、聊天落库/SSE 结束事件，以及最近一周的时间范围、语义归组 ID 校验、高频筛选、差评过滤和失败重试。真实 MySQL、Elasticsearch、模型和 XXL-Job 的端到端联调需在配置好的环境执行。

### 自动准出规则

三类规则均已实现，阈值在 `qa-cache` 下配置：

| 规则 | 默认条件 | 执行时机 | 下线原因 |
|---|---|---|---|
| 低命中占比 | 发布满 7 天，最近 7 天有效缓存查询 ≥ 100，条目命中占比 < 1% | `qaCachePublish` 扫描 | `LOW_HIT_RATE` |
| 用户差评 | 至少 3 位不同用户差评，差评人数 / 总评价人数 ≥ 50% | `qaCachePublish` 定时扫描（默认每 5 分钟） | `NEGATIVE_FEEDBACK` |
| 知识变化 | 来源文档当前版本与回答来源版本不同、文档/版本已删除、版本不再有效，或访问权限不再是 VISITOR | 审批及发布前后、每次命中前检查，任务补偿 | `KNOWLEDGE_CHANGED` |

低命中占比的分母为同一观察窗口内**所有成功执行向量查询的缓存请求**，不是单条问题的候选曝光量。未命中计入分母，缓存关闭、上下文跳过及 ES/embedding 异常不计入；命中分子要求对应助手消息已写入非空正文。新发布条目需经过完整观察窗口，样本量不足不会淘汰。恰好 1% 不下线。

反馈通过 `POST /chat/message/{messageId}/feedback` 提交，例如 `{"helpful":false,"comment":"答案已过时"}`。所有已完成的助手回答均可评价，服务端核验消息类型、正文和会话归属。评价直接保存到 `chat_message.helpful`（NULL 未评价、1 有帮助、0 无帮助）、`feedback_comment` 和 `feedback_at`，同一回答仅保存首次评价，缓存关闭也可使用。反馈请求只更新消息评价字段，不锁定或修改缓存条目；差评准出统一由 `qaCachePublish` 定时处理。缓存关联只读取服务端消息元数据中的 `qaCacheEntryId`，不额外存储。缓存准出关联会话所属用户，按“条目 + 用户”取首次评价（`feedback_at`、消息数字 `id` 排序）；跨会话评价都保留，但不增加该用户的统计权重，已删除的消息和会话不计入统计。网页端所有已完成回答下均有「有帮助 / 没帮助」按钮，历史消息也可评价；钉钉目前没有内嵌评价按钮。缓存命中不显示专门提示，实时网页流统一通过内部 `[ANSWER_MESSAGE]` 事件关联评价对象，不作为正文显示。差评占比使用缓存条目整个发布周期的用户评价。

`qa_cache_entry.dependencies` 以 JSON 数组固定保存回答实际引用的文档及版本 ID，保存后不覆盖；NULL 表示尚未解析，无需额外的就绪标记。旧缓存通过原始引用的 `chunkId` 在分段表追溯版本，不能追溯全部来源时以 `SOURCE_UNVERIFIABLE` 下线，不能用当前版本补填冒充旧答案的来源。因旧来源格式不完整而下线的条目，需要基于新历史问答重新整理审核。版本切换、撤销公开权限、单个或批量删除文档、手动失效指定版本时，会通过同步事件在同一数据库事务中下线相关缓存，提交后立即停止使用，事务回滚则一并回滚缓存状态；ES 向量仍由定时任务清理。即使无人访问，发布任务也会补偿扫描失效缓存；两轮任务之间，命中前的来源校验仍会拦截旧答案。源码文档版本变化不会自动批准新答案。

自动下线保留原审批人和审核意见，另存 `disabled_at / disable_reason / disable_detail`，可在审核页面「已下线」列表查看。过期条目自动标记 `EXPIRED`，人工下线标记 `MANUAL`。下线不可由发布重试重新激活，ES 删除失败不会影响数据库门禁。

部署代码后保持原 `qaCachePublish` 定时任务即可启用准出，不需要新增任务。

测试包含内存 H2（MySQL 模式）的真实 MyBatis-Plus Mapper 校验，覆盖去重、归属、并发投票、观察窗口和版本失效；仍需在部署环境完成真实 MySQL / ES 联调。

### 表结构维护

缓存与评价只维护 `tables.sql` 中的最终表结构，不再提供独立的建表、迁移、清理或存储过程脚本。评价字段直接放在 `chat_message`，不再维护独立评价表。已有数据库的结构和数据调整需按实际现状处理；`CREATE TABLE IF NOT EXISTS` 不会自动修改旧表或迁移数据。

缓存与评价的数据访问统一采用 MyBatis-Plus Entity / Mapper / Service。普通查询、分页和条件更新优先使用 IService 的 getOne/count/page/update 及 Lambda Wrapper；仅幂等插入、原子自增、行锁和跨表聚合等特殊操作保留 Mapper SQL，事务由 Service 管理；业务代码不直接使用 JdbcTemplate。测试中的 JdbcTemplate 仅用于建表、样本准备和独立断言。

缓存条目字段检查：`sources` 保存原始问答及 RAG 引用，供人工审核；`dependencies` 保存实际文档版本，供失效校验，两者用途不同。`fingerprint` 用于来源幂等，`question_key` 用于问题精确去重；审核证据、审核记录、有效期、准出记录和向量重试状态均有业务用途，保留。累计 `hits` 用于页面展示，按窗口的命中率由查询记录统计。已删除独立的来源依赖表及冗余的 `dependencies_ready` 字段。

若已有旧版缓存表，升级前需把原依赖表中的文档及版本 ID 原样迁入条目的 `dependencies`，核对后再移除旧表和就绪标记；不要用知识库当前版本代替历史依赖。`tables.sql` 是表结构定义，不会自动迁移已有数据。

### 每周高频问答提取

- XXL-Job 的 `qaCacheCurate` 每周执行一次，固定查询 `[执行时刻 - 7 天, 执行时刻)` 内创建的助手回答及对应首轮问题；按回答创建时间划定范围。每次执行固定上下界，所有分页和入库复查使用同一范围。
- 直接查询 `chat_message`，排除 `helpful=0` 的回答，先由模型按语义归组，再由代码统计独立会话数，默认至少 3 次才整理答案。没有采样表、游标表或 Redis 提取进度；分页位置只在本次调用内使用。
- 归组模型仅接收问题 ID 和文本，只返回各组的问题 ID；同义词和语序不同可以归为同一组。提示词要求车型、版本、年款、数字、单位、适用对象及否定条件不同的问题分开，语义判断仍需人工审核把关。
- 代码验证所有输入 ID 恰好出现一次，拒绝虚构、重复或遗漏的 ID；频次按真实会话去重计算，不接受模型估算。归组和整理答案是两次独立调用，低频组不会进入答案整理。
- 归组一次提交最近一周的合格问题文本；目前不做跨批语义合并，问题量超过模型上下文时任务失败，不截断历史冒充完整统计。文本归一化仅用于现有精确去重键；候选保存前会用标准问题检索现有缓存索引，复用 `min-score` 阈值，若存在仍生效且未过期的相似条目则跳过入库。待审候选尚未写入向量库，因此不在该语义查重范围内。检索失败则任务失败，不跳过查重强行入库。
- 任一差评都会排除对应回答。保存前重新核对输入证据，期间新增差评、删除或改写时丢弃本次结果。
- 已有待审批、已审批或生效的问题跳过整理，来源指纹用于入库去重。失败后可以重新执行，仍查询重跑时刻前 7 天；不补查更早的历史，不记录空结果的处理状态。
- 任务由 XXL-Job 单机路由和单机串行策略控制执行，人工审批和缓存发布流程保持原有行为。表结构只维护在 `tables.sql`，不提供独立的过程或迁移 SQL。

### 缓存代码目录

`QaCacheService` 统一承载历史问答提取、缓存查询、发布和准出调度，提取步骤保留为内部方法。缓存模块按现有项目分层放置：`controller` 放审核接口，`service` 放缓存查询、提取及准出逻辑，`service/impl` 放 MyBatis-Plus 实现，`entity` 和 `mapper` 放持久化对象与查询，`config`、`job`、`util` 分别放配置、调度和工具。模型提示词位于 `resources/prompts/qa-cache-*-prompt.txt`。统一评价操作收在现有 `ChatMessageService` 和 `ChatMessageMapper` 中。

---

© 2026 章文杰 · 技术演示项目
