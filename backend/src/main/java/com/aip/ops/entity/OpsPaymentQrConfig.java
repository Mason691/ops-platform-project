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
 * 收款码配置：每个运营商组织一条，登记文件路径与 SHA-256。
 *
 * <p>取图接口每次重新计算文件哈希并与 file_hash 比对，
 * 不一致即拒绝返回并告警，防止服务器文件被直接替换。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_payment_qr_config")
public class OpsPaymentQrConfig extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 相对上传根目录的路径（不对外暴露） */
    @TableField("file_path")
    private String filePath;

    /** SHA-256（小写十六进制） */
    @TableField("file_hash")
    private String fileHash;

    @TableField("file_size")
    private Long fileSize;

    @TableField("content_type")
    private String contentType;

    @TableField("uploader_user_id")
    private Long uploaderUserId;

    @TableField("uploader_name")
    private String uploaderName;

    @TableField("upload_time")
    private LocalDateTime uploadTime;

    /** 1=启用 0=禁用 */
    @TableField("status")
    private Integer status;
}
