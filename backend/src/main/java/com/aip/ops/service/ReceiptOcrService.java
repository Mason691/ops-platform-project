package com.aip.ops.service;

import java.util.Map;

/**
 * 收据图片 OCR：从上传的收据图片识别收据号。
 */
public interface ReceiptOcrService {

    /**
     * @param attachmentId 收据图片附件 ID（需属于当前组织、类型 RECEIPT）
     * @return receiptNo / exact / multiple / message；识别失败时 receiptNo=null + 手填提示
     */
    Map<String, Object> recognize(Long attachmentId);
}
