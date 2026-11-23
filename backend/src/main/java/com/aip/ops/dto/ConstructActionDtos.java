package com.aip.ops.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 施工流转操作入参（H5 公众号员工工作台）。
 *
 * <p>服务端只信任 JWT 中的员工/组织身份；施工员、订单、状态均由后端校验。
 * 定位失败时允许 locationStatus=FAILED/MANUAL 并手填地址，不阻断现场打卡。</p>
 */
public final class ConstructActionDtos {

    private ConstructActionDtos() {
    }

    /** 上门打卡：定位 + 现场照片（照片 0~3 张，前端默认要求 1 张）。 */
    public record ArriveRequest(
            String locationStatus,
            BigDecimal lng,
            BigDecimal lat,
            String address,
            List<Long> attachmentIds,
            String remark) {
    }

    /** 客户签字：手写签名 PNG Base64（可带 data:image/png;base64, 前缀）。 */
    public record CustomerConfirmRequest(
            String signImageBase64,
            String locationStatus,
            BigDecimal lng,
            BigDecimal lat,
            String remark) {
    }

    /** 拒绝接单/客户拒绝确认：原因必填（≥5 字）。 */
    public record ReasonRequest(String reason) {
    }
}
