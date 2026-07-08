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

    /**
     * 可见范围分页查询。
     * 合伙人(corpId非空): 自己创建的 + 运营商指定给自己的(is_operator_initiated=1 AND FIND_IN_SET(corpId, partner_corp_ids))
     * 超管(corpId为空/null): 全可见
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
        WHERE a.delete_flag = 0
        <if test='corpId != null and corpId != \"\"'>
            AND (a.corp_id = #{corpId} OR (a.is_operator_initiated = 1 AND FIND_IN_SET(#{corpId}, a.partner_corp_ids) > 0))
        </if>
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
    List<OpsActivity> selectActivityPageVisible(@Param("offset") int offset,
                                               @Param("size") int size,
                                               @Param("type") String type,
                                               @Param("category") String category,
                                               @Param("status") Integer status,
                                               @Param("corpId") String corpId);

    @Select("""
        <script>
        SELECT COUNT(*) FROM ops_activity a WHERE a.delete_flag = 0
        <if test='corpId != null and corpId != \"\"'>
            AND (a.corp_id = #{corpId} OR (a.is_operator_initiated = 1 AND FIND_IN_SET(#{corpId}, a.partner_corp_ids) > 0))
        </if>
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
    long countActivityPageVisible(@Param("type") String type,
                                  @Param("category") String category,
                                  @Param("status") Integer status,
                                  @Param("corpId") String corpId);
}
