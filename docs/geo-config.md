# 小区地址与地理编码配置

> 功能：公众号员工工作台「新增业务订单 → 新建小区」支持**手动输入区县之后的详细地址**，
> 由后端拼接组织省市区并调用高德正向地理编码，自动获得 GCJ-02 坐标；
> 也可点击「使用当前位置自动填充」走定位 + 逆地理编码。两者并存，编码失败允许无坐标保存。

## 1. 链路

```
【手动地址（默认）】
ops H5 输入：只填区县之后，如「XX路88号」
  └─ POST /api/ops/employee/business-data/geo/geocode   ← ops 后端（员工 JWT）
      └─ POST /api/internal/community/geocode           ← 平台后端
          ├─ 组织区划路径 + 用户输入 → 完整地址（自动去除重复省市区）
          └─ 高德 https://restapi.amap.com/v3/geocode/geo（key 仅在平台）
              └─ 返回 lng/lat、formatted_address、level、lowAccuracy

【当前位置（可选按钮）】
wx.getLocation({type:'gcj02'}) → 失败降级 navigator.geolocation（WGS-84 前端转 GCJ-02）
  └─ GET /api/ops/employee/business-data/geo/regeo → 平台 /api/internal/community/regeo
      └─ 高德 https://restapi.amap.com/v3/geocode/regeo

【保存】
POST /api/ops/employee/business-data/communities
  └─ POST /api/internal/community/create
      └─ sys_community（完整地址/经纬度/行政区划/来源/精度）
          └─ 若前端未带坐标：平台用完整地址兜底地理编码；失败则保存地址、坐标为空 + 告警
```

**高德 key 不出现在前端**：key 存于平台 `sys_org_ai_config.amap_server_key_enc`（AES 加密），
按员工所属组织解析；组织未配置时回退平台级 `corp_id=0` 的配置。
不新增全局 `amap.web.key`，避免与既有组织地图配置形成两套来源。

## 2. 数据库变更

平台库 `ai_platform`：

| 脚本 | 说明 |
|---|---|
| `sql/alter-sys-community-location.sql` | 新增 `province/city/district_name/township/location_source/location_accuracy/coordinate_type/adcode` + `idx_location`，幂等 |
| `sql/rollback-sys-community-location.sql` | 回滚上述列/索引（不删除既有 address/经纬度） |
| `sql/alter-sys-community-geocode-level.sql` | 新增 `geocode_level`（门牌号/道路/兴趣点/区县等），幂等 |
| `sql/rollback-sys-community-geocode-level.sql` | 回滚 `geocode_level` |

`sys_community.address`（VARCHAR(500)）保存**完整地址**（组织省市区 + 用户输入）；
用户输入框只填区县之后的部分，前后端都会去除重复省市区。
同名小区已存在时直接返回既有记录，**不回填本次地址/定位**（避免静默改写他人数据）；
存量无坐标小区可在平台「小区管理 → 批量地理编码」按地址补录。

**执行顺序**：DDL → 平台后端 → ops 后端 → ops H5。
MySQL 8.0.46 不支持 `ADD COLUMN IF NOT EXISTS`，脚本已用 `information_schema` 条件化，可重复执行。

## 3. 高德服务端 Key 配置

1. 在高德开放平台创建「Web 服务」类型 Key（**地理编码 + 逆地理编码**，同一 Key 通用）。
2. 平台「组织设置 → 地图配置」填写**服务端密钥**（`amap_server_key_enc`）。
3. 若所有组织共用平台级 Key：配置 `corp_id=0` 的组织 AI 配置即可，组织级留空会自动回退。
4. 可选配置（平台 `application.yml`）：

