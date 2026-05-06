"""
BERT Fine-tune 训练脚本
=====================
将 bert-base-chinese（或 hfl/chinese-roberta-wwm-ext）微调为
QUERY / CONFIG / UNKNOWN 三分类意图识别器。

对应 Java 侧：com.kb.intent.BertIntentClassifier
  POST /classify
  Request:  {"text": "用户输入"}
  Response: {"label": "QUERY|CONFIG|UNKNOWN", "confidence": 0.95}

运行：
  python train.py

依赖：
  pip install -r requirements.txt
"""

import json
import random
from pathlib import Path

import torch
from datasets import Dataset
from sklearn.metrics import classification_report
from transformers import (
    AutoTokenizer,
    AutoModelForSequenceClassification,
    TrainingArguments,
    Trainer,
    EarlyStoppingCallback,
)

# ═══════════════════════════════════════════════════════════════════════════
# 配置区（按需修改）
# ═══════════════════════════════════════════════════════════════════════════

# 预训练模型：
#   "bert-base-chinese"              - 默认，适合快速验证
#   "hfl/chinese-roberta-wwm-ext"    - 推荐，中文效果更好，替换一行即可
PRETRAINED_MODEL = "bert-base-chinese"

DATA_PATH   = "data/intent_dataset.json"   # 训练数据路径（从项目 src/test/resources/eval/intent_dataset.json 复制）
OUTPUT_DIR  = "./model/bert-intent"        # fine-tune 后模型保存路径（server.py 读取此路径）
MAX_LEN     = 64      # 意图识别句子较短，64 已足够
BATCH_SIZE  = 16
EPOCHS      = 10      # EarlyStopping 会提前停止，无需担心 overfit
SEED        = 42

# 类别映射（与 Java 侧 IntentType 枚举保持一致）
LABEL2ID = {"QUERY": 0, "CONFIG": 1, "UNKNOWN": 2}
ID2LABEL = {v: k for k, v in LABEL2ID.items()}


# ═══════════════════════════════════════════════════════════════════════════
# 1. 加载数据
# ═══════════════════════════════════════════════════════════════════════════

def load_data(path: str) -> list[dict]:
    """加载 intent_dataset.json，转换为 {text, label} 格式"""
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return [
        {"text": item["query"], "label": LABEL2ID[item["expected"]]}
        for item in raw
    ]


random.seed(SEED)
data = load_data(DATA_PATH)
random.shuffle(data)

# 8:1:1 划分 train / val / test
n = len(data)
train_data = data[:int(n * 0.8)]
val_data   = data[int(n * 0.8):int(n * 0.9)]
test_data  = data[int(n * 0.9):]

print(f"数据集划分 → Train: {len(train_data)}, Val: {len(val_data)}, Test: {len(test_data)}")

# 检查数据量：< 200 条时给出警告
if len(data) < 200:
    print(f"\n[警告] 当前数据集仅 {len(data)} 条，建议扩充到 700+ 条以获得更好的准确率。")
    print("参考 README.md 中的「数据扩充策略」章节使用 GPT-4 批量生成数据。\n")


# ═══════════════════════════════════════════════════════════════════════════
# 2. Tokenize
# ═══════════════════════════════════════════════════════════════════════════

tokenizer = AutoTokenizer.from_pretrained(PRETRAINED_MODEL)


def tokenize(batch):
    return tokenizer(
        batch["text"],
        truncation=True,
        padding="max_length",
        max_length=MAX_LEN,
    )


train_ds = Dataset.from_list(train_data).map(tokenize, batched=True)
val_ds   = Dataset.from_list(val_data).map(tokenize, batched=True)
test_ds  = Dataset.from_list(test_data).map(tokenize, batched=True)

fmt_cols = ["input_ids", "attention_mask", "token_type_ids", "label"]
train_ds.set_format("torch", columns=fmt_cols)
val_ds.set_format("torch", columns=fmt_cols)
test_ds.set_format("torch", columns=fmt_cols)


