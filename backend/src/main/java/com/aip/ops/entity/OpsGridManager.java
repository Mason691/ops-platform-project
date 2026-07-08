package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_grid_manager")
public class OpsGridManager extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long userId;
    private String phone;
    private String realName;
    private LocalDate startTime;
    private LocalDate endTime;
    private String remark;

    /** Transient: associated community IDs (for create/update). */
    @TableField(exist = false)
    private List<Long> communityIds;

    /** Transient: count of associated communities (for list display). */
    @TableField(exist = false)
    private Integer communityCount;

    /** Transient: community names joined with commas. */
    @TableField(exist = false)
    private String communityNames;
}
