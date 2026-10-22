# 本地 Reranker 模型目录

仓库不含模型二进制文件（`model_quantized.onnx` 约 540MB，超过 GitHub 单文件 100MB 限制，已在 `.gitignore` 中排除）。

如需启用本地 ONNX Reranker：

1. 从 HuggingFace 下载 [BAAI/bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) 的 ONNX 量化版本（推荐 [onnx 社区转换版](https://huggingface.co/Xenova/bge-reranker-v2-m3) 中的量化模型）
2. 将模型重命名为 `model_quantized.onnx` 放入本目录，同时放入对应的 `tokenizer.json`

```
src/main/resources/model/bge-reranker-model/
├── model_quantized.onnx   # ~540MB
└── tokenizer.json         # ~16MB
```

未放置模型文件不影响其余功能编译与运行。
