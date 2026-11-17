package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 财务分类字典（ff_category）。
 *
 * <p>平台级基础数据，无 corp_id；分类编码取 Excel「分类」文本前五位（如 11.01），
 * 其余文本为分类名称。供后续财务收支业务引用。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ff_category")
public class FfCategory extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类编码（前五位，如 11.01） */
    @TableField("category_code")
    private String categoryCode;

    /** 分类名称（编码之后的文本） */
    @TableField("category_name")
    private String categoryName;

    /** 父级编码（按点号推导，如 11.01 → 11；无点号时为空） */
    @TableField("parent_code")
    private String parentCode;

    /** 层级：点号分隔的段数（11.01 → 2） */
    @TableField("category_level")
    private Integer level;

    /** 状态：1启用 0禁用 */
    @TableField("status")
    private Integer status;
}
