# 营销智能助手 | Multi-Agent 企业知识库系统

> 基于 **Spring AI 1.0 + PGVector** 构建的企业级 Multi-Agent 营销智能系统，支持 **BERT/LLM 双层意图识别**、**双路由 RAG**、**Planner→Supervisor→Skill Agent 链**、**MCP 工具自动调用**、**向量+BM25混合检索**、**三种文档录入方式**、**Redis 分布式 Token 缓存**、**SSE 流式输出**，以及配套的**全栈 AI 评测体系**。

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    前端 UI (index.html)                          │
│    SSE 流式输出 · 意图标签 · Agent 步骤面板 · 三种文档录入        │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP / SSE
┌────────────────────────────▼────────────────────────────────────┐
│              ChatController  (Port 8080 · VirtualThread)        │
│                  意图识别 + 路由分发 + SSE 推送                  │
└──────┬──────────────────────────────────────────┬───────────────┘
       │ QUERY 路径                                │ CONFIG 路径
       ▼                                           ▼
┌─────────────────────┐              ┌─────────────────────────────┐
│  IntentClassifier   │              │   ConfigAgentOrchestrator   │
│  BERT (主) →        │              │                             │
│  LLM (降级) →       │              │  Step1: SopRagService       │
│  默认QUERY (兜底)   │              │  Step2: PlannerAgent        │
└──────┬──────────────┘              │  Step3: SupervisorAgent     │
       │                             │  Step4: SkillAgent          │
       ▼                             │       └─ MCP Tool Calling   │
┌─────────────────────┐              └──────────────┬──────────────┘
│  ChatService        │                             │
│  · 并发 RAG 检索    │              ┌──────────────▼──────────────┐
│  · SystemPrompt     │              │   MarketingApiClient        │
│  · stream() SSE     │              │   RestClient + Token 刷新   │
└─────────────────────┘              └─────────────────────────────┘

                   ┌──────────────────────┐
                   │  BERT 意图识别服务    │
                   │  (bert-service/)     │
                   │  Python + FastAPI    │
                   │  bert-base-chinese   │
                   │  fine-tune 三分类    │
                   └──────────────────────┘

数据层：
  ┌──────────────────┐  ┌─────────────────────┐  ┌──────────────────────┐
  │  kb_document     │  │  vector_store (PG)  │  │  sop_vector_store    │
  │  文档元数据      │  │  内部文档 HNSW索引   │  │  SOP文档 HNSW索引    │
  └──────────────────┘  └─────────────────────┘  └──────────────────────┘
  ┌──────────────────┐  ┌─────────────────────┐
  │  mcp_call_log    │  │  Redis              │
  │  操作审计日志    │  │  Token缓存 + 分布式锁│
  └──────────────────┘  └─────────────────────┘
