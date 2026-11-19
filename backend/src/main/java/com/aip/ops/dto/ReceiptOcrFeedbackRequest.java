package com.aip.ops.dto;

/**
 * 收据号 OCR 采用结果反馈：记录图片、红蓝候选、最终采用值及是否人工修改。
 *
 * <p>前端在自动采用、切换候选、手动输入后失焦/离开步骤时上报，
 * 由后端写结构化日志，用于追溯与后续优化识别规则。</p>
 */
public record ReceiptOcrFeedbackRequest(
        Long attachmentId,
        String redReceiptNo,
        String blueReceiptNo,
        String adoptedReceiptNo,
        Boolean manual,
        String action) {
}
