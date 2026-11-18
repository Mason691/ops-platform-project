package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 业务订单附件（收据图片 / 支付凭证 / 合同附件）。
 *
 * <p>上传时先落本表（order_id 为空），制单提交时校验 corp/类型/归属并回填
 * order_id；订单头同时保存 URL 列表，便于列表/详情直接展示。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_attachment")
public class OpsBusinessAttachment extends BaseEntity {

    public static final String TYPE_RECEIPT = "RECEIPT";
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_CONTRACT = "CONTRACT";
    public static final String TYPE_REFUND = "REFUND";
    /** 完工验收凭证：图片或短视频，验收节点强制至少 1 个。 */
    public static final String TYPE_ACCEPTANCE = "ACCEPTANCE";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 关联订单 ID；提交前为空。 */
    @TableField("order_id")
    private Long orderId;

    /** RECEIPT / PAYMENT / CONTRACT / REFUND。 */
    @TableField("file_type")
    private String fileType;

    /** 原始文件名。 */
    @TableField("file_name")
    private String fileName;

    /** 相对访问地址（/uploads/ops/...），不暴露服务器路径。 */
    @TableField("file_url")
    private String fileUrl;

    /** 压缩后字节数。 */
    @TableField("file_size")
    private Long fileSize;

    @TableField("content_type")
    private String contentType;

    /** 视频时长（秒）；图片为空。用于验收短视频 ≤30 秒的服务端复核。 */
    @TableField("duration_seconds")
    private Integer durationSeconds;

    @TableField("uploader_user_id")
    private Long uploaderUserId;

    @TableField("uploader_name")
    private String uploaderName;

    @TableField("upload_time")
    private LocalDateTime uploadTime;
}