```

### 核心特性一览

| 特性 | 实现方案 |
|------|---------|
| **BERT/LLM 双层意图识别** | BERT REST API（主路径，<50ms）→ LLM Few-Shot（降级）→ 默认 QUERY（兜底），置信度阈值可配 |
| **双路由 RAG** | QUERY → 内部文档库（docVectorStore）；CONFIG → SOP 文档库（sopVectorStore） |
| **Multi-Agent 链** | Planner（步骤拆分）→ Supervisor（完整性校验 + 最多2次重规划）→ Skill（MCP Tool Calling）|
| **MCP 工具调用** | Spring AI `@Tool` 注解封装 7 个营销 API，LLM 自主参数绑定执行 |
| **混合检索（Hybrid Search）** | 向量语义检索 + PostgreSQL BM25 全文检索 + RRF 融合排序 |
| **向量语义缓存** | 余弦相似度匹配（阈值 0.95），LRU + TTL 双淘汰，`ReentrantReadWriteLock` 线程安全 |
| **分布式 Token 刷新** | Redis 缓存 + Redisson 分布式锁 + Double-Check + 提前刷新窗口 + 本地降级 |
| **并发 RAG 检索** | `CompletableFuture.supplyAsync()` 并发执行上下文检索与来源查询，延迟降低 43% |
| **SSE 流式输出** | Token 级实时推送（VirtualThread），支持意图标签、Agent 步骤进度实时展示 |
| **三种文档录入** | 文件上传（PDF/DOCX/TXT/MD）、URL 抓取（Jsoup 正文提取）、RPC 推送（外部系统集成）|
| **多格式文档解析** | Apache Tika（通用）+ PDFBox（PDF）+ Apache POI（Word），SHA-256 去重，TokenTextSplitter 智能分块 |
| **全栈 AI 评测体系** | 6 个维度评测类（意图/RAG/向量缓存/Planner/Supervisor/Agent E2E）+ Maven Tag 分层运行 |

---

## 项目结构

```
knowledge-base/
├── pom.xml                              # Maven（Spring Boot 3.2 / Spring AI 1.0）
├── docker-compose.yml                   # PostgreSQL+PGVector + Redis
├── bert-service/                        # BERT 意图识别微服务（Python）
│   ├── train.py                         # bert-base-chinese fine-tune 训练脚本
│   ├── server.py                        # FastAPI 推理服务（/classify + /classify/batch）
│   ├── requirements.txt                 # Python 依赖（transformers/FastAPI/uvicorn）
│   ├── data/intent_dataset.json         # 训练数据（从 src/test/resources/eval/ 复制）
│   └── model/bert-intent/               # fine-tune 后生成，server.py 从此加载
└── src/
    ├── main/java/com/kb/
    │   ├── KnowledgeBaseApp.java
    │   ├── config/
    │   │   ├── VectorStoreConfig.java   # 双 PGVector Bean（docVectorStore + sopVectorStore）
    │   │   ├── EmbeddingConfig.java     # Embedding 模型配置
    │   │   ├── ChatModelConfig.java     # ChatClient Bean
    │   │   ├── RedisConfig.java         # Redis Template + Redisson 分布式锁配置
    │   │   └── AsyncConfig.java         # ragExecutor 线程池（并发检索）
    │   ├── controller/
    │   │   ├── ChatController.java      # 问答 API（意图路由 + SSE，VirtualThread 执行）
    │   │   ├── DocumentController.java  # 文档管理 API（文件上传 + URL录入 + RPC录入）
    │   │   └── CacheController.java     # 缓存管理 API（stats/clear/evict-expired）
    │   ├── intent/                      # 意图识别模块
    │   │   ├── IntentClassifierService  # 统一入口：BERT → LLM → QUERY 保守降级
    │   │   ├── BertIntentClassifier     # BERT FastAPI REST 调用（主路径）
    │   │   ├── LlmIntentClassifier      # LLM Few-Shot 结构化分类（降级）
    │   │   ├── IntentResult             # 分类结果：type / confidence / source
    │   │   ├── IntentType               # 枚举：QUERY / CONFIG / UNKNOWN
    │   │   └── IntentSource             # 枚举：BERT / LLM / FALLBACK
    │   ├── agent/                       # Multi-Agent 模块
    │   │   ├── ConfigAgentOrchestrator  # 编排：SOP→Planner→Supervisor→Skill
    │   │   ├── PlannerAgent             # 步骤拆分 + replan（JSON 输出）
    │   │   ├── SupervisorAgent          # 步骤完整性校验（触发 replan）
    │   │   ├── SkillAgent               # MCP Tool Calling 逐步执行 + SSE 推送
    │   │   ├── PlanStep                 # 步骤模型：stepId/action/description/params/dependsOn
    │   │   └── ValidationResult        # Supervisor 校验结果：complete/missingSteps
    │   ├── mcp/                         # MCP 工具模块
    │   │   ├── MarketingMcpTools        # @Tool 注解工具集（7 个营销 API）
    │   │   └── MarketingApiClient       # RestClient 封装 HTTP 接口（含自动 Token 注入）
    │   ├── service/
    │   │   ├── ChatService              # 流式问答（并发 RAG + stream() SSE）
    │   │   ├── RagService               # 原始 RAG（兼容保留）
    │   │   ├── MarketingDocRagService   # 内部文档 RAG（QUERY 路径，含向量缓存）
    │   │   ├── SopRagService            # SOP 文档 RAG（CONFIG 路径）
    │   │   ├── DocumentService          # 文档解析 + 分块 + 向量化入库（三种录入方式）
    │   │   ├── HybridSearchService      # 混合检索：向量 + BM25 + RRF 融合
    │   │   └── VectorCacheService       # 语义缓存：LRU+TTL + ReadWriteLock
    │   ├── token/
    │   │   ├── TokenRefreshService      # 分布式 Token 管理（Redis+Redisson双重锁）
    │   │   ├── MiddlePlatformTokenClient# 中台 OAuth /oauth/token 调用
    │   │   ├── TokenInfo                # Token record：token / expiresAt / needsRefresh()
    │   │   └── TokenRefreshException    # Token 获取失败异常
    │   ├── model/
    │   │   ├── KbDocument               # 文档元数据实体（JPA，含 sourceType/sourceUrl/sourceSystem）
    │   │   ├── UrlIngestRequest         # URL 录入请求 DTO
    │   │   ├── RpcIngestRequest         # RPC 录入请求 DTO
    │   │   ├── CachedQuestion           # 缓存条目（向量 + LRU 元数据）
    │   │   ├── CachedResult             # 缓存命中结果 DTO
    │   │   └── ChatRequest              # 问答请求 DTO
    │   ├── parser/
    │   │   ├── DocumentParser           # 解析器接口
    │   │   ├── PdfParser                # PDF（PDFBox）
    │   │   ├── WordParser               # Word .docx（Apache POI）
    │   │   ├── TextParser               # TXT / Markdown
    │   │   ├── ParserFactory            # 按文件扩展名选择解析器
    │   │   └── UrlFetcher               # URL 抓取（JDK HttpClient + Jsoup，防 SSRF）
    │   └── repository/
    │       └── DocumentMetaRepository   # JPA Repository（kb_document 表）
    ├── main/resources/
    │   ├── application.yml              # 全量配置（BERT/Agent/MCP/Hybrid/Cache/Token/URL录入）
    │   ├── schema.sql                   # 初始化脚本（kb_document / mcp_call_log 表 + 索引）
    │   └── static/index.html            # 前端 UI（意图标签 + Agent 步骤面板 + 三种文档录入）
    └── test/
        ├── java/com/kb/
        │   ├── eval/                    # AI 评测测试（@Tag("eval")）
        │   │   ├── IntentEvaluationTest         # 意图识别评测（Accuracy/Macro-F1/ECE）
        │   │   ├── RagEvaluationTest            # RAG 检索质量评测（Recall@K/NDCG/MRR）
        │   │   ├── VectorCacheEvaluationTest    # 向量缓存误命中率评测
        │   │   ├── PlannerEvaluationTest        # Planner 步骤生成质量评测
        │   │   ├── SupervisorEvaluationTest     # Supervisor 步骤校验准确性评测
        │   │   ├── AgentE2EEvaluationTest       # Agent 端到端评测（SSE完整性/执行成功率）
        │   │   └── util/
        │   │       ├── EvalMetricsCalculator    # 通用指标计算（Accuracy/F1/NDCG/MRR/ECE）
        │   │       ├── EvalReportPrinter        # 控制台评测报告格式化输出
        │   │       ├── EvalDatasetLoader        # JSON 数据集加载工具
        │   │       └── IntentTestCase           # 意图评测用例结构体
        │   └── unit/                    # 单元测试（@Tag("unit")）
        │       ├── HybridSearchServiceTest
        │       ├── IntentClassifierServiceTest
        │       ├── PlannerAgentTest
        │       ├── SupervisorAgentTest
        │       └── VectorCacheServiceTest
        └── resources/eval/              # 评测数据集
            ├── intent_dataset.json      # 意图识别标注数据（51 条，QUERY/CONFIG/UNKNOWN）
            ├── rag_dataset.json         # RAG 检索评测数据（问题 + 相关文档 ID）
            ├── cache_eval_pairs.json    # 向量缓存正负样本对（合法命中 + 误命中场景）
            ├── planner_dataset.json     # Planner 评测数据（query + 期望步骤 + 期望 action）
            └── supervisor_dataset.json  # Supervisor 评测数据（完整/不完整计划样本）
