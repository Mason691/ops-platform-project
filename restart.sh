#!/bin/bash
# 金地燃气聚合展示引流平台 重启脚本
# 用法：
#   ./restart.sh              重启后端
#   ./restart.sh rebuild       重新打包前后端后重启后端
#   ./restart.sh stop          停止后端
#   ./restart.sh dev           重启后端 + 启动前端 dev server (:3035)
#
# 后端：Spring Boot 3.2.5 / JDK 17 / 端口 8084
# 前端：uni-app H5 / Vite dev :3035 / 产物 frontend/dist/build/h5/

set -e
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$PROJECT_DIR/backend/target/ops-platform-1.0.0-SNAPSHOT.jar"

# Spring Boot 3.2.5 需要 JDK 17，系统默认为 Java 8
export JAVA_HOME=/home/jason/.local/jdk17
export PATH="$JAVA_HOME/bin:$PATH"

BACKEND_LOG=/tmp/ops-backend.log
FRONTEND_LOG=/tmp/ops-frontend.log
TARGET="${1:-all}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[ops]${NC} $*"; }
warn() { echo -e "${YELLOW}[ops]${NC} $*"; }
err()  { echo -e "${RED}[ops]${NC} $*"; }

# ── 打包函数 ─────────────────────────────────────────────────────
build_jar() {
    log "重新打包后端 jar..."
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -Dmaven.repo.local=/home/jason/m2_local -f backend/pom.xml -q
    log "打包完成: $(ls -lh "$JAR" | awk '{print $5, $9}')"
}

build_frontend_h5() {
    log "重新打包前端 H5..."
    export PATH="/home/jason/nodejs/bin:$PATH"
    cd "$PROJECT_DIR/frontend"
    [ -d node_modules ] || npm install --silent
    export NODE_OPTIONS='--max-old-space-size=512'
    npx uni build
    log "H5 打包完成 → $PROJECT_DIR/frontend/dist/build/h5/"
}

# ── 停止函数 ─────────────────────────────────────────────────────
stop_backend() {
    warn "停止后端 (端口 8084)..."
    if [ -f /tmp/ops-backend.pid ]; then
        local pid
        pid=$(cat /tmp/ops-backend.pid)
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            log "已发送 SIGTERM 给进程 $pid，等待优雅关闭..."
        fi
        rm -f /tmp/ops-backend.pid
    fi
    # 兜底：只杀占用 8084 端口的进程，不影响其他项目
    local i=0
    while lsof -ti:8084 >/dev/null 2>&1; do
        i=$((i+1))
        if [ $i -ge 35 ]; then
            err "端口 8084 仍被占用（超过 35s），强制 SIGKILL"
            lsof -ti:8084 | xargs kill -9 2>/dev/null || true
            break
        fi
        [ $i -eq 1 ] && lsof -ti:8084 | xargs kill 2>/dev/null || true
        sleep 1
    done
    log "后端已停止"
}

stop_frontend() {
    warn "停止前端 (端口 3035)..."
    if [ -f /tmp/ops-frontend.pid ]; then
        local pid
        pid=$(cat /tmp/ops-frontend.pid)
        kill -0 "$pid" 2>/dev/null && kill "$pid" && log "前端进程 $pid 已发送 SIGTERM"
        rm -f /tmp/ops-frontend.pid
    fi
    # 兜底：只杀占用 3035 端口的进程
    local i=0
    while lsof -ti:3035 >/dev/null 2>&1; do
        i=$((i+1))
        if [ $i -ge 10 ]; then
            err "端口 3035 仍被占用，强制 SIGKILL"
            lsof -ti:3035 | xargs kill -9 2>/dev/null || true
            break
        fi
        [ $i -eq 1 ] && lsof -ti:3035 | xargs kill 2>/dev/null || true
        sleep 1
    done
    log "前端已停止"
}

# ── 启动函数 ─────────────────────────────────────────────────────
start_backend() {
    log "启动后端 (端口 8084)..."

    # jar 不存在时自动打包
    if [ ! -f "$JAR" ]; then
        warn "jar 包不存在，先执行打包..."
        build_jar
    fi

    cd "$PROJECT_DIR"
    nohup "$JAVA_HOME/bin/java" \
        -Xms128m -Xmx256m \
        -jar "$JAR" \
        > "$BACKEND_LOG" 2>&1 &
    echo $! > /tmp/ops-backend.pid
    log "后端 PID: $(cat /tmp/ops-backend.pid)，日志: $BACKEND_LOG"

    # 等待健康检查通过（最多 90 秒）
    warn "等待后端就绪..."
    local i=0
    until curl -sf http://localhost:8084/actuator/health >/dev/null 2>&1; do
        i=$((i+1))
        if [ $i -ge 90 ]; then
            err "后端 90 秒内未就绪，查看日志: tail -f $BACKEND_LOG"
            return 1
        fi
        sleep 1
    done
    log "后端已就绪 ✓"
}

start_frontend() {
    log "启动前端 dev server (端口 3035)..."
    export PATH="/home/jason/nodejs/bin:$PATH"
    cd "$PROJECT_DIR/frontend"
    [ -d node_modules ] || npm install --silent
    nohup npx uni > "$FRONTEND_LOG" 2>&1 &
    echo $! > /tmp/ops-frontend.pid
    log "前端 PID: $(cat /tmp/ops-frontend.pid)，日志: $FRONTEND_LOG"

    warn "等待前端就绪..."
    local i=0
    until curl -sf http://localhost:3035 >/dev/null 2>&1; do
        i=$((i+1))
        if [ $i -ge 30 ]; then
            err "前端 30 秒内未就绪，查看日志: tail -f $FRONTEND_LOG"
            return 1
        fi
        sleep 1
    done
    log "前端已就绪 ✓"
}

# ── 主流程 ───────────────────────────────────────────────────────
echo "===== 燃气聚合平台 重启 ====="

case "$TARGET" in
    backend)
        stop_backend
        start_backend
        ;;
    frontend)
        stop_frontend
        start_frontend
        ;;
    build)
        build_frontend_h5
        build_jar
        ;;
    rebuild)
        stop_backend
        build_jar
        start_backend
        ;;
    stop)
        stop_backend
        stop_frontend
        ;;
    dev)
        stop_backend
        stop_frontend
        build_jar
        start_backend
        start_frontend
        ;;
    ""|all)
        stop_backend
        build_jar
        start_backend
        ;;
    *)
        err "未知参数: $TARGET"
        echo "用法: $0 [backend|frontend|all|build|rebuild|dev|stop]"
        exit 1
        ;;
esac

echo ""
echo "===== 重启完成 ====="
echo "后端健康: http://localhost:8084/actuator/health"
echo "H5 移动端: https://a.15891589.xyz/subsystem/ops/index.html"
echo "管理后台: https://a.15891589.xyz"
echo "日志:"
echo "  tail -f $BACKEND_LOG"
echo "  tail -f $FRONTEND_LOG"
