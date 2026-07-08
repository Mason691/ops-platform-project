package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_activity_review")
public class OpsActivityReview extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long activityId;
    private String summary;
    private String remark;
    private String attachment;
    private Integer reviewStatus;
    private String reviewComment;
    private Long reviewBy;
    private LocalDateTime reviewTime;

    /** Transient: costs list for CRUD */
    @TableField(exist = false)
    private List<OpsActivityReviewCost> costs;
    /** Transient: activity title */
    @TableField(exist = false)
    private String activityTitle;
}
