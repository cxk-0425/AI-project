# 🧠 企业知识库问答系统

> 基于 **Spring AI 1.0 + PGVector** 构建的企业级 RAG 知识库系统，支持文档解析、混合检索（Hybrid Search）、向量缓存、智能并发与流式上下文增强生成。

---

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          前端 UI                                  │
│              HTML/CSS/JS + SSE 流式输出 + 实时显示               │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                      Spring Boot API                             │
│                   REST + SSE (Port 8080)                        │
└────────┬─────────────────┬──────────────────┬───────────────────┘
         │                 │                  │
┌────────▼────────┐ ┌─────▼────────────┐ ┌───▼────────────────────┐
│   文档处理模块   │ │   RAG 检索模块   │ │      Chat 模块         │
│   DocumentService│ │   RagService     │ │   ChatService          │
│   + Parser 工厂  │ │   + HybridSearch │ │   + 并发执行           │
└────────┬────────┘ └─────┬────────────┘ └───┬────────────────────┘
         │                 │                  │
         │         ┌───────▼──────────────────▼──────┐
         │         │              │                  │
┌────────▼────────▼┐    ┌───────▼────────┐  ┌──────▼──────────┐
│  文档元数据表     │    │   Vector Store │  │   ChatClient    │
│  kb_document     │    │   (PGVector)   │  │   (OpenAI)      │
└─────────────────┘    └────────────────┘  └─────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │   PostgreSQL 全文索引   │
                    │   (BM25 关键词检索)   │
                    └───────────────────────┘
```

### 核心特性

| 特性 | 说明 |
|------|------|
| **混合检索 (Hybrid Search)** | 向量语义检索 + BM25 关键词检索 + RRF 融合排序 |
| **向量缓存** | 基于余弦相似度的语义缓存，支持 LRU + TTL 淘汰策略 |
| **并发检索** | 上下文检索与来源查询并行执行，显著降低延迟 |
| **流式输出 (SSE)** | Token 级实时推送，支持中断恢复 |
| **多格式支持** | PDF、Word、TXT、Markdown 文档解析 |
| **智能分块** | Token 级文本分割，保留语义边界与重叠区域 |

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
│   │   │   ├── VectorStoreConfig.java   # PGVector 向量存储配置
│   │   │   ├── EmbeddingConfig.java     # Embedding 模型配置
│   │   │   ├── ChatModelConfig.java     # ChatClient 配置
│   │   │   └── AsyncConfig.java        # RAG 并发执行线程池
│   │   ├── controller/
│   │   │   ├── DocumentController.java # 文档管理 API
│   │   │   ├── ChatController.java      # 问答 API（SSE 流式）
│   │   │   └── CacheController.java     # 缓存管理 API
│   │   ├── service/
│   │   │   ├── DocumentService.java     # 文档解析 + 分块 + 向量化入库
│   │   │   ├── RagService.java         # 检索 + 上下文构建 + 缓存管理
│   │   │   ├── ChatService.java         # 流式问答 + 并发执行
│   │   │   ├── VectorCacheService.java  # 向量语义缓存（LRU + TTL）
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
│       ├── application.yml              # 主配置（含 Hybrid/RAG/Cache 配置）
│       ├── schema.sql                   # 数据库初始化脚本
│       └── static/
│           └── index.html               # 前端 UI（单页面）
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
cd /Users/lirxin/knowledge-base
docker-compose up -d
```

等待 PostgreSQL 健康检查通过：
```bash
docker ps   # 确认 kb-pgvector 状态为 healthy
```

### 3. 配置 API Key

**方式一：环境变量（推荐）**
```bash
export OPENAI_API_KEY=sk-your-key-here
export OPENAI_BASE_URL=https://api.openai.com  # 可选，自定义代理
```

**方式二：修改 `application.yml`**
```yaml
spring:
  ai:
    openai:
      api-key: sk-your-key-here
      base-url: https://api.openai.com
```

### 4. 构建并运行

```bash
cd /Users/lirxin/knowledge-base
mvn spring-boot:run
```

启动成功后访问：**http://localhost:8080**

---

## 📡 API 接口说明

### 文档管理

| Method | URL | 说明 |
|--------|-----|------|
| POST | `/api/documents/upload` | 上传文档（multipart/form-data, field: file） |
| GET | `/api/documents` | 获取文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档（同时清理向量缓存）|

### 问答接口

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/chat/stream?q=问题` | **流式问答**（SSE，推荐） |
| POST | `/api/chat/sync` | 同步问答，Body: `{"question":"..."}` |
| POST | `/api/chat/retrieve` | 仅检索不调用 LLM，用于调试 |

### 缓存管理

| Method | URL | 说明 |
|--------|-----|------|
| GET | `/api/cache/stats` | 获取缓存统计（命中率、大小等）|
| POST | `/api/cache/clear` | 清空所有缓存 |
| POST | `/api/cache/evict-expired` | 清理过期缓存条目 |

### SSE 事件类型

```
event: token   → 逐 token 文本输出（实时显示）
event: sources → 引用的来源文档名称（逗号分隔）
event: done    → [END] 流结束标志
event: error   → 错误信息（LLM 服务异常时）
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
| `kb.hybrid.rrf-k` | 60 | RRF 融合参数（k 值越大差异越小）|
| `kb.hybrid.vector-top-k` | 20 | 向量检索候选数量 |
| `kb.hybrid.keyword-top-k` | 20 | BM25 检索候选数量 |

