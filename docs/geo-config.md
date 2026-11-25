# 小区定位与逆地理编码配置

> 功能：公众号员工工作台「新增业务订单 → 新建小区」打开时自动获取手机定位（GCJ-02），
> 经高德逆地理编码回填详细地址，并随小区保存坐标与行政区划。
> 定位失败可手动填写地址，不阻断制单。

## 1. 链路

```
ops H5（微信内置浏览器）
  └─ wx.getLocation({type:'gcj02'})            ← 微信 JS-SDK（首选）
      └─ 失败降级 navigator.geolocation（WGS-84 前端转 GCJ-02）
          └─ 再失败：手填地址（locationSource=MANUAL）

POST /api/ops/employee/business-data/communities   ← ops 后端（员工 JWT）
  └─ PlatformInternalClient (X-Internal-Token)
      └─ POST /api/internal/community/create        ← 平台后端
          └─ sys_community（address/longitude/latitude + 行政区划/来源）

GET /api/ops/employee/business-data/geo/regeo      ← ops 后端代理
  └─ GET /api/internal/community/regeo?corpId&lng&lat
      └─ 高德 https://restapi.amap.com/v3/geocode/regeo（key 仅在平台）
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

`sys_community.address`（VARCHAR(500)）复用为"小区详细地址"，
`longitude/latitude` 为既有列；存量小区无坐标可正常展示，不受影响。
同名小区已存在时直接返回既有记录，**不回填本次定位**（避免静默改写他人数据）；
存量无坐标小区可在平台「小区管理 → 批量地理编码」按地址补录。

**执行顺序**：DDL → 平台后端 → ops 后端 → ops H5。
MySQL 8.0.46 不支持 `ADD COLUMN IF NOT EXISTS`，脚本已用 `information_schema` 条件化，可重复执行。

## 3. 高德服务端 Key 配置

1. 在高德开放平台创建「Web 服务」类型 Key（逆地理编码）。
2. 平台「组织设置 → 地图配置」填写**服务端密钥**（`amap_server_key_enc`）。
3. 若所有组织共用平台级 Key：配置 `corp_id=0` 的组织 AI 配置即可，组织级留空会自动回退。
4. 可选配置（平台 `application.yml`）：

```yaml
amap:
  regeo:
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

平台侧固定保护：单组织 60 次/分钟、相同坐标 24h 缓存、超时/网络错误重试 1 次；
ops 侧按员工 10 次/分钟限流。多实例部署 ops 时需把员工限流改为 Redis。
平台多实例部署时 Caffeine 缓存与组织限流也是实例级，配额保护需改 Redis。
配额类错误返回「地图服务调用超限，请稍后重试或手动填写」，不阻断保存。

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
# 跳过高德代理，返回固定地址
VITE_MOCK_REGEO=1
```

修改后重启 `npm run dev:h5`。生产构建不设置这两个变量即关闭。

## 6. 交互与降级规则

| 场景 | 行为 |
|---|---|
| 定位成功 + 逆解析成功 | 自动填充详细地址，展示坐标；地址可编辑，编辑后自动定位不再覆盖 |
| 定位成功 + 逆解析失败 | `partial`：保留坐标，提示"地址解析失败，请手动填写详细地址" |
| 定位失败/拒绝授权 | 提示原因 + 「重新定位」；本会话不再自动弹窗，可手填保存 |
| 定位中 | 提交按钮禁用，提供「跳过定位」立即转手填 |
| 定位区县 ≠ 组织区县 | 黄色提示 + 保存前 `uni.showModal` 确认；取消停留，确认后保存 |
| 组织未配置区县 | 维持原逻辑：拒绝新建并提示管理员完善组织信息 |
| 同名小区已存在 | 幂等返回已有记录，提示"该小区已存在，已为您选中" |

区县归属**始终以组织 `region_id` 为准**，定位区县只用于提示与日志告警，不写回组织归属。

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
