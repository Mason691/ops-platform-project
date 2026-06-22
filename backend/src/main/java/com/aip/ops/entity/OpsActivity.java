package com.aip.ops.entity;
import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_activity")
public class OpsActivity extends BaseEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("title") private String title;
    @TableField("cover_image") private String coverImage;
    @TableField("cover_url") private String coverUrl;
    @TableField("images") private String images;
    @TableField("video_url") private String videoUrl;
    @TableField("video_cover_url") private String videoCoverUrl;
    @TableField("detail_video_url") private String detailVideoUrl;
    @TableField("detail_images") private String detailImages;
    @TableField("type") private String type;
    @TableField("live_room_id") private Long liveRoomId;
    @TableField("detail") private String detail;
    @TableField("category") private String category;
    @TableField("start_time") private LocalDateTime startTime;
    @TableField("end_time") private LocalDateTime endTime;
    @TableField("location") private String location;
    @TableField("points_reward") private Integer pointsReward;
    @TableField("max_participants") private Integer maxParticipants;
    @TableField("status") private Integer status;

    /** 关联商品数量（非数据库字段，由列表查询 JOIN 计算） */
    @TableField(exist = false)
    private Integer productCount;
}