```yaml
amap:
  regeo:
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

平台侧固定保护：单组织 60 次/分钟、相同坐标/地址 24h 缓存、超时/网络错误重试 1 次；
ops 侧按员工 10 次/分钟限流（定位与地理编码共用）。多实例部署 ops 时需把员工限流改为 Redis。
平台多实例部署时 Caffeine 缓存与组织限流也是实例级，配额保护需改 Redis。
配额类错误返回「地图服务调用超限，请稍后重试」，不阻断无坐标保存。

## 4. 微信公众号 JS-SDK

ops H5 的 `index.html` 已注入 `jweixin-1.6.0` 并把 `getLocation`/`openLocation`
加入 `jsApiList`，`wx.ready` 后设置 `window.__wxReady=true`。仍需在公众号后台确认：

- 「设置与开发 → 公众号设置 → 功能设置」：**JS 接口安全域名**包含 ops H5 域名
  （生产 `boss.jdzn99.com`；本地联调需用内网穿透域名，不能用 localhost）。
- 「开发 → 接口权限」：确认**地理位置**接口可用（服务号需认证）。
- 页面必须通过公众号菜单/网页授权入口打开；普通浏览器会走 H5 降级。

未开通时表现：`wx.getLocation` fail → 自动降级 `navigator.geolocation`；
仍失败则提示手填地址。可在微信开发者工具/真机日志查看 `[JSSDK]` 前缀输出。

## 5. 本地 Mock

在 `frontend/.env.development`（不要提交个人地址）临时追加：

```bash
# 跳过真实定位，固定返回 GCJ-02 坐标
VITE_MOCK_LOCATION=103.8,36.0
# 跳过高德逆地理编码代理，返回固定地址
VITE_MOCK_REGEO=1
# 跳过高德正向地理编码，返回固定坐标（门牌号精度）
VITE_MOCK_GEOCODE=1
```

修改后重启 `npm run dev:h5`。生产构建不设置这三个变量即关闭。

## 6. 交互与降级规则

| 场景 | 行为 |
|---|---|
| 弹窗打开 | **不自动定位**，默认展示详细地址输入框；点「使用当前位置自动填充」才请求定位 |
| 手动输入 | 只填区县之后地址；placeholder 与辅助说明明确"无需填写省市区"；检测到重复省市区时黄色提示 + 「一键去除」 |
| 首次提交（无坐标） | 后端拼接完整地址 → 高德地理编码 → 展示完整地址/坐标/精度预览；再次点击才创建 |
| 地理编码成功 | `locationSource=GEOCODE`，保存坐标与 `geocode_level` |
| 地理编码无结果 | 提示"未找到该地址"；可修改重试，或点「仍要保存（无坐标）」二次确认后保存 |
| 地理编码低精度 | level 为省/市/区县时黄色提示"建议补充门牌号"，仍可保存并记录 level |
| 使用当前位置 | 已有地址时先确认覆盖；成功后回填地址与坐标（`WECHAT`/`H5`），旧编码结果失效 |
| 编辑地址 | 旧坐标/编码结果立即失效，提示"地址已修改，将重新获取坐标" |
| 定位/编码区县 ≠ 组织区县 | 黄色提示 + 创建前 `uni.showModal` 确认；取消停留，确认后保存 |
| 组织未配置区县 | 拒绝新建并提示管理员完善组织信息 |
| 同名小区已存在 | 幂等返回已有记录，提示"该小区已存在，已为您选中" |

区县归属**始终以组织 `region_id` 为准**，定位/编码区县只用于提示与日志告警，不写回组织归属。
后端在 create 时若发现未传坐标且有详细地址，会再做一次兜底地理编码；仍失败则保存地址、
坐标为空、`locationSource=MANUAL` 并写告警日志。

## 7. 隐私

- 定位前展示"位置仅用于记录小区地址，便于派工与施工导航"。
- 不写入 `localStorage`/`uni.setStorageSync`，关闭页面即丢弃。
- 拒绝授权后本会话不再自动弹窗，仅用户主动点「重新定位」时重试。
- 坐标仅随小区记录保存，受现有小区/订单权限控制。

## 8. 验证命令

```bash
# 平台编译 + 单测
JAVA_HOME=~/.local/jdk17 mvn -pl service,web -am compile -DskipTests
JAVA_HOME=~/.local/jdk17 mvn -pl service -am -Dtest=CommunityGeoSupportTest test

# ops 后端编译 + 单测
cd ~/ops-platform-project/backend
JAVA_HOME=~/.local/jdk17 mvn compile
JAVA_HOME=~/.local/jdk17 mvn -Dtest=CommunityGeoUtilsTest test

# ops H5
cd ~/ops-platform-project/frontend
npm run build:h5
```

真机验收重点：微信内自动定位填充、编辑后不被覆盖、重新定位、拒绝授权仍可保存、
H5 降级坐标转换、区县不一致确认、数据库 `sys_community` 定位字段落库。
