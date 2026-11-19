package com.aip.ops.dto;

import java.util.List;

/**
 * 收据号 OCR 结果。
 *
 * <p>在原有单值结果上扩展红/蓝双色候选：红蓝同时识别且不一致时
 * {@code needsConfirm=true}，前端必须展示两组候选由员工确认，禁止静默选择。
 * {@code receiptNo} 始终是默认推荐值，保持旧客户端兼容。</p>
 */
public record ReceiptOcrResult(
        String receiptNo,
        String redReceiptNo,
        String blueReceiptNo,
        String preferredColor,
        String recommendation,
        boolean needsConfirm,
        boolean exact,
        boolean multiple,
        String confidence,
        String source,
        String message,
        List<Candidate> candidates) {

    /**
     * 单个候选。color=RED/BLUE/UNKNOWN；position 为图片中的相对位置描述。
     */
    public record Candidate(String color, String value, String position,
                            double confidence, boolean selected) {
    }
}
