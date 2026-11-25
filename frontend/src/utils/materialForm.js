/** 员工 H5 通用展示工具（SCM 物料领用/核销页面使用）。 */

/** LocalDateTime 展示：去掉 T、精确到分钟。 */
export function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/**
 * 商品统一显示：`编号 品牌 名称 型号`。
 *
 * 空值逐段跳过，不产生 null、连续空格或首尾空格：
 *   WL001 美的 电饭煲 MB-FB40
 *   品牌为空 → WL001 电饭煲 MB-FB40
 *   型号为空 → WL001 美的 电饭煲
 *   都为空   → WL001 电饭煲
 *
 * 与平台前端 views/scm/materialLabel.js 同口径（跨仓库无法直接引用，保持实现一致）。
 */
export function formatMaterialLabel(material) {
  if (!material) return ''
  return [material.number, material.brandName, material.name, material.model]
    .map(part => (part == null ? '' : String(part).trim()))
    .filter(part => part.length > 0)
    .join(' ')
}
