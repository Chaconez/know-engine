#!/usr/bin/env bash
# =============================================================================
# KnowEngine 演示知识库一键导入
#
# 做的事情：
#   1. 用员工账号登录，拿到 sa-token Cookie
#   2. 逐个上传 demo/knowledge/*.md（DOCUMENT_SEARCH 类型）
#   3. 上传成功后立即触发 SMART 切片，切片完成由事件驱动自动向量化
#
# 用法：
#   ./import-knowledge.sh                          # 默认 http://localhost:8009
#   BASE_URL=http://192.168.3.15:8009 ./import-knowledge.sh
#   CHUNK_SIZE=400 ./import-knowledge.sh           # 调整分段长度
#
# 前置条件：
#   - 员工账号 TEST001 / 123456 已存在（见 demo/sql/demo-data.sql）
#   - MinIO、Elasticsearch、Ollama(bge-m3) 已就绪
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8009}"
EMP_ID="${EMP_ID:-TEST001}"
EMP_PWD="${EMP_PWD:-123456}"
CHUNK_SIZE="${CHUNK_SIZE:-300}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOWLEDGE_DIR="$SCRIPT_DIR/knowledge"
COOKIE_JAR="$(mktemp -t knowengine-cookie.XXXXXX)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# ---------------------------------------------------------------- 0. 环境检查
if ! command -v curl >/dev/null 2>&1; then
  echo "✗ 未找到 curl，请先安装" >&2
  exit 1
fi
if [ ! -d "$KNOWLEDGE_DIR" ]; then
  echo "✗ 未找到知识库目录：$KNOWLEDGE_DIR" >&2
  exit 1
fi

echo "=============================================="
echo " KnowEngine 演示知识库导入"
echo " 服务地址：$BASE_URL"
echo " 分段长度：$CHUNK_SIZE"
echo "=============================================="

# ---------------------------------------------------------------- 1. 员工登录
echo ""
echo "[1/3] 员工登录 $EMP_ID ..."
login_resp="$(curl -sS -X POST "$BASE_URL/auth/staffLogin" \
  -H 'Content-Type: application/json' \
  -c "$COOKIE_JAR" \
  -d "{\"empId\":\"$EMP_ID\",\"password\":\"$EMP_PWD\"}" 2>&1)"

if ! grep -q '"code":200' <<<"$login_resp"; then
  echo "✗ 登录失败，返回：$login_resp" >&2
  echo "  请确认员工账号是否存在，或服务地址是否正确。" >&2
  exit 1
fi
if ! grep -q 'satoken' "$COOKIE_JAR"; then
  echo "✗ 未拿到 satoken Cookie，请检查 sa-token 配置" >&2
  exit 1
fi
echo "  ✓ 登录成功"

# ---------------------------------------------------------------- 2. 上传文档
# 格式：文件名|文档标题|文档描述|可见范围
DOC_LIST=$(cat <<'EOF'
01-tech-guide-x5-cockpit.md|星驰 X5 智能座舱与仪表警示灯使用指南|仪表警示灯分级处置、中控屏与语音操作、辅助驾驶边界、冬季用车建议|VISITOR
02-after-sales-warranty.md|星驰汽车售后服务与三包政策（2025版）|三包有效期、整车与三电质保、保养周期价格、代步车、道路救援|VISITOR
03-presales-buying-guide.md|星驰 S3 X5 M7 选购指南与车型对比|三款车型定位、动力形式取舍、空间对比、金融方案与落地价|VISITOR
04-complaint-handling.md|星驰汽车客户投诉与争议处理规范|受理渠道时限、投诉分级、质量争议流程、退换车与补偿费|VISITOR
05-charging-guide.md|星驰充电与补能服务指南|家充桩安装、公共超充、充电故障自查、电池养护、充电费用|VISITOR
06-owner-benefits.md|星驰车主会员权益与老客推荐礼遇（2025版）|会员等级、首任车主终身质保、推荐礼遇、积分兑换规则|OWNER
EOF
)

echo ""
echo "[2/3] 上传并切片知识文档 ..."
ok_count=0
skip_count=0
fail_count=0
doc_ids=""

while IFS='|' read -r fname title desc accessible; do
  [ -z "${fname:-}" ] && continue
  fpath="$KNOWLEDGE_DIR/$fname"
  if [ ! -f "$fpath" ]; then
    echo "  ✗ 文件不存在：$fname"
    fail_count=$((fail_count + 1))
    continue
  fi

  # 上传（返回体 + HTTP 状态码）
  resp="$(curl -sS -X POST "$BASE_URL/api/document/upload" \
    -b "$COOKIE_JAR" \
    -F "file=@$fpath;type=text/markdown" \
    -F "title=$title" \
    -F "description=$desc" \
    -F "knowledgeBaseType=DOCUMENT_SEARCH" \
    -F "accessibleBy=$accessible" \
    -F "version=1.0.0" \
    -w $'\n%{http_code}' 2>&1)"

  http_code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"

  if [ "$http_code" = "409" ]; then
    echo "  - 跳过（内容已存在）：$title"
    skip_count=$((skip_count + 1))
    continue
  fi
  if [ "$http_code" != "200" ]; then
    echo "  ✗ 上传失败 [$http_code]：$title"
    echo "    $body"
    fail_count=$((fail_count + 1))
    continue
  fi

  doc_id="$(printf '%s' "$body" | sed -n 's/.*"docId"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  if [ -z "$doc_id" ]; then
    echo "  ✗ 未解析到 docId，返回：$body"
    fail_count=$((fail_count + 1))
    continue
  fi

  # 触发切片（SMART = 按 Markdown 标题父子分段 + 超长二次切分）
  split_resp="$(curl -sS -X POST \
    "$BASE_URL/api/document/split/$doc_id?splitType=SMART&chunkSize=$CHUNK_SIZE" \
    -b "$COOKIE_JAR" -w $'\n%{http_code}' 2>&1)"
  split_code="${split_resp##*$'\n'}"
  seg_count="${split_resp%$'\n'*}"

  if [ "$split_code" = "200" ]; then
    echo "  ✓ 已入库 docId=${doc_id}，切片 ${seg_count} 段：$title"
    ok_count=$((ok_count + 1))
    doc_ids="$doc_ids $doc_id"
  else
    echo "  ! 文档已上传但切片失败 [$split_code]：$title"
    fail_count=$((fail_count + 1))
  fi
done <<<"$DOC_LIST"

# ---------------------------------------------------------------- 3. 汇总
echo ""
echo "[3/3] 导入结果"
echo "  成功：${ok_count}　跳过：${skip_count}　失败：${fail_count}"
if [ -n "$doc_ids" ]; then
  echo "  新增文档 ID：$doc_ids"
fi
echo ""
echo "提示：向量化由事件驱动异步执行，约 10~60 秒后可开始提问。"
echo "     查看状态：员工登录后访问 $BASE_URL/document.html"
