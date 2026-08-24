#!/bin/bash
# 金地燃气聚合展示引流平台 重启脚本
# 用法：
#   ./restart.sh              重启后端
#   ./restart.sh rebuild       重新打包后重启后端
#   ./restart.sh dev           重启后端 + 启动前端 dev server (:3035)
#   ./restart.sh stop          停止所有服务
#
# 后端：Spring Boot 3.2.5 / JDK 17 / 端口 8084
# 前端：uni-app H5 / Vite dev :3035
set -e
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$PROJECT_DIR/backend/target/ops-platform-1.0.0-SNAPSHOT.jar"

export JAVA_HOME=/home/jason/jdk17
export PATH="$JAVA_HOME/bin:/home/jason/apache-maven-3.9.9/bin:/home/jason/.nvm/versions/node/v24.18.0/bin:$PATH"

BACKEND_LOG=/tmp/ops-backend.log
FRONTEND_LOG=/tmp/ops-frontend.log
TARGET="${1:-all}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[ops]${NC} $*"; }
warn() { echo -e "${YELLOW}[ops]${NC} $*"; }
err()  { echo -e "${RED}[ops]${NC} $*"; }

# ── 打包 ───────────────────────────────────────────────────────────
build_jar() {
    log "重新打包后端 jar..."
    cd "$PROJECT_DIR/backend"
    mvn clean package -DskipTests -q
    log "打包完成: $(ls -lh "$JAR" | awk '{print $5, $9}')"
}

build_frontend_h5() {
    log "重新打包前端 H5..."
    cd "$PROJECT_DIR/frontend"
    [ -d node_modules ] || npm install --silent
    npx uni build
    curl -sL 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js' \
        -o "$PROJECT_DIR/frontend/dist/build/h5/jweixin-1.6.0.js"
    log "H5 打包完成 → $PROJECT_DIR/frontend/dist/build/h5/"
}

# ── 停止 ───────────────────────────────────────────────────────────
stop_backend() {
    warn "停止后端 (端口 8084)..."
    if [ -f /tmp/ops-backend.pid ]; then
        local pid; pid=$(cat /tmp/ops-backend.pid)
        kill -0 "$pid" 2>/dev/null && kill "$pid" && log "已发送 SIGTERM 给 $pid"
        rm -f /tmp/ops-backend.pid
    fi
    local i=0
    while lsof -ti:8084 >/dev/null 2>&1; do
        i=$((i+1))
        if [ $i -ge 35 ]; then
            err "端口 8084 仍被占用，强制 SIGKILL"
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
        local pid; pid=$(cat /tmp/ops-frontend.pid)
        kill -0 "$pid" 2>/dev/null && kill "$pid" && log "前端进程 $pid 已停止"
        rm -f /tmp/ops-frontend.pid
    fi
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

# ── 启动 ───────────────────────────────────────────────────────────
start_backend() {
    log "启动后端 (端口 8084)..."
    if [ ! -f "$JAR" ]; then
        warn "jar 包不存在，先打包..."
        build_jar
    fi
    cd "$PROJECT_DIR"
    nohup "$JAVA_HOME/bin/java" -Xms128m -Xmx256m -jar "$JAR" \
        > "$BACKEND_LOG" 2>&1 &
    echo $! > /tmp/ops-backend.pid
    log "后端 PID: $(cat /tmp/ops-backend.pid)，日志: $BACKEND_LOG"

    warn "等待后端就绪..."
    local i=0
    until curl -sf http://localhost:8084/actuator/health >/dev/null 2>&1; do
        i=$((i+1))
        [ $i -ge 90 ] && err "后端 90 秒内未就绪: tail -f $BACKEND_LOG" && return 1
        sleep 1
    done
    log "后端已就绪 ✓"
}

start_frontend() {
    log "启动前端 dev server (端口 3035)..."
    cd "$PROJECT_DIR/frontend"
    [ -d node_modules ] || npm install --silent
    nohup npm run dev:h5 > "$FRONTEND_LOG" 2>&1 &
    echo $! > /tmp/ops-frontend.pid
    log "前端 PID: $(cat /tmp/ops-frontend.pid)，日志: $FRONTEND_LOG"

    warn "等待前端就绪..."
    local i=0
    until curl -sf http://localhost:3035 >/dev/null 2>&1; do
        i=$((i+1))
        [ $i -ge 30 ] && err "前端 30 秒内未就绪: tail -f $FRONTEND_LOG" && return 1
        sleep 1
    done
    log "前端已就绪 ✓"
}

# ── 主流程 ─────────────────────────────────────────────────────────
echo "===== 燃气聚合平台 重启 ====="

case "$TARGET" in
    backend)  stop_backend && start_backend ;;
    frontend) stop_frontend && start_frontend ;;
    build)    build_frontend_h5 && build_jar ;;
    rebuild)  stop_backend && build_jar && start_backend ;;
    dev)      stop_backend; stop_frontend; build_jar; start_backend; start_frontend ;;
    stop)     stop_backend; stop_frontend ;;
    all)      stop_backend; build_jar; start_backend ;;
    *)
        err "未知参数: $TARGET"
        echo "用法: $0 [backend|frontend|all|build|rebuild|dev|stop]"
        exit 1 ;;
esac

echo ""
echo "===== 重启完成 ====="
echo "后端健康: http://localhost:8084/actuator/health"
echo "H5 移动端: https://a.15891589.xyz/subsystem/ops/index.html"
echo "管理后台: https://a.15891589.xyz"
echo "日志:"
echo "  tail -f $BACKEND_LOG"
echo "  tail -f $FRONTEND_LOG"
