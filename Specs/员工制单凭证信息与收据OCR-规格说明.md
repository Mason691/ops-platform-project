# 员工制单「支付信息 / 凭证信息」拆分与收据号 OCR — 规格说明

> 状态：✅ 基础功能已生产部署（2026-11-18 12:10/14:57）；红字 OCR + 收据原图改造已完成待发布；微信真机 E2E 待执行
> 范围：ops 公众号 H5 员工制单向导 + ops 后端附件/OCR + mall_db 数据模型

## 一、功能概述

员工工作台「新增业务订单」由 4 步调整为 5 步：

```
基础信息 → 商品明细 → 支付信息 → 凭证信息 → 确认提交
```

- **支付信息**：支付方式（二维码/现金/转账）、市场收款日期、备注；付款方式（全款/定金）与特批/提成仍按现有商品行模型在「商品明细」维护，本步只读提示。
- **凭证信息**：收据图片（可选，用于 OCR）→ 收据号（必填，可 OCR 回填/手改）→ 支付凭证（必填，1~3 张）→ 合同附件（必填，1~5 份，图片/PDF）。
- **确认提交**：展示支付信息与凭证信息汇总，支持跳转修改；缺失项在待补充事项卡片就地补填。

## 二、用户故事

1. 员工在凭证信息步骤上传收据图片，系统自动识别收据号并提示核对；识别失败可手填，不阻塞继续填写。
2. 收据号必填；人工修改后，重新上传图片时系统弹窗询问是否用新识别结果覆盖。
3. 支付凭证未上传时红色必填提示生效，无法进入确认/提交；支付凭证可预览、删除重传。
4. 合同附件支持 JPG/PNG/PDF，可预览/下载、删除重传；未上传无法提交。
5. 确认提交页完整展示支付信息与凭证信息，缺失项可就地补填后提交。
6. 提交后订单头保存收据图片、收据号、支付凭证、合同附件；附件表记录上传人/时间/原文件名，订单可追溯。

## 三、验收标准

| # | 验收项 | 实现 |
|---|--------|------|
| 1 | 步骤条为「基础信息 → 商品明细 → 支付信息 → 凭证信息 → 确认提交」 | 5 步 STEPS + 向导组件 |
| 2 | 原收款信息中的收据号/收款凭证不在支付信息出现 | 已移入凭证信息 |
| 3 | 上传收据图片自动识别红色数字（`NO.` 后 7 位）并回填；未命中红字时回退 `NO.` 规则 | 红色掩膜 + Tesseract（psm 7/11）+ `ReceiptRedDigitExtractor`；回退 `ReceiptNoParser` |
| 4 | 人工修改后不被识别结果静默覆盖 | 收据号为空/仍为上次识别值才自动填；否则弹窗确认 |
| 5 | 识别失败/超时提示手填，收据号仍必填 | OCR 失败返回 `receiptNo=null` + 提示；前端可重试 |
| 6 | 支付凭证必填、1~3 张、可预览/删除 | 前端校验 + 后端 `requireOwnedList` |
| 7 | 合同附件必填、1~5 份、JPG/PNG/PDF、≤10MB | 同上 |
| 8 | 确认页展示支付信息/凭证信息，可跳转修改 | `BusinessStepConfirm` 两个汇总卡 |
| 9 | 提交后数据正确落库、文件可访问；收据为原图，支付凭证/合同图片为压缩版本 | 订单头 3 字段 + `ops_business_attachment` |
| 10 | 回归：以旧换新/支付方式/多商品多经办人/批次订单号/工作流不变 | 未改动相关逻辑；`voucher_url` 兼容写支付凭证 |

## 四、交互与校验

### 4.1 收据号 OCR

- **红字优先（业务口径）**：收据号是收据上红色印刷的数字（通常为右上角 `NO.` 后的 7 位）。服务端先按 `R≥100 且 R-max(G,B)≥30` 提取红字掩膜（红色像素为黑、其余为白），用 Tesseract `eng --psm 7 -c tessedit_char_whitelist=0123456789` 识别；未命中再以 `--psm 11` 稀疏文本兜底。
- **候选过滤**：只接受 6~8 位纯数字（底部红章的 13 位流水号自动排除）；7 位优先；上部区域（`top+height ≤ 72%` 图高）优先，避免把红章流水号误当收据号；同区域多候选取置信度最高者。位置可疑、非 7 位或置信度低于 60 时返回 `exact=false`，提示「识别结果可能不准确」。
- **全图回退**：红字未识别到候选（或红字识别异常/超时）时，回退原规则——定位「入账日期」后的 `NO.`（兼容 `N0`、全角、数字间空格），取其后 7 位数字；6/8 位提示「识别结果可能不准确」；多候选取入账日期后的第一个。
- 后端：`POST /api/ops/employee/business-data/receipt-ocr`（员工 JWT），入参 `{attachmentId}`；红字 20s、全图 30s 超时（原图尺寸更大）；全图首次慢速失败（≥5s，通常是超时）不再重跑 `eng`，仅快速失败/空结果降级 `eng`；红字识别异常不阻塞，自动回退全图。OCR 并发上限 2，排队 5s 仍无槽位时返回「识别任务繁忙，请稍后重试」。
- 锚点（回退规则）：优先「入账日期」；若图片/OCR 文本中不存在该词，才退化为「入账/日期/DATE」的最早位置，避免把开票日期等其它日期后的 NO. 误当入账收据号。
- 前端：上传成功自动触发；加载态「正在识别收据号…」；失败可「重新识别」；收据号可手改，修改后旧识别值失效。
- 覆盖策略：收据号为空或等于上次自动识别值 → 自动填入；用户已手改 → 弹窗询问是否覆盖（默认保留）。

