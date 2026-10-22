#!/bin/bash
#
# 下载 RAG 重排序（reranker）所需的 ONNX 模型
#
# 为什么需要单独下载：bge-reranker-v2-m3 的量化 ONNX 模型约 544MB，
# 超出 GitHub 单文件 100MB 限制，因此不纳入版本库，需本地下载后放到
# src/main/resources/model/bge-reranker-model/ 目录下。
#
# 用法：
#   ./scripts/download-reranker.sh
#   HF_ENDPOINT=https://huggingface.co ./scripts/download-reranker.sh   # 走官方源
#
set -euo pipefail

HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
REPO="onnx-community/bge-reranker-v2-m3-ONNX"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$ROOT_DIR/src/main/resources/model/bge-reranker-model"

# 预期文件大小下限（字节），用于校验下载是否完整
MIN_MODEL_BYTES=500000000
MIN_TOKENIZER_BYTES=10000000

mkdir -p "$DEST_DIR"

download() {
    local remote_path="$1"
    local file_name="$2"
    local target="$DEST_DIR/$file_name"

    if [ -f "$target" ]; then
        echo "[跳过] $file_name 已存在（$(du -h "$target" | cut -f1)）"
        return 0
    fi

    echo "[下载] $remote_path"
    echo "       来源 $HF_ENDPOINT/$REPO"
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
        -o "$target.part" \
        "$HF_ENDPOINT/$REPO/resolve/main/$remote_path"
    mv "$target.part" "$target"
    echo "[完成] $file_name（$(du -h "$target" | cut -f1)）"
}

download "onnx/model_quantized.onnx" "model_quantized.onnx"
download "tokenizer.json" "tokenizer.json"

model_size=$(wc -c < "$DEST_DIR/model_quantized.onnx" | tr -d ' ')
tokenizer_size=$(wc -c < "$DEST_DIR/tokenizer.json" | tr -d ' ')

if [ "$model_size" -lt "$MIN_MODEL_BYTES" ]; then
    echo "[错误] model_quantized.onnx 只有 ${model_size} 字节，疑似下载不完整" >&2
    exit 1
fi
if [ "$tokenizer_size" -lt "$MIN_TOKENIZER_BYTES" ]; then
    echo "[错误] tokenizer.json 只有 ${tokenizer_size} 字节，疑似下载不完整" >&2
    exit 1
fi

echo
echo "模型就绪：$DEST_DIR"
ls -lh "$DEST_DIR"
