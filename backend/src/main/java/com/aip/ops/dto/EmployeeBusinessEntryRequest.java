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

    /** 小区 ID（ai_platform.sys_community.id，必填；服务端校验属于当前组织） */
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

    /**
     * 收据号（必填）：可由收据图片 OCR 自动识别后人工核对修改。
     * 填写时需在当前组织内唯一（同客户可复用），长度 ≤50。
     */
    private String receiptNo;

    /** 收据图片附件 ID（可选；上传后用于 OCR 识别收据号）。 */
    private Long receiptImageId;

    /** 支付凭证附件 ID 列表（必填，1~3 张；ops_business_attachment.id）。 */
    private List<Long> paymentVoucherIds;

    /** 合同附件 ID 列表（必填，1~5 份；ops_business_attachment.id，图片/PDF）。 */
    private List<Long> contractAttachmentIds;

    /** 是否收款（是/否）——历史字段保留兼容，新前端不再传值 */
    private Boolean isReceived;

    /** 支付方式：QR=二维码 / CASH=现金 / TRANSFER=转账（必填） */
    private String paymentMethod;
    /**
     * 付款方式：FULL=全款支付（默认）/ DEPOSIT=定金支付。
     * 注意与 paymentMethod（二维码/现金/转账）是两个维度。
     */
    private String paymentType;

    /** 定金金额（仅 DEPOSIT 必填；>0 且 < 销售价合计；最多两位小数） */
    private BigDecimal depositAmount;

    /** 订单备注（选填，≤500 字，落库 ops_business_order.remark） */
    private String remark;

    /** 单个产品明细。 */
    @Data
    public static class ProductItem {

        /** SCM 商品信息ID（用于产品售价查询；旧前端可不传，服务端按品牌/品类/型号兜底） */
        private Long productId;

        /** 供应链品牌名称 */
        private String brand;

        /** 供应链品类名称（服务端映射为 PRODUCT_CATEGORY 字典 itemKey 落库） */
        private String category;

        /** 供应链商品规格型号 */
        private String model;

        private BigDecimal salePrice;

        private Integer saleQuantity;

        /** 特批标记（前端展示用；服务端以“售价是否低于标准价”重新判定） */
        private Boolean isSpecial;

        /** 特批文件ID（低价场景必填，ops_business_special_file.id） */
        private Long specialFileId;

        /** 提成比例（0-100，仅低价特批时生效；非特批按 0 处理） */
        private BigDecimal commissionRate;

        /** 是否以旧换新；true 时 deductionAmount/tradeInItems 必填 */
        private Boolean isTradeIn;

        /** 以旧换新抵扣金额（>0 且 ≤ 销售单价×数量） */
        private BigDecimal deductionAmount;

        /** 旧品明细（至少 1 条；旧品不在商品库时 isManual=true） */
        private List<TradeInItem> tradeInItems;
    }

    /** 以旧换新旧品明细。 */
    @Data
    public static class TradeInItem {

        /** SCM 商品ID；手工旧品可不传 */
        private Long productId;

        /** SCM 商品编码（后端按 productId 回查覆盖） */
        private String materialNumber;

        /** 旧品名称（手工旧品必填） */
        private String productName;

        private String brand;

        private String model;

        private String category;

        /** 旧品数量（正整数） */
        private Integer quantity;

        private String unit;

        /** 成色/故障等备注 */
        private String remark;

        /** true=其他旧品手工录入 */
        private Boolean isManual;
    }
}