### 4.2 附件

| 类型 | 数量 | 格式 | 单文件 | 压缩 |
|------|------|------|--------|------|
| 收据图片 RECEIPT | 0~1（可选） | JPG/PNG | ≤20MB | **原图保存，不压缩**（保证红字 OCR 清晰度） |
| 支付凭证 PAYMENT | 1~3（必填） | JPG/PNG | ≤10MB | 服务端 JPEG 最长边 2000px / 0.85 |
| 合同附件 CONTRACT | 1~5（必填） | JPG/PNG/PDF | ≤10MB | 图片同上；PDF 原样 |

- 上传接口：`POST /api/ops/employee/business-data/attachments?fileType=RECEIPT|PAYMENT|CONTRACT`（multipart `file`），仅员工 JWT。
- 上传先落 `ops_business_attachment`（order_id 为空），提交时按 corp + 类型 + 未绑定校验并回填 order_id。
- 收据图片**原图保存并用于 OCR（不压缩）**；支付凭证/合同图片服务端重编码 JPEG（最长边 2000px/0.85），PDF 原样。支付/合同图片的 10MB 限制作用于压缩后产物，选择原图仅做 50MB 粗筛。
- 收据图片以 ImageReader 探测到的真实格式决定扩展名/Content-Type（JPEG→jpg、PNG→png），防止改后缀绕过。

### 4.3 提交校验

- 收据号非空 + 长度/字符集 + 同组织同客户查重（后端二次校验）。
- 支付凭证 1~3 张、合同附件 1~5 份，均需属于当前组织且未绑定其他订单。
- 缺项返回具体提示（如「请上传支付凭证（银行或微信支付凭证）」）。

## 五、数据模型与接口

### 5.1 数据库（mall_db）

- `ops_business_order` 新增：
  - `receipt_image_url VARCHAR(1000)` 收据图片 URL
  - `payment_voucher_urls VARCHAR(3000)` 支付凭证 URL 列表（逗号分隔）
  - `contract_attachment_urls VARCHAR(3000)` 合同附件 URL 列表（逗号分隔）
- 新表 `ops_business_attachment`：corp_id / order_id / file_type / file_name / file_url / file_size / content_type / uploader_user_id / uploader_name / upload_time + 审计字段。
- 迁移：`sql/migrate_business_order_vouchers.sql`（幂等，须先于新 JAR 执行）。

### 5.2 接口

- `POST /api/ops/employee/business-data/attachments` 上传附件，返回 `{id,fileUrl,fileName,fileSize,contentType,fileType}`。
- `POST /api/ops/employee/business-data/receipt-ocr` 识别收据号，返回 `{receiptNo,exact,multiple,confidence,message,source}`（confidence=HIGH/MEDIUM/LOW；source=RED/FULL/NONE）。
- `POST /api/ops/employee/business-data/manual` 新增 `receiptImageId` / `paymentVoucherIds` / `contractAttachmentIds`；移除旧 `voucherUrls` 入参（旧客户端需同步升级）。
- 订单详情增加 `receiptImageUrl` / `paymentVoucherUrls` / `contractAttachmentUrls` / `attachments`。

## 六、安全

- 上传/OCR 仅员工 JWT；`JwtTokenInspector` 拒绝会员 SSO 令牌；上传/删除服务层再次断言可解析员工手机号。
- 附件归属按 corp + 类型校验；已绑定其他订单的附件拒绝复用；回写订单凭证/绑定附件的 update 均带 corp 条件。
- 上传路径固定 `ops/business/{corpId}/{type}/{date}/`；OCR 解析本地路径时做前缀 + 规范路径校验，防目录穿越。
- 文件类型二次校验：图片解码 + 像素上限（收据原图 24MP、其他图片 16MP，防压缩炸弹）；PDF `%PDF-` 魔数；收据单文件 ≤20MB，其他 ≤10MB。
- URL 相对路径 `/uploads/ops/...`，不暴露服务器文件路径。
- 上传配额：同一员工 24h 内未绑定附件最多 100 个；每日 04:30 清理 7 天前未绑定附件（文件+记录）；表单删除附件时调用删除接口（仅本人、未绑定可删）。
- 已知限制：附件 URL 为不可猜测 UUID，但 `/uploads/**` 仍沿用平台既有静态直出策略（未加 JWT）；如需严格鉴权需后续改造为受控下载端点。

## 七、与平台 AI 的关系

平台现有 AI 服务（DeepSeek 文本对话 / ASR / TTS）没有视觉/OCR 能力；按需求允许自行安装组件，采用服务端 Tesseract OCR。识别接口与 OCR 实现隔离，后续平台提供视觉服务时可替换 `ReceiptOcrService` 实现，不影响前端契约。

## 八、已知限制 / 部署注意

- 需重建 ops 镜像安装 `tesseract-ocr` + `tesseract-ocr-chi-sim`（仅换 JAR 的 overlay 发布不会安装 OCR 组件）。
- 迁移脚本必须先于新 JAR 执行；后端与 ops H5 需同一发布窗口切换（旧客户端不再传新附件 ID）。
- 旧草稿中的 `voucherUrls` 不会自动迁移为附件 ID，恢复后需重新上传支付凭证/合同附件（收据号保留）。
- 确认页已展示支付信息与凭证信息；付款方式/定金与特批/提成仍按现有商品行模型在商品明细维护。
- 未绑定附件有配额与每日清理；表单删除会尽力同步删除文件/记录，删除失败由定时任务兜底。
- 附件 URL 沿用平台 `/uploads/**` 静态直出（既有策略，未加 JWT）；合同/银行凭证的严格鉴权下载列为后续改进。
- 项目无自动化测试框架；OCR 解析规则以独立解析器测试样例验证。
