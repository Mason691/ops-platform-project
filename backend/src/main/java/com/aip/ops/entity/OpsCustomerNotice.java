package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户施工用户告知书：服务端内容快照 + 客户手写签字图片 + SHA-256 哈希。
 *
 * <p>签字版本递增；重签时旧版本置 status=0 保留，开工后不可再改。
 * 图片存于受控上传目录，不直接暴露服务器路径。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_customer_notice")
public class OpsCustomerNotice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    @TableField("order_no")
    private String orderNo;

    @TableField("worker_id")
    private Long workerId;

    @TableField("worker_name")
    private String workerName;

    @TableField("customer_name")
    private String customerName;

    /** 告知书内容快照（施工员、上门时间、订单与商品摘要）。 */
    @TableField("notice_content")
    private String noticeContent;

    @TableField("notice_time")
    private LocalDateTime noticeTime;

    @TableField("sign_image_url")
    private String signImageUrl;

    /** 签字图片原始字节 SHA-256（小写十六进制），用于防篡改核验。 */
    @TableField("sign_image_hash")
    private String signImageHash;

    @TableField("sign_time")
    private LocalDateTime signTime;

    @TableField("sign_location_lng")
    private BigDecimal signLocationLng;

    @TableField("sign_location_lat")
    private BigDecimal signLocationLat;

    @TableField("version")
    private Integer version;

    /** 1=有效 0=作废（重签后旧版本）。 */
    @TableField("status")
    private Integer status;

    /** 归档PDF地址（预留；当前由前端打印代替）。 */
    @TableField("pdf_url")
    private String pdfUrl;
}
