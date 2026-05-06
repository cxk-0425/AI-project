# BERT 意图识别服务

基于 `bert-base-chinese` fine-tune 的三分类意图识别器，为 Java 侧 `BertIntentClassifier` 提供推理服务。

## 目录结构

```
bert-service/
├── requirements.txt          # Python 依赖
├── train.py                  # fine-tune 训练脚本
├── server.py                 # FastAPI 推理服务
├── data/
│   └── intent_dataset.json   # 训练数据（从项目复制或扩充后放这里）
└── model/
    └── bert-intent/          # fine-tune 后自动生成，server.py 从此加载
```

## 快速开始

### 1. 安装依赖

```bash
cd bert-service
pip install -r requirements.txt
```

### 2. 准备数据

将项目中的标注数据复制到 `data/` 目录：

```bash
cp ../src/test/resources/eval/intent_dataset.json data/
```

> **注意**：当前数据集仅 51 条，建议扩充到 700+ 条（见下方「数据扩充策略」）。

### 3. 运行 fine-tune 训练

```bash
python train.py
```

训练完成后模型保存到 `./model/bert-intent/`。

- CPU：约 5~10 分钟
- GPU（CUDA）：约 1~2 分钟，自动开启 FP16 混合精度

### 4. 启动推理服务

```bash
uvicorn server:app --host 0.0.0.0 --port 8000
```

### 5. 验证接口

```bash
# 健康检查
curl http://localhost:8000/health

# 意图分类
curl -X POST http://localhost:8000/classify \
  -H "Content-Type: application/json" \
  -d '{"text": "帮我创建一个双十一满减活动"}'
# 期望: {"label":"CONFIG","confidence":0.98}

curl -X POST http://localhost:8000/classify \
  -H "Content-Type: application/json" \
  -d '{"text": "查一下当前有哪些优惠活动"}'
# 期望: {"label":"QUERY","confidence":0.97}
```

### 6. 开启 Java 侧 BERT 路径

```bash
export BERT_ENABLED=true
export BERT_SERVICE_URL=http://localhost:8000
```

或在 `application.yml` 中修改：

```yaml
bert:
  enabled: true
  service:
    url: http://localhost:8000
  confidence-threshold: 0.82   # 低于此值降级到 LLM
```

---

## 接口说明

### POST /classify

对应 Java 侧 `BertIntentClassifier` 的调用约定。

**Request**
```json
{"text": "用户原始输入文本"}
```

**Response**
```json
{"label": "QUERY|CONFIG|UNKNOWN", "confidence": 0.95}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `label` | string | 意图类型：`QUERY`（查询）/ `CONFIG`（配置）/ `UNKNOWN`（未知）|
| `confidence` | float | 置信度，0.0~1.0，低于 `bert.confidence-threshold`（0.82）时 Java 侧降级到 LLM |

### POST /classify/batch

批量分类接口，供评测脚本使用，Java 侧不调用。

```bash
curl -X POST http://localhost:8000/classify/batch \
  -H "Content-Type: application/json" \
  -d '{"texts": ["创建活动", "查看活动列表", "今天天气"]}'
```

---

## 意图类型定义

| 类型 | 含义 | 示例 |
|------|------|------|
| `QUERY` | 查询类：用户想查看/了解/检索信息 | "当前有哪些优惠券活动？" |
| `CONFIG` | 配置类：用户想创建/修改/删除/执行操作 | "帮我创建双十一满减活动" |
| `UNKNOWN` | 未知：语义模糊或与营销系统无关 | "今天天气怎么样" |

---

## 数据扩充策略

当前 51 条数据严重不足，**这是准确率的最大瓶颈**。推荐量：

| 类型 | 当前 | 建议最少 |
|------|------|---------|
| QUERY | 16 条 | 300 条 |
| CONFIG | 16 条 | 300 条 |
| UNKNOWN | 8 条 | 100 条 |
| 边界/模糊样本 | 11 条 | 100 条 |

### 用 GPT-4 批量生成（推荐）

发送以下 Prompt 给 GPT-4，每次可生成 100 条：

```
你是营销系统意图分类训练数据生成器。
请为每种意图生成 100 条不重复的中文用户输入，
要求多样化（口语/书面/长句/短句/模糊表达/错别字）：

QUERY（查询类）：用户想查看/了解/检索营销活动信息
CONFIG（配置类）：用户想创建/修改/删除/执行营销操作
UNKNOWN（无关类）：与营销系统无关的随机输入

输出 JSON 数组格式：
[{"query": "...", "expected": "QUERY"}, ...]
```

### 边界样本（必须重点补充）

以下类型容易分类错误，需要专门补充：

| 样本 | 正确标签 | 说明 |
|------|---------|------|
| `"活动搞起来"` | CONFIG | 口语化操作 |
| `"有没有满减的"` | QUERY | 询问存在性=查询 |
| `"看一下活动状态不太对好像需要改一下"` | CONFIG | 虽有"看"但意图是修改 |
| `"查看活动顺便改一下时间"` | CONFIG | 混合意图，以操作为主 |
| `"能不能帮我弄个优惠"` | CONFIG | 口语化创建 |

---

## 提升准确率的关键参数

| 参数 | 位置 | 建议值 | 说明 |
|------|------|--------|------|
| `PRETRAINED_MODEL` | `train.py` | `hfl/chinese-roberta-wwm-ext` | RoBERTa-wwm 对中文效果更好，只需改一行 |
| `learning_rate` | `train.py` | `2e-5`（默认）| BERT fine-tune 标准范围 1e-5~5e-5 |
| `bert.confidence-threshold` | `application.yml` | 训练好后调至 `0.75` | 提高 BERT 命中率，减少 LLM 降级 |
| 数据量 | `data/intent_dataset.json` | 700+ 条 | **最关键** |

### 切换到 RoBERTa-wwm（推荐）

在 `train.py` 中修改一行：

```python
# 修改前
PRETRAINED_MODEL = "bert-base-chinese"

# 修改后（效果更好，接口完全兼容）
PRETRAINED_MODEL = "hfl/chinese-roberta-wwm-ext"
```

---

## 与 Java 侧的集成关系

```
Java: IntentClassifierService
  ↓ bert.enabled=true
Java: BertIntentClassifier
  ↓ POST http://localhost:8000/classify
Python: server.py (FastAPI)
  ↓ 加载 ./model/bert-intent/
Python: fine-tuned BERT 模型
         (由 train.py 训练生成)
```

置信度低于 `bert.confidence-threshold: 0.82` 时，Java 侧自动降级到 `LlmIntentClassifier`（Few-Shot + temperature=0）。
