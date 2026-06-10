#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# 金地燃气聚合展示引流平台 — 前后端重启脚本
#
# 用法：
#   ./restart.sh              # 停后端 → 编译 → 启动后端
#   ./restart.sh rebuild      # 停前后端 → 编译前后端 → 启动前后端
#   ./restart.sh --stop       # 停前后端
#   ./restart.sh --dev        # 停前后端 → 编译后端 → 启动后端 + 前端 dev server
#
# 后端：Spring Boot 3.2.5 / JDK 17 / 端口 8084
# 前端：uni-app H5 / Vite dev :3035 / 产物 dist/build/h5/
# ═══════════════════════════════════════════════════════════════════════════════

set -eu

# ── 环境变量 ──────────────────────────────────────────────────────────────────
# JDK 17 放 PATH 最前面，确保 mvn / java / jar 全部使用 JDK 17（系统默认是 1.8）
export JAVA_HOME="${JAVA_HOME:-/home/jason/.local/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
NODE_BIN="${NODE_BIN:-/home/jason/nodejs/bin}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"
LOG_FILE="$PROJECT_DIR/app.log"
FE_LOG_FILE="$PROJECT_DIR/frontend.log"
JAR_FILE="$BACKEND_DIR/target/ops-platform-1.0.0-SNAPSHOT.jar"
BACKEND_PORT=8084
FRONTEND_PORT=3035

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[✔]${NC} $*"; }
warn() { echo -e "${YELLOW}[~]${NC} $*"; }
die()  { echo -e "${RED}[✘]${NC} $*" >&2; exit 1; }

ACTION="backend"
case "${1:-}" in
  rebuild)
    ACTION="rebuild"
    ;;
  --stop|-s)
    ACTION="stop"
    ;;
  --dev|-d)
    ACTION="dev"
    ;;
  --help|-h)
    echo "用法: $0 [rebuild|--stop|--dev]"
    echo "  (无参数)   停后端 → 编译后端 → 启动后端"
    echo "  rebuild    停前后端 → 编译前后端 → 启动后端（前端由 Nginx 托管）"
    echo "  --stop     停前后端"
    echo "  --dev      停前后端 → 编译后端 → 启动后端 + 前端 Vite dev server"
    exit 0
    ;;
esac

# ── 检查环境 ──────────────────────────────────────────────────────────────────
check_env() {
    echo ""
    warn "检查运行环境..."

    java -version >/dev/null 2>&1 || die "JDK 17 未找到：$JAVA_HOME"
    JAVA_VER=$(java -version 2>&1 | head -1)
    [[ "$JAVA_VER" == *"17."* ]] || die "需要 JDK 17，当前：$JAVA_VER"
    ok "JDK：$JAVA_VER"

    [ -x /usr/bin/mvn ] || die "Maven 未安装"
    ok "Maven：$(/usr/bin/mvn -v 2>&1 | head -1)"

    if [ "$ACTION" != "backend" ]; then
        [ -d "$NODE_BIN" ] || die "Node.js 未找到：$NODE_BIN（请先安装）"
        export PATH="$NODE_BIN:$PATH"
        ok "Node：$(node -v) / npm $(npm -v)"
    fi
}

# ── 停止所有服务 ──────────────────────────────────────────────────────────────
stop_all() {
    echo ""
    warn "停止所有服务..."

    # 停止后端
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        kill "$(lsof -ti:$BACKEND_PORT)" 2>/dev/null || true
        sleep 2
        lsof -ti:$BACKEND_PORT 2>/dev/null | xargs -r kill -9 2>/dev/null || true
        ok "后端已停止（端口 $BACKEND_PORT）"
    else
        warn "后端未运行"
    fi

    # 停止前端 dev server
    if lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        kill "$(lsof -ti:$FRONTEND_PORT)" 2>/dev/null || true
        sleep 1
        lsof -ti:$FRONTEND_PORT 2>/dev/null | xargs -r kill -9 2>/dev/null || true
        ok "前端 dev server 已停止（端口 $FRONTEND_PORT）"
    else
        warn "前端 dev server 未运行"
    fi
}

# ── 拉取代码 ──────────────────────────────────────────────────────────────────
pull_code() {
    echo ""
    warn "拉取最新代码..."
    cd "$PROJECT_DIR"
    if git pull origin main >/dev/null 2>&1; then
        ok "代码已更新"
    else
        warn "代码拉取失败，使用本地代码继续"
    fi
}

# ── 编译后端 ──────────────────────────────────────────────────────────────────
build_backend() {
    echo ""
    warn "编译后端（Maven package，跳过测试）..."

    cd "$BACKEND_DIR"
    /usr/bin/mvn clean package -DskipTests -q >> "$LOG_FILE" 2>&1 \
        || die "后端编译失败，请查看日志：tail -100 $LOG_FILE"

    [ -f "$JAR_FILE" ] || die "JAR 文件未生成：$JAR_FILE"
    ok "后端编译完成  →  $(du -sh "$JAR_FILE" | cut -f1)"
}

