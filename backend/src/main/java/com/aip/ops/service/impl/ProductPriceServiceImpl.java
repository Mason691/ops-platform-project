package com.aip.ops.service.impl;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.client.ScmBaseDataClient;
import com.aip.ops.dto.ProductPriceDtos;
import com.aip.ops.entity.OpsProductPrice;
import com.aip.ops.mapper.OpsProductPriceMapper;
import com.aip.ops.service.CommissionRuleService;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.ProductPriceService;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品售价实现。
 *
 * <p>售价归属运营商组织；员工制单时从员工组织向上解析运营商后查价。
 * 写入仅运营商/超管（且必须切换到具体组织），城市合伙人只读。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPriceServiceImpl implements ProductPriceService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REMARK_LENGTH = 500;

    private final OpsProductPriceMapper priceMapper;
    private final ScmBaseDataClient scmClient;
    private final OrgRoleService orgRoleService;
    private final CommissionRuleService commissionRuleService;

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String brand, String category,
                                    String model, Integer status) {
        Long owner = ownerCorpForRead();
        int page = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        long total = priceMapper.countByCorp(owner, trimToNull(brand), trimToNull(category),
                trimToNull(model), status);
        List<OpsProductPrice> records = priceMapper.selectPageByCorp(owner, trimToNull(brand),
                trimToNull(category), trimToNull(model), status, (page - 1) * size, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", size);
        result.put("current", page);
        return result;
    }

    @Override
    public ScmBaseDataClient.ScmMaterialPage scmProducts(String keyword, long pageNum, long pageSize) {
        // 写入权限与商品选择权限一致：仅运营商/超管可进入新增；查询本身只要求登录
        return scmClient.searchMaterials(keyword, pageNum, Math.min(Math.max(1, pageSize), 100));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(ProductPriceDtos.ProductPriceRequest request) {
        Long corpId = requireOperatorCorp();
        Prices prices = validatePrices(request);
        String category = requireCategoryKey(request.category());
        ScmBaseDataClient.ScmMaterial material = scmClient.getMaterial(request.productId());
        if (material.id() == null) {
            throw BusinessException.of("供应链商品不存在或已删除");
        }
        OpsProductPrice existing = priceMapper.selectByCorpAndProductIncludingDeleted(
                corpId, material.id());
        if (existing != null && Integer.valueOf(0).equals(existing.getDeleteFlag())) {
            throw BusinessException.of("该商品已配置售价");
        }
        OpsProductPrice price = new OpsProductPrice();
        price.setCorpId(corpId);
        price.setProductId(material.id());
        price.setProductName(truncate(trimToNull(material.name()), 200));
        price.setBrand(truncate(material.brand(), 100));
        price.setModel(truncate(material.model(), 200));
        price.setCategory(category);
        price.setSalePrice(prices.sale());
        price.setRetailFloorPrice(prices.retail());
        price.setGroupLowestPrice(prices.group());
        price.setStatus(normalizeStatus(request.status()));
        price.setRemark(normalizeRemark(request.remark()));
        if (existing != null) {
            // 唯一键 (corp_id, product_id)：逻辑删除后重新新增时复用原行
            price.setId(existing.getId());
            priceMapper.restoreDeleted(price);
        } else {
            try {
                priceMapper.insert(price);
            } catch (DuplicateKeyException e) {
                throw BusinessException.of("该商品已配置售价");
            }
        }
        log.info("产品售价已保存 corpId={} productId={} sale={}", corpId, price.getProductId(),
                price.getSalePrice());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductPriceDtos.ProductPriceRequest request) {
        Long corpId = requireOperatorCorp();
        OpsProductPrice existing = requireOwned(id, corpId);
        Prices prices = validatePrices(request);
        String category = requireCategoryKey(request.category());
        String productName = existing.getProductName();
        String brand = existing.getBrand();
        String model = existing.getModel();
        // 品牌/型号以 SCM 商品信息为准，编辑时同步刷新冗余字段；
        // SCM 暂不可用不阻断价格维护，保留原冗余值。
        try {
            ScmBaseDataClient.ScmMaterial material = scmClient.getMaterial(existing.getProductId());
            if (material.id() != null) {
                productName = truncate(trimToNull(material.name()), 200);
                brand = truncate(material.brand(), 100);
                model = truncate(material.model(), 200);
            }
        } catch (BusinessException e) {
            log.warn("刷新 SCM 商品信息失败 productId={}: {}", existing.getProductId(),
                    e.getMessage());
        }
        String remark = normalizeRemark(request.remark());
        Integer status = request.status() != null
                ? normalizeStatus(request.status()) : existing.getStatus();
        // remark 允许清空：不能用 setXxx(null)+updateById（MyBatis-Plus 会忽略 null）
        priceMapper.update(null, new LambdaUpdateWrapper<OpsProductPrice>()
                .eq(OpsProductPrice::getId, id)
                .eq(OpsProductPrice::getCorpId, corpId)
                .set(OpsProductPrice::getProductName, productName)
                .set(OpsProductPrice::getBrand, brand)
                .set(OpsProductPrice::getModel, model)
                .set(OpsProductPrice::getCategory, category)
                .set(OpsProductPrice::getSalePrice, prices.sale())
                .set(OpsProductPrice::getRetailFloorPrice, prices.retail())
                .set(OpsProductPrice::getGroupLowestPrice, prices.group())
                .set(OpsProductPrice::getStatus, status)
                .set(OpsProductPrice::getRemark, remark)
                .set(OpsProductPrice::getModifyTime, LocalDateTime.now()));
        log.info("产品售价已更新 corpId={} id={} productId={}", corpId, id,
                existing.getProductId());
    }

    @Override
    public void delete(Long id) {
        Long corpId = requireOperatorCorp();
        requireOwned(id, corpId);
        priceMapper.deleteById(id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Long corpId = requireOperatorCorp();
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.of("状态不合法");
        }
        OpsProductPrice existing = requireOwned(id, corpId);
        existing.setStatus(status);
        priceMapper.updateById(existing);
    }

    @Override
    public ProductPriceDtos.ProductPriceInfo lookupForEmployee(Long employeeCorpId, Long productId,
                                                               String brand, String category,
                                                               String model) {
        if (employeeCorpId == null || employeeCorpId <= 0) {
            return notConfigured(productId);
        }
        Long owner = orgRoleService.getOperatorCorpId(employeeCorpId);
        OpsProductPrice price = null;
        if (productId != null && productId > 0) {
            price = priceMapper.selectActiveByProduct(owner, productId);
        }
        if (price == null && hasText(brand) && hasText(model)) {
            String categoryKey = categoryKeyOrNull(category);
            // category 列只存字典 itemKey；无法解析时不能省略品类条件去匹配，
            // 否则可能命中同品牌型号的其它品类价格，绕过特批判定。
            if (categoryKey != null) {
                price = priceMapper.selectActiveByFields(owner, brand.trim(), categoryKey,
                        model.trim());
            }
        }
        if (price == null) return notConfigured(productId);
        return new ProductPriceDtos.ProductPriceInfo(true, null, price.getProductId(),
                price.getSalePrice(), price.getRetailFloorPrice(), price.getGroupLowestPrice(),
                price.getCategory());
    }

    // ── helpers ──

    private ProductPriceDtos.ProductPriceInfo notConfigured(Long productId) {
        return new ProductPriceDtos.ProductPriceInfo(false,
                "该商品未配置售价或已停用，请联系管理员", productId, null, null, null, null);
    }

    private OpsProductPrice requireOwned(Long id, Long corpId) {
        OpsProductPrice price = id == null ? null : priceMapper.selectById(id);
        if (price == null) throw BusinessException.notFound("售价记录不存在");
        if (!corpId.equals(price.getCorpId())) {
            throw BusinessException.forbidden("无权操作其他组织的产品售价");
        }
        return price;
    }

    private Long requireOperatorCorp() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请切换到具体运营商组织后维护产品售价");
        }
        if (!orgRoleService.isOperatorOrSuperAdmin() || orgRoleService.isCityPartner(corpId)) {
            throw BusinessException.forbidden("仅运营商管理员可维护产品售价");
        }
        return corpId;
    }

    /** 读取归属：运营商=自身，城市合伙人=父运营商，超管未切组织=全部。 */
    private Long ownerCorpForRead() {
        Long current = SecurityUtils.getCurrentCorpIdOrNull();
        if (current == null || current == 0L) return null;
        return orgRoleService.getOperatorCorpId(current);
    }

    private Prices validatePrices(ProductPriceDtos.ProductPriceRequest request) {
        if (request == null) throw BusinessException.of("请求参数不能为空");
        BigDecimal sale = normalizePrice(request.salePrice(), "销售价");
        BigDecimal retail = normalizePrice(request.retailFloorPrice(), "零售底价");
        BigDecimal group = normalizePrice(request.groupLowestPrice(), "集团最低价");
        if (sale.compareTo(retail) < 0 || retail.compareTo(group) < 0) {
            throw BusinessException.of("价格需满足：销售价 ≥ 零售底价 ≥ 集团最低价");
        }
        return new Prices(sale, retail, group);
    }

    private BigDecimal normalizePrice(BigDecimal raw, String label) {
        if (raw == null) throw BusinessException.of(label + "不能为空");
        BigDecimal value = raw.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) throw BusinessException.of(label + "必须大于0");
        return value;
    }

    /** 品类必须命中 PRODUCT_CATEGORY 字典（key 或中文名均可，落库统一 itemKey）。 */
    private String requireCategoryKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw BusinessException.of("请选择品类");
        String key = categoryKeyOrNull(value);
        if (key == null) throw BusinessException.of("品类不在 PRODUCT_CATEGORY 字典中");
        return key;
    }

    private String categoryKeyOrNull(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        // SCM 类别名与字典名称不一致的兼容（与 ScmBaseDataClient 别名保持一致）
        String alias = switch (value) {
            case "灶具" -> "1";
            case "烟机" -> "2";
            case "燃气热水器", "热水器" -> "3";
            case "壁挂炉" -> "4";
            default -> null;
        };
        if (alias != null) return alias;
        try {
            for (Map<String, String> item : commissionRuleService.getCategories()) {
                String key = item.get("itemKey");
                String label = item.get("itemValue");
                if (value.equals(key) || value.equals(label)) return key;
            }
        } catch (Exception e) {
            log.warn("加载 PRODUCT_CATEGORY 字典失败: {}", e.getMessage());
        }
        return null;
    }

    private String normalizeRemark(String value) {
        String remark = value == null ? "" : value.trim();
        if (remark.length() > MAX_REMARK_LENGTH) {
            throw BusinessException.of("备注不能超过" + MAX_REMARK_LENGTH + "个字");
        }
        return remark.isEmpty() ? null : remark;
    }

    private Integer normalizeStatus(Integer status) {
        if (status == null) return 1;
        if (status != 0 && status != 1) {
            throw BusinessException.of("状态不合法");
        }
        return status;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private record Prices(BigDecimal sale, BigDecimal retail, BigDecimal group) {
    }
}
