#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# 金地燃气聚合展示引流平台 — 重启脚本
#
# 用法：
#   ./restart.sh              # 仅重启后端
#   ./restart.sh --frontend   # 重启后端 + 重新构建前端 H5
#   ./restart.sh --stop       # 仅停止所有服务
#
# 后端端口：8084
# H5 产物： frontend/dist/build/h5/ → Nginx /subsystem/ops/
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

# ── 环境变量 ──────────────────────────────────────────────────────────────────
export JAVA_HOME="${JAVA_HOME:-/home/jason/.local/jdk17}"
NODE_BIN="${NODE_BIN:-/home/jason/nodejs/bin}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"
LOG_FILE="$PROJECT_DIR/app.log"
JAR_FILE="$BACKEND_DIR/target/ops-platform-1.0.0-SNAPSHOT.jar"
BACKEND_PORT=8084

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[✔]${NC} $*"; }
warn() { echo -e "${YELLOW}[~]${NC} $*"; }
die()  { echo -e "${RED}[✘]${NC} $*" >&2; exit 1; }

BUILD_FRONTEND=false
case "${1:-}" in
  --frontend|-f) BUILD_FRONTEND=true ;;
  --stop|-s)
    warn "停止所有 ops-platform 进程..."
    pkill -f "ops-platform.*jar" 2>/dev/null && ok "后端已停止" || warn "后端未运行"
    pkill -f "tail -f /tmp/ops-platform.log" 2>/dev/null || true
    ok "已停止"
    exit 0
    ;;
esac

# ── 1. 检查环境 ──────────────────────────────────────────────────────────────
echo ""
warn "检查运行环境..."

[ -x "$JAVA_HOME/bin/java" ] || die "JDK 17 未找到：$JAVA_HOME"
JAVA_VER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
ok "JDK：$JAVA_VER"

[ -x /usr/bin/mvn ] || die "Maven 未安装"
ok "Maven：$(/usr/bin/mvn -v 2>&1 | head -1)"

# ── 2. 停止旧进程 ────────────────────────────────────────────────────────────
echo ""
warn "停止旧进程..."

if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
    kill "$(lsof -ti:$BACKEND_PORT)" 2>/dev/null || true
    sleep 2
    # 强行释放
    lsof -ti:$BACKEND_PORT 2>/dev/null | xargs -r kill -9 2>/dev/null || true
    ok "后端已停止（端口 $BACKEND_PORT）"
else
    warn "后端未运行"
fi

# ── 3. 拉取最新代码 ──────────────────────────────────────────────────────────
echo ""
warn "拉取最新代码..."
cd "$PROJECT_DIR"
git pull origin main 2>&1 | tail -1
ok "代码已更新"

# ── 4. 编译后端 ──────────────────────────────────────────────────────────────
echo ""
warn "编译后端（Maven package，跳过测试）..."

cd "$BACKEND_DIR"
"$JAVA_HOME/bin/java" -version 2>&1 > /dev/null  # ensure JAVA_HOME sticks
/usr/bin/mvn clean package -DskipTests -q >> "$LOG_FILE" 2>&1 \
    || die "后端编译失败，请查看日志：tail -100 $LOG_FILE"

[ -f "$JAR_FILE" ] || die "JAR 文件未生成：$JAR_FILE"
ok "后端编译完成"

# ── 5. 编译前端（可选）────────────────────────────────────────────────────────
if $BUILD_FRONTEND; then
    echo ""
    warn "编译前端（uni-app H5）..."

    [ -d "$NODE_BIN" ] || die "Node.js 未找到：$NODE_BIN（请先安装）"
    export PATH="$NODE_BIN:$PATH"

    cd "$FRONTEND_DIR"
    [ -d node_modules ] || (warn "安装前端依赖..." && npm install --silent)

    export NODE_OPTIONS='--max-old-space-size=512'
    npx uni build >> "$LOG_FILE" 2>&1 \
        || die "前端编译失败，请查看日志：tail -50 $LOG_FILE"

    H5_DIR="$FRONTEND_DIR/dist/build/h5"
    [ -f "$H5_DIR/index.html" ] || die "H5 产物缺失：$H5_DIR/index.html"
    ok "前端编译完成 → $H5_DIR"

    echo ""
    warn "提示：H5 静态文件已更新，Nginx 无需重启（alias 指向 dist/build/h5/）。"
fi

# ── 6. 启动后端 ──────────────────────────────────────────────────────────────
echo ""
warn "启动后端（端口 $BACKEND_PORT）..."

# 清空旧日志
:> "$LOG_FILE"

nohup "$JAVA_HOME/bin/java" \
    -Xms128m -Xmx256m \
    -jar "$JAR_FILE" \
    --server.port="$BACKEND_PORT" \
    >> "$LOG_FILE" 2>&1 &
BACKEND_PID=$!

ok "后端已启动（PID: $BACKEND_PID）"

# ── 7. 健康检查 ──────────────────────────────────────────────────────────────
echo ""
warn "等待后端就绪（最多 60 秒）..."

for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
        ok "后端已就绪（${i}×2s）"
        break
    fi
    if ! ps -p "$BACKEND_PID" >/dev/null 2>&1; then
        die "后端进程意外退出，请查看日志：tail -50 $LOG_FILE"
    fi
    [ "$i" -eq 30 ] && die "后端 60 秒内未就绪，请查看日志：tail -50 $LOG_FILE"
    sleep 2
done

# ── 8. 完成 ──────────────────────────────────────────────────────────────────
echo ""
ok "重启完成"
echo "  后端端口：  $BACKEND_PORT"
echo "  健康检查：  http://127.0.0.1:$BACKEND_PORT/actuator/health"
echo "  H5 访问：   https://a.15891589.xyz/subsystem/ops/index.html"
echo "  管理后台：  https://a.15891589.xyz"
echo ""
echo "  实时日志：  tail -f $LOG_FILE"
echo ""
