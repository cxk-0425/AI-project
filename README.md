# 🧠 营销智能助手 | Multi-Agent 企业知识库系统

> 基于 **Spring AI 1.0 + PGVector** 构建的企业级 Multi-Agent 营销智能系统，支持 **BERT 意图识别**、**双 RAG 路由**、**Planner/Supervisor/Skill Agent 链**、**MCP 工具调用**、混合检索（Hybrid Search）、向量缓存与流式上下文增强生成。

---

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         前端 UI (index.html)                             │
│          HTML/CSS/JS + SSE 流式输出 + 意图标签 + Agent 步骤面板           │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────────────┐
│                         Spring Boot API (Port 8080)                      │
│                     ChatController（含意图路由）                          │
└────────┬──────────────────────┬──────────────────────────────────────────┘
         │                      │
         │  BERT/LLM 意图识别   │
         │  IntentClassifierService
         │         │            │
         ▼         ▼            ▼
    ┌────────────────┐    ┌──────────────────────────────────────────┐
    │   QUERY 查询类  │    │             CONFIG 配置类                │
    │                │    │                                          │
    │ MarketingDoc   │    │  SopRagService → PlannerAgent            │
    │ RagService     │    │              → SupervisorAgent           │
    │ (docVectorStore│    │              → SkillAgent + MCP Tools    │
    │  + HybridSearch│    │              → MarketingApiClient        │
    │  + 向量缓存)   │    │                                          │
    └──────┬─────────┘    └──────────────────────────────────────────┘
           │
    ┌──────▼───────────────────────────────────────────────────────────┐
    │                    ChatService (RAG + SSE)                        │
    │         CompletableFuture 并发执行 + ChatClient.stream()          │
    └──────────────────────────────────────────────────────────────────┘

数据层：
  ┌─────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
  │  kb_document    │  │  vector_store (PG)   │  │  sop_vector_store   │
  │  (元数据表)     │  │  内部文档向量库 HNSW  │  │  SOP文档向量库 HNSW  │
  └─────────────────┘  └──────────────────────┘  └──────────────────────┘
