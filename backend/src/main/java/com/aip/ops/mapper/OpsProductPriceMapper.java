package com.aip.ops.mapper;

import com.aip.ops.entity.OpsProductPrice;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 产品售价 Mapper。
 *
 * <p>售价按运营商组织归属；城市合伙人读取父运营商配置时必须显式按
 * corp_id 查询，因此这些查询跳过 TenantLine 自动过滤（SQL 内自带 corp_id）。</p>
 */
@Mapper
public interface OpsProductPriceMapper extends BaseMapper<OpsProductPrice> {

    @InterceptorIgnore(tenantLine = "true")
    List<OpsProductPrice> selectPageByCorp(@Param("corpId") Long corpId,
                                           @Param("brand") String brand,
                                           @Param("category") String category,
                                           @Param("model") String model,
                                           @Param("status") Integer status,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    long countByCorp(@Param("corpId") Long corpId,
                     @Param("brand") String brand,
                     @Param("category") String category,
                     @Param("model") String model,
                     @Param("status") Integer status);

    @InterceptorIgnore(tenantLine = "true")
    OpsProductPrice selectActiveByProduct(@Param("corpId") Long corpId,
                                          @Param("productId") Long productId);

    @InterceptorIgnore(tenantLine = "true")
    OpsProductPrice selectActiveByFields(@Param("corpId") Long corpId,
                                         @Param("brand") String brand,
                                         @Param("category") String category,
                                         @Param("model") String model);

    /** 含逻辑删除行：新增时若命中已删除记录则复用恢复（唯一键 corp_id+product_id）。 */
    @InterceptorIgnore(tenantLine = "true")
    OpsProductPrice selectByCorpAndProductIncludingDeleted(@Param("corpId") Long corpId,
                                                           @Param("productId") Long productId);

    /** 恢复逻辑删除行并覆盖字段（唯一键 corp_id+product_id 不允许重复插入）。 */
    @InterceptorIgnore(tenantLine = "true")
    int restoreDeleted(OpsProductPrice price);
}
