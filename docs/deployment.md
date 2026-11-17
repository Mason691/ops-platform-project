# 生产部署与备份

> 从 `CLAUDE.md` 拆分（2026-11-16）。前端部署目标与 nginx 白名单是强制规则，`CLAUDE.md` 中保留摘要。

---

## 生产部署（Docker）

生产服务器 `113.250.60.41`（root），部署目录 `/data2/boss-platform/`。**始终本地构建，上传到远程。**

```bash
# 后端
cd backend && JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn clean package -DskipTests -o
# JAR → /data2/boss-platform/ops-backend/app.jar
# docker build -t boss-platform/ops:latest ./ops-backend/
# docker compose up -d ops-backend
# Dockerfile 包含 HEALTHCHECK（30s 间隔，GET /actuator/health），Python3 + openpyxl 用于 TSV→XLSX 转换

# 前端 —— 两套前端独立，改哪边就部署哪边（见下表）
cd frontend && npm run build:h5        # ops H5 → /data2/boss-platform/html/ops/
```

**只换 JAR 时用 overlay 构建（推荐）**：不要重跑完整 Dockerfile——基础镜像/pip 依赖层缓存一旦失效，`docker build` 可能卡 20+ 分钟。新建 `Dockerfile.overlay`：`FROM boss-platform/ops:latest` + `COPY app.jar /app/app.jar`，构建前先把当前镜像 retag 为 `boss-platform/ops:rollback-<日期>` 供回滚，然后 `docker build -f Dockerfile.overlay -t boss-platform/ops:latest ./build-ctx && docker compose up -d ops-backend`——秒级发布，保留 Python 依赖、ENTRYPOINT、HEALTHCHECK。若依赖或基础镜像本身变更，必须走完整构建。

**前端部署目标（勿混）**：

| 改动位置 | 上传目标 | 生效方式 |
|---|---|---|
| `~/ops-platform-project/frontend/`（C 端/员工端 H5，hash 路由） | `/data2/boss-platform/html/ops/` | `boss-ops-nginx`(9083) |
| `~/ai-native-platform/frontend/src/views/ops/`（管理后台页） | `/data2/boss-platform/html/platform/` | `docker exec boss-platform-nginx nginx -s reload` |

**新增 `/ops/xxx` 管理页必须同步加 host nginx 白名单**：两套前端共用 `/ops/` 前缀，host nginx（`/data2/nginx/conf/conf.d/boss.conf`）以**逐路由白名单**把管理页路由到主站 9081，未列入者落入 catch-all 被送到 Ops H5 —— 用户刷新管理页会看到公众号移动端。加一行 `location ^~ /ops/新路径 { proxy_pass http://172.17.0.1:9081; proxy_http_version 1.1; proxy_set_header Host $host; proxy_read_timeout 300s; proxy_buffering off; }` 后执行 `docker exec nginx nginx -t && docker exec nginx nginx -s reload`。校验：curl 该路径看 title——主站=「协同平台」，H5=「金地美家」。

生产 Docker 关键参数：
- ops-backend: `SERVER_PORT=8082`, `network_mode: host`
- Volume: `/data2/boss-platform/uploads:/app/uploads`
- `PLATFORM_BASE_URL=http://localhost:8080`（host 网络直连平台）
- `MALL_SERVICE_BASE_URL=http://localhost:8083`
- 不再需要 `PAYMENT_QR_BASE_DIR` / 收款码持久卷（2026-11-17 起改为 jar 内置 + ECDSA 验签；旧卷可在发布后删除）

生产 Nginx：`/ops/` → `boss-ops-nginx:80`，`/api/ops/**` → `ops-backend:8082`

### 收款码上线检查（强制）

收款码图片与签名打包在 ops jar 的 `secure/qr/` 下（**不是 static 目录**），后台无上传入口；员工只能经 `/api/ops/employee/business-data/payment-qr` 取图，后端验签通过才返回。

1. 更新二维码：按 `tools/qr-signature/README.md` 用离线私钥签名，提交 `payment-qr.png` + `payment-qr.png.sig`，走代码发布。
2. 发布后检查启动日志：应出现 `PAYMENT_QR_INTEGRITY_OK`；若出现 `PAYMENT_QR_INTEGRITY_FAILED`，生产会拒绝启动。
3. 员工 H5（支付方式=二维码）应显示收款码；直接访问 `https://boss.jdzn99.com/secure/qr/payment-qr.png` **不能返回 `image/png`**（资源在 jar 内，不在静态目录；返回 404 或 SPA 的 `text/html` 均正常，说明未暴露图片）。
4. 禁止在服务器上手改 jar 内图片或 `.sig`；任何替换都会导致验签失败并拒绝展示。
5. 旧表 `ops_payment_qr_config` 仅保留历史审计，应用不再读取；执行 `sql/deprecate_payment_qr_config.sql` 标记废弃并移除 AI 实体注册。

### 生产数据库定时备份（2026-09-04 上线，落盘本机）

- **任务**：**本机**（开发机 WSL）crontab `0 2 * * *` 每日凌晨 2:00 全量备份生产 `ai_platform` + `mall_db`（CST）。本机需开机且 cron 服务运行，否则当天无备份。2026-10-15 起增 `@reboot` 补跑（`db-backup-if-stale.sh`：超 20h 未备份即补，幂等）
- **脚本**：本机 `/home/jason/data/db-backup.sh` — SSH 到生产 `docker exec mysqldump`（utf8mb4 + single-transaction + routines/triggers/events），管道回本机 gzip 落盘
- **产物**：本机 `/home/jason/data/<db>_<YYYYmmdd_HHMMSS>.sql.gz`（约 5~7MB/库），保留 7 天自动清理；日志同目录 `backup.log`。手工单表导出放 `manual/` 子目录可免被清理
- 恢复方式（在生产服务器）：`gzip -dc <file> | docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p`

---