```

### 核心特性

| 特性 | 说明 |
|------|------|
| **BERT 意图识别** | 双层识别：BERT REST API（主路径）+ LLM 降级（兜底），自动路由到 QUERY/CONFIG 处理链 |
| **双 RAG 路由** | 查询类走内部文档库（docVectorStore），配置类走 SOP 文档库（sopVectorStore） |
| **Multi-Agent 链** | Planner（步骤拆分）→ Supervisor（完整性校验）→ Skill（MCP Tool Calling） |
| **MCP 工具调用** | Spring AI @Tool 注解封装 7 个营销 API，LLM 自主参数绑定并执行 |
| **混合检索 (Hybrid Search)** | 向量语义检索 + BM25 关键词检索 + RRF 融合排序 |
| **向量缓存** | 基于余弦相似度的语义缓存，支持 LRU + TTL 淘汰策略 |
| **并发检索** | 上下文检索与来源查询 CompletableFuture 并行执行，降低延迟 |
| **流式输出 (SSE)** | Token 级实时推送，支持意图标签、Agent 步骤进度实时展示 |
| **多格式文档** | PDF、Word、TXT、Markdown 文档解析 + TokenTextSplitter 智能分块 |

---

## 📁 项目结构

```
knowledge-base/
├── pom.xml                              # Maven 依赖（Spring Boot 3.2 / Spring AI 1.0）
├── docker-compose.yml                   # PostgreSQL + PGVector
├── src/main/
│   ├── java/com/kb/
│   │   ├── KnowledgeBaseApp.java        # 应用启动入口
│   │   ├── config/
│   │   │   ├── VectorStoreConfig.java   # 双 PGVector Bean（docVectorStore + sopVectorStore）
│   │   │   ├── EmbeddingConfig.java     # Embedding 模型配置
│   │   │   ├── ChatModelConfig.java     # ChatClient 配置
│   │   │   └── AsyncConfig.java        # RAG 并发执行线程池
│   │   ├── controller/
│   │   │   ├── DocumentController.java # 文档管理 API（含 SOP 上传 /upload/sop）
│   │   │   ├── ChatController.java      # 问答 API（意图路由 + SSE 流式）
│   │   │   └── CacheController.java     # 缓存管理 API
│   │   ├── intent/                      # ★ 意图识别模块
│   │   │   ├── IntentType.java          # 意图枚举：QUERY / CONFIG / UNKNOWN
│   │   │   ├── IntentSource.java        # 来源枚举：BERT / LLM / FALLBACK
│   │   │   ├── IntentResult.java        # 意图结果模型
│   │   │   ├── BertIntentClassifier.java# BERT REST 调用（主路径）
│   │   │   ├── LlmIntentClassifier.java # LLM 结构化分类（降级路径）
│   │   │   └── IntentClassifierService.java # 统一入口：BERT → LLM → 默认 QUERY
│   │   ├── agent/                       # ★ Multi-Agent 模块
│   │   │   ├── PlanStep.java            # 步骤数据模型
│   │   │   ├── ValidationResult.java    # Supervisor 校验结果
│   │   │   ├── PlannerAgent.java        # Planner：步骤拆分 + replan
│   │   │   ├── SupervisorAgent.java     # Supervisor：步骤完整性校验
│   │   │   ├── SkillAgent.java          # Skill：MCP Tool Calling 执行
│   │   │   └── ConfigAgentOrchestrator.java # 编排：SOP→Planner→Supervisor→Skill
│   │   ├── mcp/                         # ★ MCP 工具模块
│   │   │   ├── MarketingMcpTools.java   # @Tool 注解工具集（7 个营销 API）
│   │   │   └── MarketingApiClient.java  # RestClient 封装营销平台 HTTP 接口
│   │   ├── service/
│   │   │   ├── DocumentService.java     # 文档解析 + 分块 + 向量化入库（含 SOP 入库）
│   │   │   ├── MarketingDocRagService.java # 内部文档 RAG（QUERY 路径）
│   │   │   ├── SopRagService.java       # SOP 文档 RAG（CONFIG 路径）
│   │   │   ├── RagService.java         # 原 RAG 服务（兼容保留）
│   │   │   ├── ChatService.java         # 流式问答 + CompletableFuture 并发执行
│   │   │   ├── VectorCacheService.java  # 向量语义缓存（LRU + TTL + ReadWriteLock）
│   │   │   └── HybridSearchService.java # 混合检索（向量 + BM25 + RRF）
│   │   ├── model/
│   │   │   ├── KbDocument.java         # 文档元数据实体
│   │   │   ├── CachedQuestion.java     # 缓存条目实体
│   │   │   ├── CachedResult.java       # 缓存命中结果 DTO
│   │   │   └── ChatRequest.java        # 问答请求 DTO
│   │   ├── parser/
│   │   │   ├── DocumentParser.java      # 解析器接口
│   │   │   ├── PdfParser.java           # PDF 解析（PDFBox）
│   │   │   ├── WordParser.java          # Word 解析（Apache POI）
│   │   │   ├── TextParser.java          # TXT/MD 解析
│   │   │   └── ParserFactory.java       # 解析器工厂
│   │   └── repository/
│   │       └── DocumentMetaRepository.java
│   └── resources/
│       ├── application.yml              # 主配置（含 BERT/Agent/MCP/Hybrid/Cache 配置）
│       ├── schema.sql                   # 数据库初始化脚本（含 mcp_call_log 表）
│       └── static/
│           └── index.html               # 前端 UI（意图标签 + Agent 步骤面板 + 双文档上传）
└── README.md
```

---

## 🚀 快速启动

### 1. 前置依赖

- Java 17+
- Maven 3.9+
- Docker + Docker Compose
- OpenAI API Key（或兼容 API）

### 2. 启动 PGVector 数据库

```bash
docker-compose up -d
docker ps   # 确认 kb-pgvector 状态为 healthy
```

### 3. 配置环境变量

```bash
# 必须
export OPENAI_API_KEY=sk-your-key-here

# 可选：自定义 API 代理
export OPENAI_BASE_URL=https://api.openai.com

# 可选：启用 BERT 意图识别服务（默认关闭，走 LLM 降级）
export BERT_ENABLED=false
export BERT_SERVICE_URL=http://localhost:8000

# 可选：营销 API 配置（MCP 工具调用目标）
export MARKETING_API_BASE_URL=http://localhost:9090
export MARKETING_API_TOKEN=your-token
```

**或修改 `application.yml`：**
```yaml
spring:
  ai:
    openai:
      api-key: sk-your-key-here
      base-url: https://api.openai.com
