package com.aip.ops.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 员工手工新增业务数据请求。
 *
 * <p>组织/经办人字段不在此 DTO 中——一律由服务端从 JWT + 平台 sys_user 派生，
 * 防止前端伪造归属。多经办人只传 userId，姓名/手机号由服务端回查。</p>
 */
@Data
public class EmployeeBusinessEntryRequest {

    /** 现有批次号；newBatch=true 时忽略 */
    private String batchNo;

    /** true = 新建批次（服务端按 公司-yyyyMMdd-流水号 生成） */
    private Boolean newBatch;

    private String customerPhone;

    private String customer;

    /** 小区 ID（ai_platform.sys_community.id，选填；服务端校验属于当前组织） */
    private Long communityId;

    /** 详细地址/门牌号（必填，≤200 字，如“3栋2单元501号”） */
    private String detailAddress;

    /** 是否特批（是/否）；特批时每个产品行单独填写提成比例 */
    private Boolean isSpecial;

    /** 共同经办人员工ID（当前操作员工由服务端强制加入，不可移除） */
    private List<Long> agentUserIds;

    /** 产品明细（至少一行，每行生成一条业务数据） */
    private List<ProductItem> products;

    /** 市场收款日期 yyyy-MM-dd；空默认当天 */
    private String receiptDate;

    /** 是否收款（是/否） */
    private Boolean isReceived;

    /** 收款凭证 URL 列表（最多 3 张，整笔业务共用） */
    private List<String> voucherUrls;

    /** 单个产品明细。 */
    @Data
    public static class ProductItem {

        /** 供应链品牌名称 */
        private String brand;

        /** 供应链品类名称（服务端映射为 PRODUCT_CATEGORY 字典 itemKey 落库） */
        private String category;

        /** 供应链商品规格型号 */
        private String model;

        private BigDecimal salePrice;

        private Integer saleQuantity;

        /** 提成比例（0-100，仅特批时生效；非特批按 0 处理） */
        private BigDecimal commissionRate;
    }
}