# ── 编译前端 ──────────────────────────────────────────────────────────────────
build_frontend() {
    echo ""
    warn "编译前端（uni-app H5）..."

    export PATH="$NODE_BIN:$PATH"

    cd "$FRONTEND_DIR"
    [ -d node_modules ] || { warn "安装前端依赖..." && npm install --silent; }

    export NODE_OPTIONS='--max-old-space-size=512'
    npx uni build >> "$FE_LOG_FILE" 2>&1 \
        || die "前端编译失败，请查看日志：tail -50 $FE_LOG_FILE"

    H5_DIR="$FRONTEND_DIR/dist/build/h5"
    [ -f "$H5_DIR/index.html" ] || die "H5 产物缺失：$H5_DIR/index.html"
    ok "前端编译完成  →  $H5_DIR"
    ok "Nginx 无需重启（alias → dist/build/h5/）"
}

# ── 启动后端 ──────────────────────────────────────────────────────────────────
start_backend() {
    echo ""
    warn "启动后端（端口 $BACKEND_PORT）..."

    # 确保端口已释放
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        warn "端口 $BACKEND_PORT 仍被占用，强制释放..."
        lsof -ti:$BACKEND_PORT | xargs -r kill -9 2>/dev/null || true
        sleep 2
    fi

    :> "$LOG_FILE"

    nohup java \
        -Xms128m -Xmx256m \
        -jar "$JAR_FILE" \
        --server.port="$BACKEND_PORT" \
        >> "$LOG_FILE" 2>&1 &
    BACKEND_PID=$!

    ok "后端已启动（PID: $BACKEND_PID）"
}

# ── 启动前端 dev server ──────────────────────────────────────────────────────
start_frontend_dev() {
    echo ""
    warn "启动前端 Vite dev server（端口 $FRONTEND_PORT）..."

    if lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        warn "端口 $FRONTEND_PORT 仍被占用，强制释放..."
        lsof -ti:$FRONTEND_PORT | xargs -r kill -9 2>/dev/null || true
        sleep 1
    fi

    export PATH="$NODE_BIN:$PATH"

    cd "$FRONTEND_DIR"
    [ -d node_modules ] || { warn "安装前端依赖..." && npm install --silent; }

    :> "$FE_LOG_FILE"

    nohup npx uni >> "$FE_LOG_FILE" 2>&1 &
    FE_PID=$!

    ok "前端 dev server 已启动（PID: $FE_PID, 端口 $FRONTEND_PORT）"
}

# ── 等待后端就绪 ──────────────────────────────────────────────────────────────
wait_backend() {
    echo ""
    warn "等待后端就绪（最多 60 秒）..."

    for i in $(seq 1 30); do
        if curl -sf "http://127.0.0.1:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
            ok "后端已就绪（${i}×2s）"
            return 0
        fi
        if ! ps -p "$BACKEND_PID" >/dev/null 2>&1; then
            die "后端进程意外退出，请查看日志：tail -50 $LOG_FILE"
        fi
        [ "$i" -eq 30 ] && die "后端 60 秒内未就绪，请查看日志：tail -50 $LOG_FILE"
        sleep 2
    done
}

# ── 摘要 ──────────────────────────────────────────────────────────────────────
summary() {
    echo ""
    echo "════════════════════════════════════════════════════"
    ok "重启完成"
    echo ""
    echo "  后端 API       http://127.0.0.1:$BACKEND_PORT"
    echo "  健康检查       http://127.0.0.1:$BACKEND_PORT/actuator/health"
    echo "  管理后台       https://a.15891589.xyz"
    echo "  H5 移动端      https://a.15891589.xyz/subsystem/ops/index.html"
    if [ "$ACTION" = "dev" ]; then
        echo "  前端 dev       http://127.0.0.1:$FRONTEND_PORT"
    fi
    echo ""
    echo "  后端日志       tail -f $LOG_FILE"
    [ "$ACTION" != "backend" ] && echo "  前端日志       tail -f $FE_LOG_FILE"
    echo "════════════════════════════════════════════════════"
    echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
# 主流程
# ═══════════════════════════════════════════════════════════════════════════════

if [ "$ACTION" = "stop" ]; then
    stop_all
    ok "已停止"
    exit 0
fi

check_env

case "$ACTION" in
  backend)
    stop_all
    pull_code
    build_backend
    start_backend
    wait_backend
    ;;
  rebuild)
    stop_all
    pull_code
    build_frontend
    build_backend
    start_backend
    wait_backend
    ;;
  dev)
    stop_all
    pull_code
    build_backend
    start_backend
    wait_backend
    start_frontend_dev
    ;;
esac

summary