```

### 4. 构建并运行

```bash
mvn spring-boot:run
```

启动成功后访问：**http://localhost:8080**

---

## 📡 API 接口说明

### 文档管理

| Method | URL | 说明 |
|--------|-----|------|
| POST | `/api/documents/upload` | 上传**内部文档**（供 QUERY 查询路径使用） |
| POST | `/api/documents/upload/sop` | 上传 **SOP/QA 文档**（供 CONFIG Agent 链使用） |
| GET | `/api/documents` | 获取文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档（同时清理向量缓存）|

### 问答接口

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/chat/stream?q=问题` | **流式问答**（SSE，含意图路由，推荐） |
| POST | `/api/chat/sync` | 同步问答，Body: `{"question":"..."}` |
| POST | `/api/chat/retrieve` | 仅检索不调用 LLM，用于调试 |

### 缓存管理

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/cache/stats` | 获取缓存统计（命中率、大小等）|
| POST | `/api/cache/clear` | 清空所有缓存 |
| POST | `/api/cache/evict-expired` | 清理过期缓存条目 |

### SSE 事件类型

**公共事件（所有路径）：**
```
event: intent      → 意图识别结果 {"type":"QUERY|CONFIG","confidence":0.95,"source":"BERT|LLM|FALLBACK"}
event: error       → 错误信息
```

**QUERY 路径（查询类）：**
```
event: token   → 逐 token 文本输出（实时显示）
event: sources → 引用的来源文档名称（逗号分隔）
event: done    → [END] 流结束标志
```

**CONFIG 路径（配置类 Agent 链）：**
```
event: sop-sources   → SOP 检索来源文档列表（JSON 数组）
event: plan          → 执行计划步骤（JSON 数组，含 stepId/action/description）
event: agent-status  → Agent 各阶段进度 {"stage":"planner|supervisor|executing","message":"..."}
event: step-start    → 步骤开始 {"stepId":1,"action":"createActivity","status":"running"}
event: step-result   → 步骤结果 {"stepId":1,"result":"活动创建成功","success":true}
event: agent-done    → Agent 链全部完成 {"message":"所有步骤执行完成","totalSteps":3}
```

---

## ⚙️ 配置参数

### RAG 检索配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.rag.top-k` | 5 | 检索返回最相关片段数 |
| `kb.rag.similarity-threshold` | 0.65 | 最低相似度阈值 |
| `kb.rag.use-hybrid-search` | true | 启用 Hybrid Search 混合检索 |

### Hybrid Search 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.hybrid.enabled` | true | 是否启用混合检索 |
| `kb.hybrid.vector-weight` | 0.5 | 向量检索权重（0.0-1.0）|
| `kb.hybrid.keyword-weight` | 0.5 | BM25 关键词检索权重（0.0-1.0）|
| `kb.hybrid.rrf-k` | 60 | RRF 融合参数 |
| `kb.hybrid.vector-top-k` | 20 | 向量检索候选数量 |
| `kb.hybrid.keyword-top-k` | 20 | BM25 检索候选数量 |

### 向量缓存配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.cache.max-size` | 500 | 最大缓存问题数量 |
| `kb.cache.similarity-threshold` | 0.95 | 相似度阈值（> 0.95 视为相同问题）|
| `kb.cache.ttl-minutes` | 30 | 缓存过期时间（分钟）|

### BERT 意图识别配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `bert.enabled` | false | 是否启用 BERT 主路径（false = 直接走 LLM 降级）|
| `bert.service.url` | http://localhost:8000 | BERT FastAPI 服务地址 |
| `bert.confidence-threshold` | 0.82 | BERT 置信度阈值（低于此值触发 LLM 降级）|
| `intent.fallback-threshold` | 0.65 | LLM 分类置信度最低阈值（低于此值默认 QUERY）|

### Agent 链配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.planner.max-steps` | 10 | Planner 最多拆分步骤数 |
| `agent.supervisor.max-replan-count` | 2 | Supervisor 触发重规划的最大次数 |

### 营销 API 配置（MCP 工具）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `marketing.api.base-url` | http://localhost:9090 | 营销平台 API Base URL |
| `marketing.api.token` | 空 | 鉴权 Token（Bearer）|

### 并发线程池配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.async.core-pool-size` | 4 | 核心线程数（建议 CPU 核心数）|
| `kb.async.max-pool-size` | 16 | 最大线程数（I/O 密集型）|
| `kb.async.queue-capacity` | 100 | 队列容量 |
| `kb.async.keep-alive-seconds` | 60 | 线程空闲时间 |

