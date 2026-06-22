package com.aip.ops.mapper;
import com.aip.ops.entity.OpsProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * ops_product 关系映射表 Mapper。
 * 仅查询 ops_product 本地表，不再 JOIN mall_product（跨库查询已通过 ProductBatchClient 替代）。
 */
@Mapper
public interface OpsProductMapper extends BaseMapper<OpsProduct> {

    /**
     * 分页查询运营商关联的商品映射列表（仅查 ops_product，不含 mall_product JOIN）。
     * 商品详情通过 ProductBatchClient 批量获取后在 Service 层组装。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount, "
            + "       gp.mall_product_id AS mallProductId, "
            + "       gp.create_time AS createTime "
            + "FROM ops_product gp "
            + "WHERE gp.operator_id = #{operatorId} "
            + "<if test='shelfStatus != null'>"
            + "  AND gp.shelf_status = #{shelfStatus} "
            + "</if>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "ORDER BY gp.id DESC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectProductList(@Param("operatorId") Long operatorId,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size,
                                                 @Param("shelfStatus") Integer shelfStatus,
                                                 @Param("categoryId") Long categoryId);

    /** 统计总数（与 selectProductList 相同的过滤条件，不含 mall_product JOIN） */
    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM ops_product gp "
            + "WHERE gp.operator_id = #{operatorId} "
            + "<if test='shelfStatus != null'>"
            + "  AND gp.shelf_status = #{shelfStatus} "
            + "</if>"
            + "<if test='categoryId != null'>"
            + "  AND gp.category_id = #{categoryId} "
            + "</if>"
            + "</script>")
    long countProductList(@Param("operatorId") Long operatorId,
                          @Param("shelfStatus") Integer shelfStatus,
                          @Param("categoryId") Long categoryId);

    /**
     * 权限校验：检查指定 mappingId 列表中的商品映射是否存在。
     * 仅查 ops_product，不再 JOIN mall_product 校验销售商归属。
     * 销售商归属校验改为通过 ProductBatchClient 获取商品 corpId 后在 Service 层完成。
     */
    @Select("<script>"
            + "SELECT gp.id FROM ops_product gp "
            + "WHERE gp.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectIdsExist(@Param("ids") List<Long> ids);

    /**
     * 通过映射ID查询单个商品映射详情（仅 ops_product，不含 mall_product JOIN）
     */
    @Select("SELECT gp.id AS mappingId, "
            + "       gp.operator_id AS operatorId, "
            + "       gp.shelf_status AS shelfStatus, "
            + "       gp.category_id AS categoryId, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount, "
            + "       gp.mall_product_id AS mallProductId, "
            + "       gp.create_time AS createTime "
            + "FROM ops_product gp "
            + "WHERE gp.id = #{mappingId}")
    Map<String, Object> selectProductByMappingId(@Param("mappingId") Long mappingId);

    /**
     * 分页查询所有上架商品映射（用户端浏览），仅查 ops_product。
     */
    @Select("<script>"
            + "SELECT gp.id AS mappingId, "
            + "       gp.category_id AS categoryId, "
            + "       gp.mall_product_id AS mallProductId, "
            + "       gp.points, "
            + "       gp.exchange_amount AS exchangeAmount "
            + "FROM ops_product gp "
            + "WHERE gp.shelf_status = 1 "
            + "ORDER BY gp.id DESC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectPublishedProducts(@Param("offset") int offset,
                                                       @Param("size") int size);

    /** 上架商品总数（仅查 ops_product） */
    @Select("SELECT COUNT(*) FROM ops_product gp WHERE gp.shelf_status = 1")
    long countPublishedProducts();
}
