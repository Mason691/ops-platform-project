---
name: receipt-ocr
description: 公众号 H5 收据号 OCR 技能：识别收据图片中红色/蓝色 NO. 后的 7 位数字，自动填入收据号，多候选时人工选择。用于新增业务订单、货款补录等需要录入收据号的移动端表单。
version: 1.0.0
triggers:
  - 收据号
  - 收据识别
  - OCR
  - NO.
  - 红色蓝色数字
  - 货款补录
  - 收据图片
---

# 收据号 OCR 技能（Receipt No OCR）

## 1. 能力概述

**用途**：员工在公众号 H5 上传收据图片后，自动识别收据上**红色或蓝色 `NO.` 后的 7 位数字**，回填「收据号」字段；识别到多组号码（红/蓝不一致，或同色多组）时展示候选，由员工点击选择。

**黄金实现**：
- 通用复合字段：`~/ops-platform-project/frontend/src/components/ReceiptNoOcrField.vue`
- 新增业务订单凭证步骤：`~/ops-platform-project/frontend/src/components/BusinessStepVoucher.vue`
- 货款补录：`~/ops-platform-project/frontend/src/pages/employee/balance-receive.vue`
- 状态机 hook：`~/ops-platform-project/frontend/src/hooks/useReceiptOcr.js`
- 候选展示：`~/ops-platform-project/frontend/src/components/ReceiptOcrPanel.vue`
- 后端：`ReceiptOcrServiceImpl`、`ReceiptColorDigitExtractor`、`ReceiptNoParser`、`ReceiptOcrResultBuilder`

## 2. 业务规则（不可回退）

1. 红/蓝颜色分别生成掩膜后 OCR，优先 7 位数字；两色都存在且不一致时默认采用 `app.receipt-ocr.preferred-color`（默认 RED），并置 `needsConfirm=true`。
2. **禁止静默选择**：多候选必须展示候选让员工确认；前端只展示后端返回的候选，不自行拼接。
3. **手改优先**：员工手动修改或点击候选后，后续识别结果只更新提示，不得覆盖人工值。
4. 收据号选填（货款补录）或必填（新增业务订单按原表单规则），字符集为字母/数字/短横线/下划线/斜杠，长度 ≤50。
5. OCR 失败、超时、图片不支持时只提示手填，不阻塞表单提交。
6. 上传的收据图片必须走 `fileType=RECEIPT` 的原图上传（不压缩），后端 OCR 按该附件类型校验归属。
7. 采用/切换/手改结果通过 `POST /api/ops/employee/business-data/receipt-ocr/feedback` 留痕，禁止把手机号、身份证等敏感信息写入日志。

## 3. 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ops/employee/business-data/attachments?fileType=RECEIPT` | 上传收据原图，返回 `id` |
| POST | `/api/ops/employee/business-data/receipt-ocr` | body `{attachmentId}`，返回红/蓝候选、推荐值、`needsConfirm`、`candidates` |
| POST | `/api/ops/employee/business-data/receipt-ocr/feedback` | 记录采用结果（`redReceiptNo/blueReceiptNo/adoptedReceiptNo/manual/action`） |

识别超时预算：彩色单色 15s/方向扫描总预算 30s/全图兜底 30s；OCR 并发上限 2。新增调用方不要自行加大超时或绕过附件归属校验。

## 4. 接入新表单（推荐）

直接使用复合字段 `ReceiptNoOcrField`：

```vue
<ReceiptNoOcrField
  v-model="form.receiptNo"
  v-model:receipt-image="form.receiptImage"
  :error="receiptNoError"
  @change="receiptNoError = ''"
/>
```

表单对象只需包含：

```js
{ receiptNo: '', receiptImage: null }
```

组件内部会：
- 上传收据图片并自动 OCR；
- 自动填入推荐收据号；
- 展示红/蓝/同色候选供人工选择；
- 手动修改后停止自动覆盖；
- 失焦/卸载时上报采用结果。

## 5. 接入已有复杂表单（使用 hook）

需要自定义上传位置/校验时，使用 `useReceiptOcr(form)`。`form` 必须包含：
- `receiptNo: string`
- `receiptImage: { id, url } | null`
- `receiptNoCheck`（避免旧查重结果覆盖，可置 null）

必须解构为顶层 ref 再传给 `ReceiptOcrPanel`（嵌套 ref 在模板中不会自动解包，会导致 loading 永久为真）。手动输入调用 `onManualInput()`，失焦/卸载调用 `logFinal()/dispose()`。

## 6. 验证清单

- [ ] 上传红色 `NO.` 收据 → 自动填入 7 位数字。
- [ ] 上传蓝色 `NO.` 收据 → 自动填入 7 位数字。
- [ ] 红蓝不一致 → 面板显示两组候选，默认采用配置色，可点击切换。
- [ ] 同色多组 → 候选可点选。
- [ ] 手动修改后再次上传/识别 → 不覆盖人工值。
- [ ] OCR 失败/非收据图片 → 提示手填，表单仍可提交。
- [ ] 删除收据图片 → OCR 状态清空，收据号保留人工值。
- [ ] 货款补录：识别后可正常提交，收据号进入补录接口。
- [ ] 业务订单新增：识别结果与原有收据号查重/校验不冲突。

## 7. 常见陷阱

1. `ReceiptOcrPanel` 的 props 必须是顶层 ref；嵌套 ref 会把 `loading` 当对象，永久显示“正在识别”。
2. 收据图片必须 `fileType=RECEIPT`；用 PAYMENT/CONTRACT 上传后 OCR 会因附件类型不符拒绝。
3. 不要用压缩后的图片做 OCR；收据号/红蓝字压缩后会丢失对比度。
4. 不要在 OCR 失败时阻断提交；识别是辅助能力。
5. 不要在前端自行选择红/蓝候选并静默覆盖用户输入。
6. 新增页面记得在 `onBeforeUnmount` 调用 `dispose()`，避免离开页面后仍回填/上报。

## 8. 相关配置

- `app.receipt-ocr.preferred-color` / 环境变量 `RECEIPT_OCR_PREFERRED_COLOR`：红蓝不一致时的默认颜色（RED/BLUE）。
- 收据图片大小上限：20MB（`MAX_RECEIPT_SIZE_MB`），仅收据保留原图。