```

---

## 快速启动

### 前置依赖

- Java 17+
- Maven 3.9+
- Docker + Docker Compose
- OpenAI API Key（或兼容接口）
- Python 3.9+（仅使用 BERT 主路径时需要）

### 1. 启动基础服务

```bash
# 启动 PostgreSQL+PGVector + Redis
docker-compose up -d

# 确认服务就绪
docker ps   # kb-pgvector 和 kb-redis 均应为 healthy
```

### 2. 配置环境变量

```bash
# 必填：OpenAI API Key
export OPENAI_API_KEY=sk-your-key-here

# 可选：自定义 API 代理地址（默认 https://api.openai.com）
export OPENAI_BASE_URL=https://api.openai.com

# 可选：BERT 意图识别服务（默认关闭，直接走 LLM 降级）
export BERT_ENABLED=false
export BERT_SERVICE_URL=http://localhost:8000

# 可选：营销平台 API（MCP 工具调用目标）
export MARKETING_API_BASE_URL=http://localhost:9090

# 可选：中台 OAuth 配置（动态 Token 刷新）
export MIDDLE_PLATFORM_URL=http://localhost:8888
export MIDDLE_PLATFORM_APP_ID=your-app-id
export MIDDLE_PLATFORM_APP_SECRET=your-app-secret

