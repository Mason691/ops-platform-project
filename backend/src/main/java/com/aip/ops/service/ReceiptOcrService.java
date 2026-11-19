package com.aip.ops.service;

import com.aip.ops.dto.ReceiptOcrFeedbackRequest;
import com.aip.ops.dto.ReceiptOcrResult;

/**
 * 收据图片 OCR：从上传的收据图片识别收据号，并记录最终采用结果。
 */
public interface ReceiptOcrService {

    /**
     * @param attachmentId 收据图片附件 ID（需属于当前组织、类型 RECEIPT）
     * @return 红/蓝候选、默认推荐值、确认提示与边界提示；失败时 receiptNo=null + 手填提示
     */
    ReceiptOcrResult recognize(Long attachmentId);

    /**
     * 记录识别候选的最终采用值（图片 ID、红蓝候选、采用值、是否人工修改）。
     * 仅写结构化日志，不阻塞制单；附件已删除等异常只告警。
     */
    void logAdoption(ReceiptOcrFeedbackRequest request);
}
