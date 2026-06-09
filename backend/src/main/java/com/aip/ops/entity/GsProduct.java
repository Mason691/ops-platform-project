package com.aip.ops.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

@Data @TableName("gs_product")
public class GsProduct {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("corp_id") private Long corpId;
    @TableField("category_id") private Long categoryId;
    @TableField("name") private String name;
    @TableField("cover_image") private String coverImage;
    @TableField("images") private String images;
    @TableField("video_url") private String videoUrl;
    @TableField("description") private String description;
    @TableField("points_price") private Integer pointsPrice;
    @TableField("cash_price") private BigDecimal cashPrice;
    @TableField("max_points_deduct") private Integer maxPointsDeduct;
    @TableField("click_count") private Integer clickCount;
    @TableField("status") private Integer status;
}