# 可选：Redis 连接配置
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=
```

> 也可直接修改 `src/main/resources/application.yml` 中的对应配置项。

### 3. 构建并运行

```bash
mvn spring-boot:run
```

启动成功后访问：**http://localhost:8080**

### 4. 启动 BERT 意图识别服务（可选）

> 跳过此步骤系统仍可正常工作，意图识别自动降级到 LLM Few-Shot 模式。

```bash
cd bert-service

# 安装 Python 依赖
pip install -r requirements.txt

# 复制训练数据并执行 fine-tune（约 5~10 分钟，GPU 约 1~2 分钟）
cp ../src/test/resources/eval/intent_dataset.json data/
python train.py

# 启动推理服务
uvicorn server:app --host 0.0.0.0 --port 8000

# 在 Java 侧开启 BERT 路径
export BERT_ENABLED=true
```

详细说明见 [bert-service/README.md](bert-service/README.md)。

---

## API 接口说明

### 问答接口

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/chat/stream?q={问题}` | **流式问答**（SSE，含意图路由，推荐使用）|
| POST | `/api/chat/sync` | 同步问答，Body: `{"question":"..."}` |
| POST | `/api/chat/retrieve` | 仅检索不生成回答（调试用）|

### 文档管理

| Method | URL | 说明 |
|--------|-----|------|
| POST | `/api/documents/upload` | 上传**内部文档**文件（QUERY 路径检索源，支持 PDF/DOCX/TXT/MD）|
| POST | `/api/documents/upload/sop` | 上传 **SOP/流程文档**文件（CONFIG Agent 链检索源）|
| POST | `/api/documents/ingest/url` | **URL 录入**：抓取网页正文入库（支持 DOC/SOP 两个知识库）|
| POST | `/api/documents/ingest/rpc` | **RPC 录入**：外部系统直接推送文本入库（CRM/ERP/OA 集成）|
| GET | `/api/documents` | 获取文档列表（含录入来源、状态）|
| DELETE | `/api/documents/{id}` | 删除文档并清理相关向量缓存 |

#### URL 录入请求示例

```bash
curl -X POST http://localhost:8080/api/documents/ingest/url \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com/marketing-guide",
    "storeType": "DOC",
    "timeoutSeconds": 15
  }'
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | 是 | 目标 URL（仅支持 http/https，内置 SSRF 防护）|
| `storeType` | string | 否 | `DOC`（默认，营销文档库）/ `SOP`（SOP 活动库）|
| `timeoutSeconds` | integer | 否 | HTTP 请求超时秒数（默认 15）|

#### RPC 录入请求示例

```bash
curl -X POST http://localhost:8080/api/documents/ingest/rpc \
  -H "Content-Type: application/json" \
  -d '{
    "title": "2024年Q4营销策略",
    "content": "本季度重点聚焦...",
    "sourceSystem": "crm-system",
    "storeType": "DOC"
  }'
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `title` | string | 是 | 文档标题（用于检索来源展示）|
| `content` | string | 是 | 文档正文（UTF-8 文本，直接切块向量化）|
| `sourceSystem` | string | 否 | 来源系统标识（如 `crm-system`，用于追溯）|
| `storeType` | string | 否 | `DOC`（默认）/ `SOP` |

### 缓存管理

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/cache/stats` | 获取缓存统计（命中率、大小、命中/未命中次数）|
| POST | `/api/cache/clear` | 清空所有语义缓存 |
| POST | `/api/cache/evict-expired` | 清理已过期缓存条目 |

### SSE 事件类型

**公共事件（所有路径）：**

```
event: intent   → 意图识别结果
  data: {"type":"QUERY|CONFIG","confidence":0.95,"source":"BERT|LLM|FALLBACK"}

event: error    → 错误信息
  data: {"message":"错误描述"}
```

**QUERY 路径（RAG 问答）：**

```
event: token    → 逐 token 流式文本（实时显示）
event: sources  → 引用的来源文档名称（逗号分隔）
event: done     → [END] 流结束标志
```

**CONFIG 路径（Agent 链）：**

```
event: sop-sources   → SOP 检索来源文档列表（JSON 数组）
event: plan          → 执行计划步骤（JSON 数组）
  data: [{"stepId":1,"action":"createActivity","description":"..."}]

event: agent-status  → Agent 各阶段进度
  data: {"stage":"planner|supervisor|replan|executing","message":"..."}

