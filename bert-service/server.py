"""
BERT 意图分类 FastAPI 推理服务
============================
对应 Java 侧 BertIntentClassifier 的 REST 接口约定：
  POST /classify
  Request:  {"text": "用户输入"}
  Response: {"label": "QUERY|CONFIG|UNKNOWN", "confidence": 0.95}

前置条件：先运行 train.py 生成 ./model/bert-intent/ 目录

启动：
  uvicorn server:app --host 0.0.0.0 --port 8000

验证：
  curl -X POST http://localhost:8000/classify \\
    -H "Content-Type: application/json" \\
    -d '{"text": "帮我创建一个双十一满减活动"}'
  # 期望: {"label":"CONFIG","confidence":0.98}
"""

import torch
import torch.nn.functional as F
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoTokenizer, AutoModelForSequenceClassification

# ═══════════════════════════════════════════════════════════════════════════
# 配置（与 train.py 保持一致）
# ═══════════════════════════════════════════════════════════════════════════

MODEL_DIR = "./model/bert-intent"   # fine-tune 后的模型目录（train.py 的 OUTPUT_DIR）
MAX_LEN   = 64
DEVICE    = "cuda" if torch.cuda.is_available() else "cpu"


# ═══════════════════════════════════════════════════════════════════════════
# 启动时一次性加载模型（推理时复用，避免每次请求重新加载）
# ═══════════════════════════════════════════════════════════════════════════

print(f"[BERT Service] 加载模型 {MODEL_DIR}，device={DEVICE} ...")

try:
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_DIR)
    model.to(DEVICE)
    model.eval()
    print(f"[BERT Service] 模型加载完成，标签映射: {model.config.id2label}")
except Exception as e:
    raise RuntimeError(
        f"[BERT Service] 模型加载失败: {e}\n"
        "请先运行 python train.py 生成 ./model/bert-intent/ 目录"
    )


# ═══════════════════════════════════════════════════════════════════════════
# FastAPI 应用
# ═══════════════════════════════════════════════════════════════════════════

app = FastAPI(
    title="BERT Intent Classifier",
    description="营销系统意图识别服务，对应 Java 侧 BertIntentClassifier",
    version="1.0.0",
)


# ── 接口 DTO ────────────────────────────────────────────────────────────────

class ClassifyRequest(BaseModel):
    text: str = Field(..., description="用户原始输入文本", min_length=1, max_length=512)


class ClassifyResponse(BaseModel):
    label: str       = Field(..., description="意图类型：QUERY | CONFIG | UNKNOWN")
    confidence: float = Field(..., description="置信度，范围 0.0 ~ 1.0", ge=0.0, le=1.0)


# ── 健康检查 ─────────────────────────────────────────────────────────────────

@app.get("/health", summary="健康检查")
def health():
    """Java 侧启动时可调用此接口确认 BERT 服务就绪"""
    return {"status": "ok", "device": DEVICE, "model": MODEL_DIR}


# ── 意图分类（核心接口）──────────────────────────────────────────────────────

@app.post("/classify", response_model=ClassifyResponse, summary="意图分类")
def classify(req: ClassifyRequest):
    """
    将用户输入文本分类为 QUERY / CONFIG / UNKNOWN。

    - QUERY：用户想查看、检索、了解信息
    - CONFIG：用户想创建、修改、删除、执行操作
    - UNKNOWN：语义模糊或与营销系统无关
    """
    try:
        # 1. Tokenize
        inputs = tokenizer(
            req.text,
            truncation=True,
            padding="max_length",
            max_length=MAX_LEN,
            return_tensors="pt",
        ).to(DEVICE)

        # 2. 推理（no_grad 节省显存，eval 模式关闭 dropout）
        with torch.no_grad():
            logits = model(**inputs).logits      # shape: [1, num_labels]
            probs  = F.softmax(logits, dim=-1)[0]  # shape: [num_labels]

        # 3. 取最高概率的类别
        pred_id    = int(probs.argmax().item())
        confidence = float(probs[pred_id].item())
        label      = model.config.id2label[pred_id]   # "QUERY" / "CONFIG" / "UNKNOWN"

        return ClassifyResponse(
            label=label,
            confidence=round(confidence, 4),
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"分类失败: {str(e)}")


# ── 批量分类（可选，用于评测脚本）──────────────────────────────────────────

class BatchClassifyRequest(BaseModel):
    texts: list[str] = Field(..., description="批量文本列表", min_length=1, max_length=100)


class BatchClassifyResponse(BaseModel):
    results: list[ClassifyResponse]


@app.post("/classify/batch", response_model=BatchClassifyResponse, summary="批量意图分类")
def classify_batch(req: BatchClassifyRequest):
    """批量分类，供评测脚本使用（Java 侧不调用此接口）"""
    try:
        inputs = tokenizer(
            req.texts,
            truncation=True,
            padding=True,
            max_length=MAX_LEN,
            return_tensors="pt",
        ).to(DEVICE)

        with torch.no_grad():
            logits = model(**inputs).logits
            probs  = F.softmax(logits, dim=-1)    # shape: [batch_size, num_labels]

        results = []
        for i in range(len(req.texts)):
            pred_id    = int(probs[i].argmax().item())
            confidence = float(probs[i][pred_id].item())
            label      = model.config.id2label[pred_id]
            results.append(ClassifyResponse(label=label, confidence=round(confidence, 4)))

        return BatchClassifyResponse(results=results)

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"批量分类失败: {str(e)}")
