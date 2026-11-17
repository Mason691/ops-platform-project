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
- `PAYMENT_QR_BASE_DIR=/app/payment-qr` + 持久卷（收款码私有目录，2026-11-16 起）

生产 Nginx：`/ops/` → `boss-ops-nginx:80`，`/api/ops/**` → `ops-backend:8082`

### 收款码上线检查（强制）

收款码不是静态文件，必须由运营商管理员在平台后台上传并登记 SHA-256；漏传时员工 H5 收款信息步骤会显示“收款码未配置，请联系管理员”，后端日志出现 `Business exception ... 收款码未配置`。

1. 平台后台 `/ops/product-price` → “收款码配置” Tab → 上传收款码（JPG/PNG，≤5MB），确认“配置状态=已配置”且预览可见。
2. 或部署后核对：
   ```sql
   SELECT corp_id, file_path, file_hash, file_size, status
   FROM mall_db.ops_payment_qr_config WHERE delete_flag=0;
   ```
   至少存在所属运营商 `corp_id` 的记录；文件 `$PAYMENT_QR_BASE_DIR/<file_path>` 存在且 `sha256sum` 与 `file_hash` 一致。
3. 员工 H5（支付方式=二维码）应显示收款码；直接替换私有目录文件会被哈希校验拒绝，必须重走后台上传。

### 生产数据库定时备份（2026-09-04 上线，落盘本机）

- **任务**：**本机**（开发机 WSL）crontab `0 2 * * *` 每日凌晨 2:00 全量备份生产 `ai_platform` + `mall_db`（CST）。本机需开机且 cron 服务运行，否则当天无备份。2026-10-15 起增 `@reboot` 补跑（`db-backup-if-stale.sh`：超 20h 未备份即补，幂等）
- **脚本**：本机 `/home/jason/data/db-backup.sh` — SSH 到生产 `docker exec mysqldump`（utf8mb4 + single-transaction + routines/triggers/events），管道回本机 gzip 落盘
- **产物**：本机 `/home/jason/data/<db>_<YYYYmmdd_HHMMSS>.sql.gz`（约 5~7MB/库），保留 7 天自动清理；日志同目录 `backup.log`。手工单表导出放 `manual/` 子目录可免被清理
- 恢复方式（在生产服务器）：`gzip -dc <file> | docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p`

---