event: step-start    → 步骤开始
  data: {"stepId":1,"action":"createActivity","status":"running"}

event: step-result   → 步骤完成结果
  data: {"stepId":1,"result":"活动创建成功，ID=ACT_001","success":true}

event: agent-done    → Agent 链全部完成
  data: {"message":"所有步骤执行完成","totalSteps":3}
```

---

## 核心流程说明

### 意图识别与路由

```
用户 Query
    │
    ▼
IntentClassifierService
    ├── Step 1: BERT 分类（若 bert.enabled=true）
    │     POST http://bert-service/classify
    │     confidence >= 0.82 → 直接路由
    │
    ├── Step 2: LLM 降级分类（BERT 不可用/置信度不足）
    │     ChatClient.entity(IntentClassification.class)
    │     Few-Shot Prompt + temperature=0
    │     confidence >= 0.65 → 路由
    │
    └── Step 3: 默认 QUERY（保守策略，避免误触发写操作）
    │
    ▼
意图路由
    ├── QUERY  → MarketingDocRagService + ChatService（RAG 问答）
    └── CONFIG → ConfigAgentOrchestrator（Agent 链）
```

### CONFIG 路径：Agent 链执行

```
用户配置类请求（如"创建双十一满减活动"）
    │
    ▼ Step 1: SOP RAG 检索
    SopRagService.retrieveAndBuildSopContext(userQuery)
    → sopVectorStore 向量相似度检索（阈值 0.60）
    → SSE: event:sop-sources
    │
    ▼ Step 2: Planner Agent
    PlannerAgent.plan(userQuery, sopContext)
    → LLM 解析需求 + SOP 上下文，输出 JSON 步骤数组
    → 步骤含：stepId / action / description / params / dependsOn
    → SSE: event:plan
    │
    ▼ Step 3: Supervisor Agent（最多 2 次重规划）
    SupervisorAgent.validate(userQuery, steps)
    → LLM 审核步骤完整性和依赖关系
    → 发现缺失 → PlannerAgent.replan() → 再次校验
    → SSE: event:agent-status
    │
    ▼ Step 4: Skill Agent（MCP Tool Calling）
    SkillAgent.execute(steps, emitter)
    → 逐步骤调用 ChatClient + @Tool 工具
    → LLM 自主选择工具并绑定参数
    → MarketingApiClient 执行实际 HTTP 请求（含 Token 自动刷新）
    → SSE: event:step-start / event:step-result / event:agent-done
```

### QUERY 路径：并发 RAG 问答

```
用户查询
    │
    ├─ CompletableFuture.supplyAsync(ragExecutor)
    │     MarketingDocRagService.retrieveAndBuildContext(question)
    │     → VectorCacheService 语义缓存命中检查（余弦相似度 > 0.95）
    │     → 未命中 → HybridSearchService.hybridSearch()
    │           ├── vectorStore.similaritySearch()（向量语义）
    │           ├── JdbcTemplate BM25 全文检索
    │           └── RRF 融合排序 → Top-K 结果
    │     → 构建 SystemPrompt 上下文
    │
    ├─ CompletableFuture.supplyAsync(ragExecutor)
    │     retrieveSourceDocuments(question)
    │
    CompletableFuture.allOf().join()  ← 并行等待
    │
    ▼ ChatClient.stream()
    → token-by-token SSE 推送
    → 流结束后发送 sources + done 事件
```

### 分布式 Token 刷新

```
MarketingApiClient 发起请求
    │
    ▼ TokenRefreshService.getValidToken()
    │
    ├── 快路径：读 Redis，有效且未进入提前刷新窗口 → 直接返回
    │
    ├── 慢路径：Redisson 分布式锁（等待 5s，锁租约 10s）
    │     ├── Double-Check：持锁后再读 Redis（他人可能已刷新）
    │     └── 调用中台 /oauth/token，写入 Redis（TTL = expiresIn - 60s）
    │
    └── 降级路径：Redis 不可用 → 本地 synchronized Double-Check 刷新
```

### 文档录入流程（三种方式）

```
录入方式：
  ① 文件上传 /api/documents/upload[/sop]
       └─ MultipartFile → ParserFactory 按扩展名选择解析器
            PDF → PDFBox  |  .docx → Apache POI  |  .txt/.md → TextParser

  ② URL 录入 /api/documents/ingest/url
       └─ UrlFetcher.fetch(url, timeout)
            JDK HttpClient GET → 校验 scheme（防 SSRF）
            Content-Type: text/html → Jsoup 提取正文
            Content-Type: text/plain → 直接使用原始文本

  ③ RPC 录入 /api/documents/ingest/rpc
       └─ 接收 JSON（title + content + sourceSystem）→ 直接进入切块流程