# ═══════════════════════════════════════════════════════════════════════════
# 3. 加载模型（在 bert-base-chinese 基础上添加 3 分类头）
# ═══════════════════════════════════════════════════════════════════════════

model = AutoModelForSequenceClassification.from_pretrained(
    PRETRAINED_MODEL,
    num_labels=3,
    id2label=ID2LABEL,
    label2id=LABEL2ID,
)


# ═══════════════════════════════════════════════════════════════════════════
# 4. 评测指标：Accuracy + Macro-F1 + 每类 F1
# ═══════════════════════════════════════════════════════════════════════════

def compute_metrics(eval_pred):
    logits, labels = eval_pred
    preds = logits.argmax(axis=-1)
    report = classification_report(
        labels, preds,
        target_names=["QUERY", "CONFIG", "UNKNOWN"],
        output_dict=True,
        zero_division=0,
    )
    return {
        "accuracy":   report["accuracy"],
        "macro_f1":   report["macro avg"]["f1-score"],
        "query_f1":   report["QUERY"]["f1-score"],
        "config_f1":  report["CONFIG"]["f1-score"],
        "unknown_f1": report["UNKNOWN"]["f1-score"],
    }


# ═══════════════════════════════════════════════════════════════════════════
# 5. 训练参数
# ═══════════════════════════════════════════════════════════════════════════

args = TrainingArguments(
    output_dir=OUTPUT_DIR,
    num_train_epochs=EPOCHS,
    per_device_train_batch_size=BATCH_SIZE,
    per_device_eval_batch_size=BATCH_SIZE,
    learning_rate=2e-5,       # BERT fine-tune 标准学习率范围：1e-5 ~ 5e-5
    warmup_ratio=0.1,         # 前 10% steps 做 learning rate warm-up
    weight_decay=0.01,        # L2 正则，防止 overfit
    evaluation_strategy="epoch",
    save_strategy="epoch",
    load_best_model_at_end=True,
    metric_for_best_model="macro_f1",
    greater_is_better=True,
    logging_steps=10,
    seed=SEED,
    fp16=torch.cuda.is_available(),   # GPU 时开启 FP16 混合精度，加速约 2x
)

trainer = Trainer(
    model=model,
    args=args,
    train_dataset=train_ds,
    eval_dataset=val_ds,
    compute_metrics=compute_metrics,
    callbacks=[
        # 连续 3 轮 macro_f1 未提升则提前停止，防止 overfit
        EarlyStoppingCallback(early_stopping_patience=3),
    ],
)


# ═══════════════════════════════════════════════════════════════════════════
# 6. 训练
# ═══════════════════════════════════════════════════════════════════════════

print(f"\n[开始训练] 模型: {PRETRAINED_MODEL}, device: {'GPU' if torch.cuda.is_available() else 'CPU'}")
trainer.train()


# ═══════════════════════════════════════════════════════════════════════════
# 7. 测试集最终评测（反映真实泛化能力）
# ═══════════════════════════════════════════════════════════════════════════

print("\n" + "=" * 60)
print("Test Set 最终评测结果")
print("=" * 60)

results = trainer.predict(test_ds)
preds  = results.predictions.argmax(axis=-1)
labels = results.label_ids

print(classification_report(
    labels, preds,
    target_names=["QUERY", "CONFIG", "UNKNOWN"],
))


# ═══════════════════════════════════════════════════════════════════════════
# 8. 保存模型 + Tokenizer（server.py 从 OUTPUT_DIR 加载）
# ═══════════════════════════════════════════════════════════════════════════

trainer.save_model(OUTPUT_DIR)
tokenizer.save_pretrained(OUTPUT_DIR)
print(f"\n[完成] 模型已保存到 {OUTPUT_DIR}")
print("下一步：uvicorn server:app --host 0.0.0.0 --port 8000")
