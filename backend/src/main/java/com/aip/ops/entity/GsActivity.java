package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("ops_activity")
public class GsActivity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("title") private String title;
    @TableField("cover_image") private String coverImage;
    @TableField("images") private String images;
    @TableField("video_url") private String videoUrl;
    @TableField("detail") private String detail;
    @TableField("category") private String category;
    @TableField("start_time") private LocalDateTime startTime;
    @TableField("end_time") private LocalDateTime endTime;
    @TableField("location") private String location;
    @TableField("points_reward") private Integer pointsReward;
    @TableField("max_participants") private Integer maxParticipants;
    @TableField("status") private Integer status;
}