公共入库流程（三种方式共用）：
    │
    ▼ SHA-256 去重检查（已存在则跳过）
    │
    ▼ TokenTextSplitter（chunk=800 tokens，overlap=200 tokens）
    │
    ▼ 附加 Metadata（source / filename / file_type / chunk_index）
    │
    ▼ EmbeddingModel.embed() → 1536 维向量
    │
    ├── 内部文档 → docVectorStore（vector_store 表）
    └── SOP 文档 → sopVectorStore（sop_vector_store 表）
    │
    ▼ 更新文档状态 DONE + 清空向量语义缓存
```

---

## AI 评测体系

本项目包含一套覆盖意图识别、RAG 检索、缓存、Agent 链的完整 AI 评测框架，所有评测测试统一位于 `src/test/java/com/kb/eval/`。

### 评测维度与运行命令

| 评测类 | Tag | 指标 | 数据集 |
|--------|-----|------|--------|
| `IntentEvaluationTest` | `model-eval` | Accuracy / Macro-F1 / ECE / Avg Latency | `intent_dataset.json`（51 条）|
| `RagEvaluationTest` | `model-eval` | Recall@5 / Precision@5 / NDCG@5 / MRR | `rag_dataset.json` |
| `VectorCacheEvaluationTest` | `model-eval` | 合法命中率 / 误命中率 | `cache_eval_pairs.json` |
| `PlannerEvaluationTest` | `agent-eval` | 步骤完整率 / 工具匹配准确率 / JSON 解析成功率 | `planner_dataset.json` |
| `SupervisorEvaluationTest` | `agent-eval` | 正确放行率 / 缺失检出率 / 误报率 | `supervisor_dataset.json` |
| `AgentE2EEvaluationTest` | `agent-eval` | SSE 事件完整性 / 执行成功率 / 重规划触发率 | 内联 EVAL_QUERIES |

```bash
# 仅运行模型评测（意图 + RAG + 缓存，需要 OPENAI_API_KEY）
mvn test -Dgroups="model-eval"

# 仅运行 Agent 评测（Planner + Supervisor + E2E，需要 OPENAI_API_KEY）
mvn test -Dgroups="agent-eval"

# 运行全部评测
mvn test -Dgroups="eval"

# 运行单元测试（无需外部依赖）
mvn test -Dgroups="unit"

# 运行单个评测类
mvn test -Dtest="IntentEvaluationTest" -Dspring.ai.openai.api-key=sk-xxx
mvn test -Dtest="RagEvaluationTest"
mvn test -Dtest="AgentE2EEvaluationTest"
```

### 评测指标说明

#### 意图识别评测（IntentEvaluationTest）

```
指标               通过阈值   说明
────────────────  ─────────  ────────────────────────────────
Accuracy          ≥ 0.90     总体分类准确率
Macro F1          ≥ 0.88     宏平均 F1（各类别等权重）
ECE               ≤ 0.10     期望校准误差（置信度可靠性）
Avg Latency       -          LLM 推理平均延迟（ms）
Per-Class P/R/F1  -          每类（QUERY/CONFIG/UNKNOWN）详细指标
```

#### RAG 检索评测（RagEvaluationTest）

```
指标        说明
──────────  ────────────────────────────────────────────
Recall@5    Top-5 结果中相关文档的召回率
Precision@5 Top-5 结果中相关文档的精确率
NDCG@5      归一化折损累积增益（考虑排序位置）
MRR         平均倒数排名（首个相关文档的排名质量）
```

#### 向量缓存评测（VectorCacheEvaluationTest）

```
指标           说明
─────────────  ──────────────────────────────────────────
合法命中率     语义真正相同的问题对应命中缓存的比例
误命中率       语义不同但相似的问题错误命中缓存的比例
```

#### Agent 评测

```
PlannerEvaluationTest：
  步骤完整率      关键 action 均被包含的比例
  工具匹配准确率  action 映射到正确 MCP 工具的比例
  参数提取率      从 query 中正确提取参数的比例
  JSON 解析成功率 LLM 输出可被正确解析为步骤列表的比例

SupervisorEvaluationTest：
  正确放行率      完整计划被判为通过的比例
  缺失检出率      不完整计划中缺失步骤被发现的比例
  误报率          完整计划被误判为不完整的比例

AgentE2EEvaluationTest：
  SSE 事件完整性  是否收到全部 6 种预期事件类型
  执行成功率      所有步骤全部成功的比例
  重规划触发率    Supervisor 触发 replan 的频率
