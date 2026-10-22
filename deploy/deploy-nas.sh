#!/bin/bash
#
# KnowEngine NAS 部署脚本
#
# 用法：
#   ./deploy/deploy-nas.sh              # 全量：打包 + 上传 + 重建容器
#   ./deploy/deploy-nas.sh config       # 只更新配置并重启（不改 jar）
#   ./deploy/deploy-nas.sh recreate     # 只重建容器（改了启动参数/挂载时用）
#   ./deploy/deploy-nas.sh restart      # 只重启容器
#
# 前置：本机需已配置到 NAS 的免密登录（~/.ssh/id_ed25519），
#      并设置 JAVA_HOME 指向 JDK 21（Homebrew 默认 JDK 27 会让 Lombok 崩）。
#
# 部署产物在 NAS 上：
#   /vol1/llmmentor/know-engine/backend/know-engine-1.0.0.jar
#   /vol1/llmmentor/know-engine/backend/config/application.yml   （NAS 覆盖配置）
#   /vol1/llmmentor/know-engine/backend/logs/                    （应用日志）
#
set -euo pipefail

# 必须按实际环境设置：export NAS_HOST=user@<你的NAS地址>
NAS_HOST="${NAS_HOST:-user@your-nas-ip}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
CONTAINER="${CONTAINER:-llmentor-know-engine}"
REMOTE_DIR="${REMOTE_DIR:-/vol1/llmmentor/know-engine/backend}"
JAR_NAME="know-engine-1.0.0.jar"
PORT="${PORT:-8009}"
OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no"
SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=no"
ACTION="${1:-all}"

log() { printf '\n=== %s ===\n' "$1"; }

build_jar() {
    log "打包 know-engine"
    if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '21\.'; then
        export JAVA_HOME=/Users/zhangwenjie/Library/Java/JavaVirtualMachines/corretto-21.0.12.1-1/Contents/Home
    fi
    echo "JAVA_HOME=$JAVA_HOME"
    ( cd "$MODULE_DIR" && mvn clean package -DskipTests )
    ls -lh "$MODULE_DIR/target/$JAR_NAME"
}

upload() {
    log "上传 jar、配置与静态页"
    $SSH "$NAS_HOST" "mkdir -p $REMOTE_DIR/logs $REMOTE_DIR/config $REMOTE_DIR/static $REMOTE_DIR/model && chmod 755 $REMOTE_DIR $REMOTE_DIR/logs $REMOTE_DIR/config $REMOTE_DIR/static $REMOTE_DIR/model"
    ensure_model
    $SCP "$MODULE_DIR/target/$JAR_NAME" "$NAS_HOST:$REMOTE_DIR/$JAR_NAME"
    $SCP "$MODULE_DIR/deploy/application-nas.yml" "$NAS_HOST:$REMOTE_DIR/config/application.yml"
    # 静态页外挂：容器内 STATIC_LOCATIONS 让 /app/static 覆盖 jar 内版本，
    # 好处是后续只改 HTML 时 scp 单文件即可生效，不用重打 jar。
    $SCP "$MODULE_DIR"/src/main/resources/static/*.html "$NAS_HOST:$REMOTE_DIR/static/"
}

ensure_model() {
    log "检查 NAS 上的 reranker 模型"
    $SSH "$NAS_HOST" "
        if [ -f $REMOTE_DIR/model/model_quantized.onnx ] && [ -f $REMOTE_DIR/model/tokenizer.json ]; then
            echo '模型已就绪'; ls -lh $REMOTE_DIR/model | tail -2
        else
            # 模型 544MB，从旧 jar 里解出来最快（同机磁盘操作，不走网络）
            src=\$(ls -S $REMOTE_DIR/*.jar 2>/dev/null | head -1)
            if [ -n \"\$src\" ] && unzip -l \"\$src\" 2>/dev/null | grep -q 'bge-reranker-model/model_quantized.onnx'; then
                echo \"从 \$src 解出模型\"
                cd /tmp && rm -rf bge_extract && mkdir bge_extract
                unzip -q -o \"\$src\" 'BOOT-INF/classes/model/bge-reranker-model/*' -d bge_extract
                cp bge_extract/BOOT-INF/classes/model/bge-reranker-model/* $REMOTE_DIR/model/
                rm -rf bge_extract
                ls -lh $REMOTE_DIR/model
            else
                echo '!! 未找到模型文件，请在本机执行 scripts/download-reranker.sh 后，'
                echo '   scp src/main/resources/model/bge-reranker-model/* \$NAS:$REMOTE_DIR/model/'
                echo '   （缺失时服务仍可启动，仅退化为不做 rerank）'
            fi
        fi
    "
}

run_container() {
    log "重建容器 $CONTAINER"
    $SSH "$NAS_HOST" "
        docker rm -f $CONTAINER 2>/dev/null || true
        docker run -d --name $CONTAINER \
          --network host --restart always \
          -v $REMOTE_DIR/$JAR_NAME:/app/app.jar \
          -v $REMOTE_DIR/config:/app/config:ro \
          -v $REMOTE_DIR/logs:/app/logs \
          -v $REMOTE_DIR/static:/app/static \
          -v $REMOTE_DIR/model:/app/model:ro \
          -e STATIC_LOCATIONS='file:/app/static/,classpath:/static/' \
          -e RERANKER_MODEL_PATH=/app/model/model_quantized.onnx \
          -e RERANKER_TOKENIZER_PATH=/app/model/tokenizer.json \
          -e OLLAMA_BASE_URL=$OLLAMA_URL \
          -e OLLAMA_CHAT_MODEL=qwen3.8:27b-mlx \
          -e OLLAMA_EMBEDDING_MODEL=bge-m3 \
          -e SERVER_PORT=$PORT \
          -e LOGGING_FILE_NAME=/app/logs/know-engine.log \
          -e TZ=Asia/Shanghai \
          -e JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8' \
          eclipse-temurin:21-jre \
          java -Xms512m -Xmx2048m -jar /app/app.jar \
               --spring.config.additional-location=file:/app/config/,file:/app/config/secret/
    "
}

health_check() {
    log "等待应用就绪（最多 120s）"
    for i in $(seq 1 24); do
        code=$($SSH "$NAS_HOST" "curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$PORT/login.html" 2>/dev/null || echo 000)
        restarts=$($SSH "$NAS_HOST" "docker inspect $CONTAINER --format '{{.RestartCount}}'" 2>/dev/null || echo '?')
        echo "第 $i 次: HTTP=$code 重启次数=$restarts"
        if [ "$code" = "200" ]; then
            log "部署成功：http://${NAS_HOST#*@}:$PORT/login.html"
            return 0
        fi
        sleep 5
    done
    echo "启动超时，最近日志："
    $SSH "$NAS_HOST" "docker logs --tail 40 $CONTAINER"
    return 1
}

case "$ACTION" in
    all)
        build_jar
        upload
        run_container
        health_check
        ;;
    config)
        $SCP "$MODULE_DIR/deploy/application-nas.yml" "$NAS_HOST:$REMOTE_DIR/config/application.yml"
        $SSH "$NAS_HOST" "docker restart $CONTAINER"
        health_check
        ;;
    restart)
        $SSH "$NAS_HOST" "docker restart $CONTAINER"
        health_check
        ;;
    recreate)
        # 只重建容器（改了启动参数/挂载时用），不重新打包上传
        run_container
        health_check
        ;;
    *)
        echo "未知参数：$ACTION（可选 all | config | restart）"
        exit 1
        ;;
esac
