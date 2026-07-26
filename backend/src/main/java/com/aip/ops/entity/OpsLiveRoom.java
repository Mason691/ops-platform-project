package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * 视频号直播配置。
 * 运营商可配置自己的视频号直播间，公众号H5端展示并支持跳转。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_live_room")
public class OpsLiveRoom extends BaseEntity {
    /** createTime / modifyTime 由 BaseEntity 继承 */
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 运营商组织ID */
    @TableField("merchant_id")
    private Long merchantId;

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

    /** 回放视频URL（直播结束后运营填写） */
    @TableField("replay_url")
    private String replayUrl;

    /** 观看人数（运营手动填写或API获取） */
    @TableField("viewer_count")
    private Integer viewerCount;

    /** 实际上播时间 */
    @TableField("actual_start_time")
    private LocalDateTime actualStartTime;

    /** 实际结束时间 */
    @TableField("actual_end_time")
    private LocalDateTime actualEndTime;

    /** 排序（数字越小越靠前） */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 直播间简介（展示在跳转前页面，支持换行） */
    @TableField("description")
    private String description;

    /** 第三方直播链接（腾讯云/阿里云等） */
    @TableField("live_link")
    private String liveLink;
}