```

### 评测工具类

| 类 | 功能 |
|----|------|
| `EvalMetricsCalculator` | 通用指标计算：Accuracy / Precision / Recall / F1 / NDCG / MRR / ECE |
| `EvalReportPrinter` | 控制台格式化输出，含 PASS/FAIL 标记 |
| `EvalDatasetLoader` | 从 classpath JSON 文件加载标注数据集 |

---

## MCP 工具列表

| 工具名 | 对应 API | 说明 |
|--------|----------|------|
| `createActivity` | POST `/api/v1/activities` | 创建营销活动（名称/类型/时间/配置）|
| `listActivities` | GET `/api/v1/activities` | 查询活动列表（按类型/时间范围过滤）|
| `getActivityDetail` | GET `/api/v1/activities/{id}` | 查询单个活动详情 |
| `updateActivity` | PUT `/api/v1/activities/{id}` | 更新活动信息 |
| `toggleActivityStatus` | PATCH `/api/v1/activities/{id}/status` | 启用/禁用活动 |
| `createCoupon` | POST `/api/v1/coupons` | 创建优惠券（关联活动）|
| `configureActivityRules` | POST `/api/v1/activities/{id}/rules` | 配置满减/折扣/赠品规则 |

---

## 配置参数

### RAG 检索

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.rag.top-k` | 5 | 检索返回最相关片段数 |
| `kb.rag.similarity-threshold` | 0.65 | 最低相似度阈值（SOP 路径为 0.60）|
| `kb.rag.use-hybrid-search` | true | 是否启用混合检索 |

### Hybrid Search 混合检索

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.hybrid.enabled` | true | 是否启用混合检索 |
| `kb.hybrid.vector-weight` | 0.5 | 向量检索权重 |
| `kb.hybrid.keyword-weight` | 0.5 | BM25 关键词检索权重 |
| `kb.hybrid.rrf-k` | 60 | RRF 融合算法参数 |
| `kb.hybrid.vector-top-k` | 20 | 向量检索候选数 |
| `kb.hybrid.keyword-top-k` | 20 | BM25 检索候选数 |

### 向量语义缓存

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.cache.max-size` | 500 | 最大缓存问题数（LRU 淘汰）|
| `kb.cache.similarity-threshold` | 0.95 | 余弦相似度阈值（>0.95 视为相同问题）|
| `kb.cache.ttl-minutes` | 30 | 缓存 TTL（分钟）|

### BERT 意图识别

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `bert.enabled` | false | 是否启用 BERT 主路径（false=直接 LLM 降级）|
| `bert.service.url` | http://localhost:8000 | BERT FastAPI 服务地址 |
| `bert.confidence-threshold` | 0.82 | BERT 置信度阈值（低于此值触发 LLM 降级）|
| `intent.fallback-threshold` | 0.65 | LLM 分类最低置信度（低于此值默认 QUERY）|

### Agent 链

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.planner.max-steps` | 10 | Planner 最多拆分步骤数 |
| `agent.supervisor.max-replan-count` | 2 | Supervisor 触发重规划的最大次数 |

### 分布式 Token 刷新

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `marketing.token.middle-platform-url` | http://localhost:8888 | 中台 OAuth 服务地址 |
| `marketing.token.redis-key` | marketing:token | Redis 缓存 Key |
| `marketing.token.lock-key` | marketing:token:lock | Redisson 分布式锁 Key |
| `marketing.token.lock-wait-seconds` | 5 | 等锁最长时间（秒）|
| `marketing.token.lock-lease-seconds` | 10 | 锁自动释放时间（秒，防锁泄漏）|
| `marketing.token.refresh-ahead-seconds` | 300 | 提前刷新窗口（Token 剩余 <5min 时主动刷新）|
| `marketing.token.max-retry` | 3 | 获锁失败后最大自旋重试次数 |

### URL 录入

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.ingest.url.user-agent` | Mozilla/5.0 (compatible; KnowledgeBase-Bot/1.0) | HTTP 请求 User-Agent |
| `kb.ingest.url.max-content-bytes` | 5242880（5MB）| 网页内容最大字节数（超出截断）|

