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
 * 业务数据低价特批文件（员工上传留痕）。
 *
 * <p>订单提交时 item 引用 file_id；文件本身通过 /uploads/ 静态访问，
 * 本表记录上传人、上传时间与原始文件名，满足可追溯要求。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_special_file")
public class OpsBusinessSpecialFile extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 相对访问地址（/uploads/...） */
    @TableField("file_url")
    private String fileUrl;

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
}
