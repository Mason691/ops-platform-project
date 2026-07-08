package com.aip.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ops_grid_manager_community")
public class OpsGridManagerCommunity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long gridManagerId;
    private Long communityId;
}