### 并发线程池

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.async.core-pool-size` | 4 | 核心线程数 |
| `kb.async.max-pool-size` | 16 | 最大线程数（I/O 密集型）|
| `kb.async.queue-capacity` | 100 | 队列容量 |
| `kb.async.keep-alive-seconds` | 60 | 线程空闲时间（秒）|

### LLM / Embedding

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.openai.chat.options.model` | gpt-4o-mini | 对话模型 |
| `spring.ai.openai.chat.options.temperature` | 0.3 | 采样温度 |
| `spring.ai.openai.embedding.options.model` | text-embedding-3-small | Embedding 模型 |
| `spring.ai.vectorstore.pgvector.dimensions` | 1536 | 向量维度 |

### 文档处理

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.document.chunk-size` | 800 | 每块最大 Token 数 |
| `kb.document.chunk-overlap` | 200 | 相邻块重叠 Token 数 |
| `kb.document.max-file-size` | 50MB | 最大文件大小 |
| `kb.document.allowed-types` | pdf,docx,doc,txt,md | 支持的文件类型 |

---

## 环境变量

| 变量 | 说明 |
|------|------|
| `OPENAI_API_KEY` | OpenAI API Key（必填）|
| `OPENAI_BASE_URL` | API Base URL（使用代理时配置）|
| `BERT_ENABLED` | 是否启用 BERT 服务（默认 false）|
| `BERT_SERVICE_URL` | BERT FastAPI 推理服务地址 |
| `MARKETING_API_BASE_URL` | 营销平台 API Base URL |
| `MIDDLE_PLATFORM_URL` | 中台 OAuth 服务地址 |
| `MIDDLE_PLATFORM_APP_ID` | 中台 App ID |
| `MIDDLE_PLATFORM_APP_SECRET` | 中台 App Secret |
| `REDIS_HOST` | Redis 主机（默认 localhost）|
| `REDIS_PORT` | Redis 端口（默认 6379）|
| `REDIS_PASSWORD` | Redis 密码（默认为空）|
| `KB_UPLOAD_DIR` | 上传文件临时目录（默认 `./uploads`）|

---

## 数据库设计

| 表名 | 说明 |
|------|------|
| `kb_document` | 文档元数据（filename / file_hash / status / chunk_count / **source_type / source_url / source_system**）|
| `vector_store` | 内部文档向量表（Spring AI 自动建表，HNSW 索引，供 QUERY 路径检索）|
| `sop_vector_store` | SOP 文档向量表（Spring AI 自动建表，HNSW 索引，供 CONFIG 路径检索）|
| `mcp_call_log` | MCP 工具调用审计日志（query / intent / plan_steps / exec_result / status）|

`kb_document.source_type` 枚举值：
- `FILE`：文件上传（multipart）
- `URL`：URL 录入（网页抓取）
- `RPC`：RPC 录入（外部系统推送）

---

## 性能优化说明

### 并发 RAG 检索（延迟降低 43%）

```
优化前：串行 → 上下文检索(200ms) + 来源查询(150ms) = 350ms
优化后：并行 → max(200ms, 150ms) = 200ms
```

使用 `CompletableFuture.supplyAsync() + ragExecutor` 线程池实现真正并行，两个任务同时执行。

### 语义向量缓存（命中时检索耗时减少 60-80%）

基于余弦相似度（阈值 0.95）的语义缓存——表述不同但语义相近的问题可命中同一缓存。

- **读锁**：多线程并发遍历缓存查找相似问题
- **写锁**：LRU 移头 + 新增写入时独占，保证一致性
- **死锁规避**：读锁内发现命中后先释放再申请写锁，避免锁升级

### BERT + LLM 双层意图识别

| 层级 | 延迟 | 消耗 Token |
|------|------|-----------|
| BERT 主路径 | < 50ms | 否 |
| LLM 降级 | 200-500ms | 是（temperature=0，Few-Shot）|
| 默认 QUERY | <1ms | 否 |

保守降级策略（置信度不足时默认 QUERY）避免误触发写操作。

### 分布式 Token 刷新（无惊群效应）

- **快路径**（无锁）：直接读 Redis，命中且在有效期内直接返回
- **慢路径**（有锁）：Redisson 分布式锁 + Double-Check，保证多实例下只有一个执行刷新
- **提前刷新**：Token 剩余有效期 < 5 分钟时主动刷新，避免临界过期
- **降级**：Redis 宕机时自动切换为本地 `synchronized` 刷新，单节点继续可用

---

## 切换其他 LLM 提供商

替换 `pom.xml` 中的 Starter 依赖：

```xml
<!-- Ollama（本地模型）-->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
</dependency>

<!-- Azure OpenAI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-azure-openai-spring-boot-starter</artifactId>
</dependency>
```

并在 `application.yml` 中配置对应模型名称和端点。

---

## License

MIT License
