# 配置参考

> 从 `CLAUDE.md` 拆分（2026-11-16）。环境相关的强制注意事项见 `CLAUDE.md`「已知陷阱」。

---

## 关键配置

### 后端（application.yml）

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 后端端口 | `8084` |
| `spring.datasource.url` | MySQL 连接 | `jdbc:mysql://104.238.165.57:3306/mall_db` ⚠️ 已退役主机，本地需覆盖 |
| `platform.base-url` | 底座平台地址 | `http://104.238.165.57:8080` ⚠️ 同上，生产由 `PLATFORM_BASE_URL` 覆盖 |
| `platform.subsystem-code` | 子系统标识 | `ops-platform` |
| `platform.internal-token` | 内部调用 Token | `aip-internal-svc-token-2026` |
| `platform.jwt-secret` | JWT 签名验证密钥（不自签） | 32+ 字节 |
| `platform.jwt-expiration` | JWT 过期时间（秒） | `43200`（12小时） |
| `spring.servlet.multipart.max-file-size` | 上传文件大小限制 | `50MB` |
| `spring.servlet.multipart.max-request-size` | 上传请求大小限制 | `50MB` |
| `mall-service.base-url` | mall 子系统地址 | `http://localhost:8083` |
| `scm-service.base-url` | 供应链基础资料服务（员工手工新增的品牌/品类/型号下拉与保存校验） | `http://localhost:8084`（生产同 host 网络，可由 `SCM_SERVICE_BASE_URL` 覆盖） |
| `app.upload.base-dir` | 上传根目录 | `/app/uploads`（Docker） |
| `app.receipt-ocr.preferred-color` | 收据号 OCR 红蓝并存时的默认采用颜色（RED/BLUE） | `${RECEIPT_OCR_PREFERRED_COLOR:RED}` |
| `security.qr.fail-fast` | 收款码启动验签失败是否拒绝启动（生产必须 true） | `${QR_FAIL_FAST:true}` |
| `security.qr.patrol-cron` | 收款码每日巡检 cron（绕过缓存重新验签） | `0 0 3 * * *` |
| `app.import.upload-dir` | 异步导入文件暂存目录 | `${user.home}/data/imports`（Docker 环境变量覆盖） |
| `app.resource.base-url` | 静态资源域名 | `https://boss.jdzn99.com` |
| `wechat.oauth.redirect-uri` | 员工微信授权回调地址（必须与公众号网页授权域名一致） | `https://boss.jdzn99.com/ops/`（`WECHAT_OAUTH_REDIRECT_URI` 可覆盖） |
| `wechat.oauth.state-ttl-seconds` | 微信 OAuth state 有效期（秒） | `300` |
| `wechat.oauth.scope` | 微信网页授权 scope | `snsapi_base` |
| `spring.kafka.enabled` | Kafka 开关 | yml 签入 `true`；无 Kafka 环境自动降级同步 |
| `spring.redis.enabled` | 平台开启 Redis 时子系统同步校验 `blacklist:{jti}` | `false`（默认不改变现网行为） |
| `management.health.redis.enabled` | Redis health indicator 开关 | `false`（SDK 引入 Redis starter 后，未配置凭据时会让 `/actuator/health` 返回 503） |
| `battle-map.etl.auto-refresh` | 作战地图定时 ETL | `true` |
| `battle-map.etl.init-on-startup` | 启动时全量 ETL | `true` |
| `battle-map.etl.cron` | ETL 刷新间隔 | `0 */30 * * * *`（每 30 分钟） |
| `ops.remind.hours-before` | 活动提醒提前小时数 | `24,1` |
| `gas.datasource.jdbc-url` | 外部燃气平台 PolarDB 连接 | `jdbc:mysql://parking-db.mysql.polardb.rds.aliyuncs.com:3306/jinyuan_info` |
| `gas.datasource.username` | 燃气平台 DB 用户名 | `jinyuan_kaifa_only` |
| `gas.import.company-filter` | 燃气导入公司过滤 | `德保` |
| `amap.key` | 高德地图 API Key（作战地图边界） | 需配置 |
| `platform.merchant-role-name` | 商家角色名称 | `销售商` |
| `logging.level.com.aip.ops` | 应用日志级别 | `DEBUG` |
| `management.endpoints.web.exposure.include` | Actuator 暴露端点 | `health,info` |
| `platform.sdk-permit-paths` | SDK 安全过滤器放行路径 | 6 个路径（`/actuator/**`, `/uploads/**` 等）。SecurityConfig 另有 7 个公开端点（`/api/ops/home-data`, `/api/ops/coupon/list`, `/api/ops/ai/config`, `/api/ops/live-rooms/**`, `/api/ops/admin/business-data/template`, `/api/internal/business-data/**`, `/api/internal/workflow/**`），合计 13 个免认证路径（内部端点仍由 X-Internal-Token 校验） |

**生产 Kafka**：SASL_PLAINTEXT 认证（`admin/admin123.`），bootstrap-server `113.250.60.41:9092`。

### RestTemplate Beans

| Bean | 超时 | 用途 |
|---|---|---|
| `restTemplate`（默认） | 10s connect, 10s read | 通用内部调用 |
| `aiRestTemplate` | 10s connect, **120s** read | AI chat 代理（底座响应慢） |
| `asrRestTemplate` | 10s connect, **300s** read | ASR 语音识别（大音频文件） |

### 前端环境变量

| 文件 | 变量 | 值 |
|------|------|-----|
| `.env` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |
| `.env` | `VITE_CORP_ID` | `4` |
| `.env.development` | `VITE_OPS_ENTRY` | `http://localhost:3035` |
| `.env.production` | `VITE_BASE` | `/ops/` |
| `.env.production` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |

---

