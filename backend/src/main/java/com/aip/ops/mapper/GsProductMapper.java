package com.aip.ops.mapper;
import com.aip.ops.entity.GsProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * gs_product 关系映射表 Mapper。
 * 提供 JOIN mall_product 的查询方法，获取完整商品信息。
 */
@Mapper
public interface GsProductMapper extends BaseMapper<GsProduct> {

    /**
     * 分页查询运营商关联的商品列表（JOIN mall_product）。
     * 仅返回当前运营商通过 gs_product 引入的商品。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.create_time AS createTime, "
            + "       mp.id AS productId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       mp.images, "
            + "       mp.video_url AS videoUrl, "
            + "       mp.description, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.cash_price, mp.sale_price, 0) AS cashPrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       COALESCE(mp.click_count, 0) AS clickCount, "
            + "       mp.status "
            + "FROM gs_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.operator_id = #{operatorId} "
            + "  AND mp.corp_id = #{corpId} "
            + "<if test='shelfStatus != null'>"
            + "  AND gp.shelf_status = #{shelfStatus} "
            + "</if>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "ORDER BY gp.id DESC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectProductList(@Param("operatorId") Long operatorId,
                                                 @Param("corpId") Long corpId,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size,
                                                 @Param("shelfStatus") Integer shelfStatus,
                                                 @Param("categoryId") Long categoryId,
                                                 @Param("keyword") String keyword);

    /** 统计总数（与 selectProductList 相同的过滤条件） */
    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM gs_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.operator_id = #{operatorId} "
            + "  AND mp.corp_id = #{corpId} "
            + "<if test='shelfStatus != null'>"
            + "  AND gp.shelf_status = #{shelfStatus} "
            + "</if>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "</script>")
    long countProductList(@Param("operatorId") Long operatorId,
                          @Param("corpId") Long corpId,
                          @Param("shelfStatus") Integer shelfStatus,
                          @Param("categoryId") Long categoryId,
                          @Param("keyword") String keyword);

    /**
     * 通过映射ID查询单个商品详情（JOIN mall_product）
     */
    @Select("SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.create_time AS createTime, "
            + "       mp.id AS productId, "
            + "       mp.corp_id AS corpId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       mp.images, "
            + "       mp.video_url AS videoUrl, "
            + "       mp.description, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.cash_price, mp.sale_price, 0) AS cashPrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       COALESCE(mp.click_count, 0) AS clickCount, "
            + "       mp.status "
            + "FROM gs_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.id = #{mappingId}")
    Map<String, Object> selectProductByMappingId(@Param("mappingId") Long mappingId);

    /**
     * 分页查询所有上架商品（用户端浏览），跨运营商。
     * 仅返回 shelf_status=1 且 mp.status=1 的商品。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       mp.id AS productId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.cash_price, mp.sale_price, 0) AS cashPrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       mp.description, "
            + "       COALESCE(mp.click_count, 0) AS clickCount "
            + "FROM gs_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.shelf_status = 1 "
            + "  AND mp.status = 1 "
            + "  AND mp.corp_id = #{corpId} "
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "ORDER BY gp.id DESC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectPublishedProducts(@Param("corpId") Long corpId,
                                                       @Param("offset") int offset,
                                                       @Param("size") int size,
                                                       @Param("categoryId") Long categoryId,
                                                       @Param("keyword") String keyword);

    /** 上架商品总数 */
    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM gs_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.shelf_status = 1 "
            + "  AND mp.status = 1 "
            + "  AND mp.corp_id = #{corpId} "
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "</script>")
    long countPublishedProducts(@Param("corpId") Long corpId,
                                @Param("categoryId") Long categoryId,
                                @Param("keyword") String keyword);
}