### LLM / Embedding 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.openai.chat.options.model` | gpt-4o-mini | LLM 模型 |
| `spring.ai.openai.embedding.options.model` | text-embedding-3-small | Embedding 模型（1536 维）|
| `spring.ai.vectorstore.pgvector.dimensions` | 1536 | 向量维度 |

### 文档处理配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.document.chunk-size` | 800 | 每块最大 Token 数 |
| `kb.document.chunk-overlap` | 200 | 相邻块重叠 Token 数 |
| `kb.document.max-file-size` | 50MB | 最大文件大小 |
| `kb.document.allowed-types` | pdf,docx,doc,txt,md | 支持的文件类型 |

---

## 🔑 环境变量

| 变量 | 说明 |
|------|------|
| `OPENAI_API_KEY` | OpenAI API Key（必须）|
| `OPENAI_BASE_URL` | API Base URL（使用自定义代理时）|
| `BERT_ENABLED` | 是否启用 BERT 服务（默认 false）|
| `BERT_SERVICE_URL` | BERT FastAPI 推理服务地址 |
| `MARKETING_API_BASE_URL` | 营销平台 API Base URL |
| `MARKETING_API_TOKEN` | 营销平台鉴权 Token |
| `KB_UPLOAD_DIR` | 上传文件临时目录（默认 `./uploads`）|

---

## 💡 核心流程说明

### 意图识别与路由流程

```
用户 Query
    │
    ▼
IntentClassifierService
    │
    ├──▶ Step 1: BERT 分类（若 bert.enabled=true）
    │       POST http://bert-service/classify
    │       → label: QUERY/CONFIG, confidence: 0.92
    │       → 置信度 >= 0.82? 直接返回
    │
    ├──▶ Step 2: LLM 降级分类（BERT 不可用或置信度不足）
    │       ChatClient.entity(IntentClassification.class)
    │       → Few-Shot Prompt + temperature=0
    │       → 置信度 >= 0.65? 返回 LLM 结果
    │
    └──▶ Step 3: 默认降级（保守策略）→ QUERY
    │
    ▼
意图路由
    │
    ├── QUERY ──▶ MarketingDocRagService + ChatService（RAG 问答）
    │
    └── CONFIG ──▶ ConfigAgentOrchestrator（Agent 链）
```

### CONFIG 路径：Agent 链执行流程

```
用户配置类请求（如"创建双十一满减活动"）
    │
    ▼
Step 1: SOP RAG 检索
    SopRagService.retrieveAndBuildSopContext()
    → 从 sop_vector_store 检索相关操作流程
    → SSE: event: sop-sources
    │
    ▼
Step 2: Planner Agent
    PlannerAgent.plan(userQuery, sopContext)
    → LLM 根据用户需求 + SOP 上下文生成步骤列表
    → 步骤示例：createActivity → createCoupon → configureActivityRules
    → SSE: event: plan
    │
    ▼
Step 3: Supervisor Agent（步骤校验 + 最多 2 次重规划）
    SupervisorAgent.validate(originalQuery, steps)
    → LLM 审核步骤完整性和依赖关系
    → 发现缺失 → PlannerAgent.replan() → 重新校验
    → SSE: event: agent-status
    │
    ▼
Step 4: Skill Agent（MCP Tool Calling）
    SkillAgent.execute(steps, emitter)
    → 对每个步骤，ChatClient + @Tool 工具调用
    → LLM 自主选择工具并绑定参数
    → MarketingApiClient 执行实际 HTTP 请求
    → SSE: event: step-start / step-result / agent-done
```

### MCP 工具列表

| 工具名 | 对应 API | 说明 |
|--------|----------|------|
| `createActivity` | POST /api/v1/activities | 创建营销活动 |
| `listActivities` | GET /api/v1/activities | 查询活动列表 |
| `getActivityDetail` | GET /api/v1/activities/{id} | 查询活动详情 |
| `updateActivity` | PUT /api/v1/activities/{id} | 更新活动信息 |
| `toggleActivityStatus` | PATCH /api/v1/activities/{id}/status | 启用/禁用活动 |
| `createCoupon` | POST /api/v1/coupons | 创建优惠券 |
| `configureActivityRules` | POST /api/v1/activities/{id}/rules | 配置活动规则 |

