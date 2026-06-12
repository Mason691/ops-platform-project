package com.aip.ops.mapper;
import com.aip.ops.entity.GsProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * ops_product 关系映射表 Mapper。
 * 提供 JOIN mall_product 的查询方法，获取完整商品信息。
 */
@Mapper
public interface GsProductMapper extends BaseMapper<GsProduct> {

    /**
     * 分页查询运营商关联的商品列表（JOIN mall_product）。
     * 仅返回当前运营商通过 ops_product 引入、且所属销售商在绑定列表中的商品。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount, "
            + "       gp.create_time AS createTime, "
            + "       mp.id AS productId, "
            + "       mp.corp_id AS merchantId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       mp.images, "
            + "       mp.video_url AS videoUrl, "
            + "       mp.description, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.sale_price, 0) AS salePrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       COALESCE(mp.click_count, 0) AS clickCount, "
            + "       mp.status "
            + "FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.operator_id = #{operatorId} "
            + "  AND mp.corp_id IN "
            + "  <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
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
                                                 @Param("corpIds") List<Long> corpIds,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size,
                                                 @Param("shelfStatus") Integer shelfStatus,
                                                 @Param("categoryId") Long categoryId,
                                                 @Param("keyword") String keyword);

    /** 统计总数（与 selectProductList 相同的过滤条件） */
    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.operator_id = #{operatorId} "
            + "  AND mp.corp_id IN "
            + "  <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
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
                          @Param("corpIds") List<Long> corpIds,
                          @Param("shelfStatus") Integer shelfStatus,
                          @Param("categoryId") Long categoryId,
                          @Param("keyword") String keyword);

    /**
     * 权限校验：检查指定 mappingId 列表中的商品是否都属于给定的销售商集合。
     * 返回属于绑定销售商的 mappingId 列表，调用方通过比较列表大小判断是否有越权操作。
     */
    @Select("<script>"
            + "SELECT gp.id FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND mp.corp_id IN "
            + "<foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
            + "</script>")
    List<Long> selectIdsInMerchants(@Param("ids") List<Long> ids,
                                     @Param("corpIds") List<Long> corpIds);

    /**
     * 通过映射ID查询单个商品详情（JOIN mall_product）
     */
    @Select("SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount, "
            + "       gp.create_time AS createTime, "
            + "       mp.id AS productId, "
            + "       mp.corp_id AS merchantId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       mp.images, "
            + "       mp.video_url AS videoUrl, "
            + "       mp.description, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.sale_price, 0) AS salePrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       COALESCE(mp.click_count, 0) AS clickCount, "
            + "       mp.status "
            + "FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.id = #{mappingId}")
    Map<String, Object> selectProductByMappingId(@Param("mappingId") Long mappingId);

    /**
     * 分页查询所有上架商品（用户端浏览），跨运营商。
     * 仅返回 shelf_status=1 且 mp.status=1 的商品。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       gp.category_id AS categoryId, "
            + "       mp.id AS productId, "
            + "       mp.corp_id AS merchantId, "
            + "       mp.name, "
            + "       mp.cover_image AS coverImage, "
            + "       COALESCE(mp.points_price, 0) AS pointsPrice, "
            + "       COALESCE(mp.sale_price, 0) AS salePrice, "
            + "       COALESCE(mp.max_points_deduct, 0) AS maxPointsDeduct, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount, "
            + "       mp.description, "
            + "       COALESCE(mp.click_count, 0) AS clickCount "
            + "FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.shelf_status = 1 "
            + "  AND mp.status = 1 "
            + "  AND mp.corp_id IN "
            + "  <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "ORDER BY gp.id DESC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectPublishedProducts(@Param("corpIds") List<Long> corpIds,
                                                       @Param("offset") int offset,
                                                       @Param("size") int size,
                                                       @Param("categoryId") Long categoryId,
                                                       @Param("keyword") String keyword);

    /** 上架商品总数 */
    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM ops_product gp "
            + "INNER JOIN mall_product mp ON gp.mall_product_id = mp.id "
            + "WHERE gp.shelf_status = 1 "
            + "  AND mp.status = 1 "
            + "  AND mp.corp_id IN "
            + "  <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND mp.name LIKE CONCAT('%', #{keyword}, '%') "
            + "</if>"
            + "</script>")
    long countPublishedProducts(@Param("corpIds") List<Long> corpIds,
                                @Param("categoryId") Long categoryId,
                                @Param("keyword") String keyword);
}