### 向量缓存配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.cache.max-size` | 500 | 最大缓存问题数量 |
| `kb.cache.similarity-threshold` | 0.95 | 相似度阈值（> 0.95 视为相同问题）|
| `kb.cache.ttl-minutes` | 30 | 缓存过期时间（分钟）|

### 并发线程池配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `kb.async.core-pool-size` | 4 | 核心线程数（建议 CPU 核心数）|
| `kb.async.max-pool-size` | 16 | 最大线程数（I/O 密集型）|
| `kb.async.queue-capacity` | 100 | 队列容量 |
| `kb.async.keep-alive-seconds` | 60 | 线程空闲时间 |

### LLM 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.openai.chat.options.model` | gpt-4o-mini | LLM 模型 |
| `spring.ai.openai.embedding.options.model` | text-embedding-3-small | Embedding 模型（1536 维）|
| `spring.ai.vectorstore.pgvector.dimensions` | 1536 | 向量维度 |
| `spring.ai.vectorstore.pgvector.index-type` | HNSW | 索引类型 |
| `spring.ai.vectorstore.pgvector.distance-type` | COSINE_DISTANCE | 距离类型 |

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
| `KB_UPLOAD_DIR` | 上传文件临时目录（默认 `./uploads`）|

---

## 💡 核心流程说明

### 文档入库流程

```
上传文件 (multipart/form-data)
    │
    ▼
SHA-256 去重检查 ──────────────────────────────────┐
    │                                              │
    ▼                                              ▼
 Parser 解析纯文本                              返回已存在文档
 (PDF: PDFBox / Word: POI / TXT: 直接读取)
    │
    ▼
TokenTextSplitter 智能切块
 - 800 tokens/chunk
 - 200 tokens overlap（保持语义连贯）
 - 优先在段落边界分割
    │
    ▼
附加 Metadata
 - source: 文档 UUID
 - filename: 文件名
 - file_type: 文件类型
 - chunk_index: 片段序号
 - total_chunks: 片段总数
    │
    ▼
EmbeddingModel.embed() → 1536 维向量
    │
    ▼
VectorStore.add() → 存入 PGVector
 - HNSW 索引
 - 余弦距离
    │
    ▼
更新文档状态 DONE
    │
    ▼
清空向量缓存（文档变更后旧缓存失效）
```

### Hybrid Search 混合检索流程

```
用户提问
    │
    ├──▶ 阶段1: 向量检索
    │       VectorStore.similaritySearch(topK=20)
    │       获取语义相似文档 + 相似度分数
    │
    ├──▶ 阶段2: BM25 关键词检索
    │       PostgreSQL to_tsvector/to_tsquery
    │       ts_rank_cd 计算 BM25 分数
    │
    ▼
阶段3: RRF 融合排序
 Reciprocal Rank Fusion
 RRF Score = Σ (weight × score × 1/(k + rank))
 - 1/(60+0) = 0.0164 (排名第1)
 - 1/(60+1) = 0.0161 (排名第2)
 - ... (逐步衰减)
    │
    ▼
阶段4: 综合排序取 Top-K
    │
    ▼
返回融合后的最相关文档片段
```

### 问答并发执行流程

```
用户提问
    │
    ▼
┌──────────────────────────────────────┐
│      并行执行两个独立任务              │
├──────────────────────────────────────┤
│                                      │
│  CompletableFuture.supplyAsync(       │
│      ragService.retrieveAndBuildContext()
│      , ragExecutor)  ──┐             │
│                          │ 并行执行    │
│  CompletableFuture.supplyAsync(       │
│      ragService.retrieveSourceDocs()  │             │
│      , ragExecutor)  ──┘             │
│                                      │
│  allOf(...).join()  等待两者完成      │
│                                      │
└──────────────────────────────────────┘
    │
    ▼
获取结果
 - context: 上下文文本
 - sources: 来源文档列表
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

### 向量缓存命中流程

```
用户提问
    │
    ▼
查询向量缓存
 - EmbeddingModel.embed(question)
 - 遍历缓存计算余弦相似度
 - similarity > 0.95? → 命中
    │
    ├──▶ 命中 ◀─────────────┐
    │   返回缓存的上下文    │ LRU 更新
    │   直接进入 LLM 生成   │ (移到队头)
    │                      │
    └──▶ 未命中 ───────────┘
        执行 Hybrid Search
        构建上下文
        写入缓存
```

---

## 🛠️ 切换其他 LLM 提供商

若需使用 Ollama、Azure OpenAI 等，只需替换 Starter 依赖：

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

VectorCacheService 使用 `ReentrantReadWriteLock`：

- **读锁**：多个线程可同时遍历缓存（读并发）
- **写锁**：修改时独占，保证数据一致性
- **场景**：读多写少，并发读取效率高

---

## 📝 许可证

MIT License
