package com.aip.ops.mapper;

import com.aip.ops.entity.OpsActivity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OpsActivityMapper extends BaseMapper<OpsActivity> {

    /**
     * 分页查询活动列表（含关联商品数量）。
     * 通过 LEFT JOIN 子查询计算每个活动的商品数量。
     */
    @Select("""
        <script>
        SELECT a.*, COALESCE(pc.cnt, 0) AS product_count
        FROM ops_activity a
        LEFT JOIN (
            SELECT activity_id, COUNT(*) AS cnt
            FROM ops_activity_product
            GROUP BY activity_id
        ) pc ON pc.activity_id = a.id
        WHERE 1=1
        <if test='type != null and type != \"\"'>
            AND a.type = #{type}
        </if>
        <if test='category != null and category != \"\"'>
            AND a.category = #{category}
        </if>
        <if test='status != null'>
            AND a.status = #{status}
        </if>
        ORDER BY a.id DESC
        LIMIT #{offset}, #{size}
        </script>
    """)
    List<OpsActivity> selectActivityPage(@Param("offset") int offset,
                                        @Param("size") int size,
                                        @Param("type") String type,
                                        @Param("category") String category,
                                        @Param("status") Integer status);

    /** 计数（与 selectActivityPage 相同 WHERE 条件） */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ops_activity a WHERE 1=1
        <if test='type != null and type != \"\"'>
            AND a.type = #{type}
        </if>
        <if test='category != null and category != \"\"'>
            AND a.category = #{category}
        </if>
        <if test='status != null'>
            AND a.status = #{status}
        </if>
        </script>
    """)
    long countActivityPage(@Param("type") String type,
                           @Param("category") String category,
                           @Param("status") Integer status);
}