### QUERY 路径：问答并发执行流程

```
用户查询类提问
    │
    ▼
┌────────────────────────────────────────────────────┐
│     CompletableFuture 并行执行两个任务              │
│                                                    │
│   contextFuture = supplyAsync(                     │
│       MarketingDocRagService.retrieveAndBuildContext│
│       , ragExecutor)  ─────┐                       │
│                             │ 并行                  │
│   sourcesFuture = supplyAsync(                     │
│       MarketingDocRagService.retrieveSourceDocs()   │
│       , ragExecutor)  ─────┘                       │
│   allOf(...).join()                                │
└────────────────────────────────────────────────────┘
    │
    ▼
构建 SystemPrompt（填充 {context}）
    │
    ▼
ChatClient.stream() → SSE 流式推送
 - token: 逐 token 输出
 - sources: 来源文档
 - done: 流结束
```

### 文档入库流程

```
上传文件（/api/documents/upload 或 /upload/sop）
    │
    ▼
SHA-256 去重检查
    │
    ▼
Parser 解析纯文本（PDFBox / POI / TXT）
    │
    ▼
TokenTextSplitter 智能切块（800 tokens，200 overlap）
    │
    ▼
附加 Metadata（source/filename/file_type/chunk_index）
    │
    ▼
EmbeddingModel.embed() → 1536 维向量
    │
    ├── 内部文档 → docVectorStore（vector_store 表）
    └── SOP 文档 → sopVectorStore（sop_vector_store 表）
    │
    ▼
更新文档状态 DONE + 清空向量缓存
```

### 向量缓存命中流程

```
用户提问
    │
    ▼
VectorCacheService.findSimilar(question)
 - EmbeddingModel.embed(question)
 - ReadLock：遍历缓存计算余弦相似度
 - similarity > 0.95? → 命中
    │
    ├──▶ 命中 → WriteLock：LRU 移到队头
    │   直接返回缓存上下文（跳过向量检索）
    │
    └──▶ 未命中 → 执行 Hybrid Search
        构建上下文 → 写入缓存（LRU + TTL）
```

---

## 🗄️ 数据库设计

| 表名 | 说明 |
|------|------|
| `kb_document` | 文档元数据（filename/hash/status/chunk_count）|
| `vector_store` | 内部文档向量表（Spring AI 自动创建，HNSW 索引）|
| `sop_vector_store` | SOP 文档向量表（Spring AI 自动创建，HNSW 索引）|
| `mcp_call_log` | MCP 工具调用审计日志（query/intent/steps/result）|

---

## 🛠️ 切换其他 LLM 提供商

若需使用 Ollama、Azure OpenAI 等，替换 Starter 依赖：

```xml
<!-- 使用 Ollama（本地模型）-->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
</dependency>

<!-- 使用 Azure OpenAI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-azure-openai-spring-boot-starter</artifactId>
</dependency>
```

并在 `application.yml` 中配置对应模型名称和端点。

---

## 📊 性能优化说明

### 并发检索优化

使用 `CompletableFuture.supplyAsync()` + 自定义线程池实现真正的并行执行：

- **优化前**：串行执行，耗时 = 上下文检索(200ms) + 来源查询(150ms) = 350ms
- **优化后**：并行执行，耗时 = max(上下文检索, 来源查询) = 200ms（**减少 43%**）

### 向量缓存优化

基于余弦相似度的语义缓存，相似问题（相似度 > 0.95）直接返回缓存：

- **优化前**：每次检索都执行向量计算和数据库查询
- **优化后**：命中缓存时跳过检索步骤，**减少 60-80% 检索耗时**

### 读写锁优化

`VectorCacheService` 使用 `ReentrantReadWriteLock` 两阶段读写分离：

- **读锁**：多线程并发遍历缓存查找相似问题
- **写锁**：LRU 更新和缓存写入时独占，保证数据一致性
- **死锁规避**：读锁内不直接升级为写锁，命中后先释放读锁再获取写锁执行 LRU 更新

### BERT + LLM 双层意图识别

- **主路径**：BERT 模型推理通常 < 50ms，且无需消耗 LLM Token
- **降级路径**：LLM 分类使用 `temperature=0` + Few-Shot 提示，准确率高但延迟约 200-500ms
- **保守策略**：置信度不足时默认 QUERY，避免误触发写操作

---

## 📝 许可证

MIT License
