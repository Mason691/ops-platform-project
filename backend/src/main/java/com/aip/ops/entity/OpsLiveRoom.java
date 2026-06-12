package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 视频号直播配置。
 * 运营商可配置自己的视频号直播间，公众号H5端展示并支持跳转。
 */
@Data
@TableName("ops_live_room")
public class OpsLiveRoom {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运营商组织ID */
    @TableField("merchant_id")
    private Long merchantId;

    /** 视频号 finderUserName（如 sphxxxx@finder） */
    @TableField("finder_username")
    private String finderUsername;

    /** 直播标题 */
    @TableField("live_title")
    private String liveTitle;

    /** 直播封面图URL */
    @TableField("cover_url")
    private String coverUrl;

    /** 预计开播时间 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 预计结束时间 */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 状态: 0=预告 1=直播中 2=已结束 */
    @TableField("status")
    private Integer status;

    /** 排序（数字越小越靠前） */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 备注 */
    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
