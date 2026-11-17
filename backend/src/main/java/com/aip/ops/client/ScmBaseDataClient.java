package com.aip.ops.client;

import com.aip.ops.service.CommissionRuleService;
import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供应链基础资料代理（品牌 / 商品类别 / 商品型号）。
 *
 * <p>员工手工录入业务数据时，品牌/品类/型号必须来自供应链管理模块。ops 子系统
 * 不直连 SCM 库，而是携带当前员工 JWT 调用 scm-mehome-service 的既有只读接口：
 * <ul>
 *   <li>{@code GET /api/bd/brand/options} — 品牌选项</li>
 *   <li>{@code GET /api/bd/material-category/options} — 商品类别树</li>
 *   <li>{@code GET /api/bd/material/page} — 商品信息（含品牌名/类别名/规格型号）</li>
 * </ul>
 * 转发员工 JWT 而非内部 token：SCM 的共享域拦截器依赖 JWT corp_id 解析所属运营商，
 * 城市合伙人才能看到运营商维护的基础资料。
 *
 * <p>基础资料 5 分钟进程内缓存（key=corpId）。SCM 不可达时抛 BusinessException，
 * 由调用方转为「表单不可提交」的明确提示，不做本地兜底数据。</p>
 */
@Slf4j
@Component
public class ScmBaseDataClient {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_PAGES = 20;
    private static final int MAX_CACHE_ENTRIES = 50;

    /**
     * 品类名称 → 字典 itemKey 的兜底映射（仅用于同名映射，不再用于过滤品类）。
     * SCM 类别名与字典不一致时（如 燃气具/休闲食品/家用电器），保留 SCM 类别名称。
     */
    private static final Map<String, String> FALLBACK_CATEGORY_KEYS = Map.ofEntries(
            Map.entry("燃气灶", "1"), Map.entry("抽油烟机", "2"),
            Map.entry("热水器", "3"), Map.entry("壁挂炉", "4"),
            Map.entry("壁挂炉以旧换新销售", "5"), Map.entry("户内美装销售", "6"),
            Map.entry("定尺波纹管", "7"), Map.entry("户内美装工程", "8"),
            Map.entry("户内美装运维", "9"), Map.entry("保险", "10"),
            Map.entry("美装管道整改", "11"), Map.entry("美装安全评估", "12"),
            Map.entry("气密性检测", "qimi"));

    /**
     * SCM 商品类别名称与 PRODUCT_CATEGORY 字典名称不一致时的别名。
     * 生产 SCM 类别为「灶具/烟机/燃气热水器」等，若不映射会落库非字典 key，
     * 导致提成规则/统计按品类匹配失败。
     */
    private static final Map<String, String> CATEGORY_ALIASES = Map.of(
            "灶具", "1", "烟机", "2", "燃气热水器", "3", "热水器", "3", "壁挂炉", "4");

    private final RestTemplate restTemplate;
    private final CommissionRuleService commissionRuleService;
    private final String baseUrl;

    private final Map<Long, CachedOptions> cache = new ConcurrentHashMap<>();
    /** 冷缓存并发去重：同一 corp 只允许一个线程真正拉取 SCM 目录 */
    private final Map<Long, Object> fetchLocks = new ConcurrentHashMap<>();

    public ScmBaseDataClient(RestTemplate restTemplate,
                             CommissionRuleService commissionRuleService,
                             @Value("${scm-service.base-url:http://localhost:8084}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.commissionRuleService = commissionRuleService;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 品牌/品类/型号选项（含 5 分钟缓存）。 */
    public ScmOptions options(Long corpId) {
        long key = corpId != null ? corpId : 0L;
        CachedOptions cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt > now) {
            return cached.options;
        }
        Object lock = fetchLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = cache.get(key);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                return cached.options;
            }
            ScmOptions fresh = fetchOptions();
            // 空结果不缓存：管理员刚同步基础资料时，下一次打开表单即可看到，不必等 TTL 过期
            if (!fresh.brands().isEmpty() || !fresh.categories().isEmpty()
                    || !fresh.materials().isEmpty()) {
                if (cache.size() > MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
                cache.put(key, new CachedOptions(fresh, now + CACHE_TTL_MS));
            }
            return fresh;
        }
    }

    /**
     * 校验品类存在于 SCM 商品类别（scm_bd_material_group），返回落库值：
     * 与 PRODUCT_CATEGORY 字典同名 → 字典 itemKey（兼容提成规则/ETL）；
     * 未同名 → SCM 类别名称（如 燃气具/休闲食品/家用电器）。
     */
    public String requireCategoryKey(Long corpId, String categoryName) {
        ScmOptions opts = options(corpId);
        return opts.categories().stream()
                .filter(c -> c.name().equals(categoryName))
                .map(CategoryOption::key)
                .findFirst()
                .orElseThrow(() -> BusinessException.of(
                        "品类「" + categoryName + "」未在供应链商品类别中找到"));
    }

    /** 校验 品牌+品类+型号 组合存在于 SCM 商品资料。 */
    public void requireMaterial(Long corpId, String brand, String categoryName, String model) {
        ScmOptions opts = options(corpId);
        boolean exists = opts.materials().stream().anyMatch(m ->
                m.brand().equals(brand) && m.category().equals(categoryName) && m.model().equals(model));
        if (!exists) {
            throw BusinessException.of("品牌/品类/型号未在供应链商品资料中找到："
                    + brand + " / " + categoryName + " / " + model);
        }
    }

    /**
     * 按 SCM 商品ID校验提交的品牌/品类/型号确实属于该商品，防止客户端
     * 用低价商品的 productId 套高价商品的品牌型号绕过售价特批校验。
     * 复用 options 的 5 分钟缓存，不额外放大 SCM 压力。
     */
    public MaterialOption requireMaterialById(Long corpId, Long productId, String brand,
                                              String categoryName, String model) {
        if (productId == null || productId <= 0) {
            throw BusinessException.of("商品ID不能为空");
        }
        ScmOptions opts = options(corpId);
        MaterialOption material = opts.materials().stream()
                .filter(m -> productId.equals(m.id()))
                .findFirst()
                .orElseThrow(() -> BusinessException.of(
                        "供应链商品不存在或已停用，请重新选择型号"));
        if (!material.brand().equals(brand) || !material.category().equals(categoryName)
                || !material.model().equals(model)) {
            throw BusinessException.of("商品与品牌/品类/型号不一致，请重新选择型号");
        }
        return material;
    }

    /**
     * 按 SCM 商品ID取权威商品资料（物资领用/核销快照用）。
     * 复用 options 的 5 分钟缓存，不额外放大 SCM 压力。
     */
    public MaterialOption requireMaterial(Long corpId, Long productId) {
        if (productId == null || productId <= 0) {
            throw BusinessException.of("请选择商品");
        }
        return options(corpId).materials().stream()
                .filter(m -> productId.equals(m.id()))
                .findFirst()
                .orElseThrow(() -> BusinessException.of("商品不存在或已停用，请重新选择"));
    }

    // ── fetch ──

    private ScmOptions fetchOptions() {
        String auth = currentBearerToken();
        List<String> brands = fetchBrands(auth);
        Set<String> brandSet = new LinkedHashSet<>(brands);
        List<CategoryOption> categories = fetchCategories(auth);
        Set<String> categoryNames = new LinkedHashSet<>();
        for (CategoryOption c : categories) categoryNames.add(c.name());
        List<MaterialOption> materials = fetchMaterials(auth, brandSet, categoryNames);
        return new ScmOptions(brands, categories, materials);
    }

    private List<String> fetchBrands(String auth) {
        List<Map<String, Object>> rows = dataList(getJson(auth, "/api/bd/brand/options"));
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("name"));
            if (!name.isEmpty()) names.add(name);
        }
        List<String> result = new ArrayList<>(names);
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /**
     * 读取 SCM 商品类别全量（scm_bd_material_group），不再按字典硬过滤。
     * 城市合伙人登录时 SCM 共享域返回其所属运营商（parent_corp_id）维护的类别；
     * 类别名与字典同名才映射 itemKey，否则保留类别名称，避免运营商目录被清空。
     */
    private List<CategoryOption> fetchCategories(String auth) {
        List<Map<String, Object>> nodes = dataList(getJson(auth, "/api/bd/material-category/options"));
        Map<String, String> nameToKey = categoryDict();
        Set<String> seen = new LinkedHashSet<>();
        List<CategoryOption> result = new ArrayList<>();
        flattenCategories(nodes, nameToKey, seen, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenCategories(List<Map<String, Object>> nodes, Map<String, String> nameToKey,
                                   Set<String> seen, List<CategoryOption> out) {
        if (nodes == null) return;
        for (Map<String, Object> node : nodes) {
            String name = str(node.get("name"));
            // 字典同名 → itemKey；未同名 → SCM 类别名称（保持运营商目录完整）
            String key = nameToKey.getOrDefault(name, name);
            if (!name.isEmpty() && seen.add(name)) {
                out.add(new CategoryOption(key, name));
            }
            Object children = node.get("children");
            if (children instanceof List<?> list) {
                List<Map<String, Object>> childMaps = new ArrayList<>();
                for (Object child : list) {
                    if (child instanceof Map<?, ?> map) {
                        childMaps.add((Map<String, Object>) map);
                    }
                }
                flattenCategories(childMaps, nameToKey, seen, out);
            }
        }
    }

    private Map<String, String> categoryDict() {
        Map<String, String> nameToKey = new LinkedHashMap<>();
        try {
            for (Map<String, String> item : commissionRuleService.getCategories()) {
                String name = item.get("itemValue");
                String key = item.get("itemKey");
                if (name != null && key != null && !name.isBlank()) {
                    nameToKey.putIfAbsent(name.trim(), key.trim());
                }
            }
        } catch (Exception e) {
            log.warn("加载 PRODUCT_CATEGORY 字典失败，使用兜底品类: {}", e.getMessage());
        }
        if (nameToKey.isEmpty()) {
            nameToKey.putAll(FALLBACK_CATEGORY_KEYS);
        }
        CATEGORY_ALIASES.forEach(nameToKey::putIfAbsent);
        return nameToKey;
    }

    private List<MaterialOption> fetchMaterials(String auth, Set<String> brands,
                                                Set<String> categories) {
        List<MaterialOption> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean complete = false;
        for (int page = 1; page <= MAX_PAGES; page++) {
            String path = "/api/bd/material/page?pageNum=" + page + "&pageSize=" + MAX_PAGE_SIZE;
            Map<String, Object> body = getJson(auth, path);
            Object data = body.get("data");
            if (!(data instanceof Map<?, ?> pageMap)) {
                complete = true;
                break;
            }
            Object records = pageMap.get("records");
            if (!(records instanceof List<?> list) || list.isEmpty()) {
                complete = true;
                break;
            }
            collectMaterials(list, brands, categories, seen, result);
            if (isLastPage(pageMap, list.size(), page)) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            log.warn("SCM 商品资料超过 {} 条，型号列表可能不完整", MAX_PAGES * MAX_PAGE_SIZE);
        }
        result.sort(Comparator.comparing(MaterialOption::brand)
                .thenComparing(MaterialOption::category)
                .thenComparing(MaterialOption::model));
        return result;
    }

    private void collectMaterials(List<?> records, Set<String> brands, Set<String> categories,
                                  Set<String> seen, List<MaterialOption> out) {
        for (Object record : records) {
            if (!(record instanceof Map<?, ?> row)) continue;
            String brand = str(row.get("brandName"));
            String category = str(row.get("groupName"));
            // 规格型号为空时回退商品名称：金蝶部分商品只填名称（如 燃气灶JZT-CA021）
            String model = str(row.get("model"));
            if (model.isEmpty()) model = str(row.get("name"));
            if (brand.isEmpty() || category.isEmpty() || model.isEmpty()) continue;
            if (!brands.contains(brand) || !categories.contains(category)) continue;
            if (seen.add(brand + "\u0001" + category + "\u0001" + model)) {
                Long id = toLong(row.get("id"));
                out.add(new MaterialOption(id, str(row.get("number")), brand, category, model,
                        str(row.get("name")), str(row.get("baseUnitName"))));
            }
        }
    }

    // ── 管理端商品选择（产品售价） ──────────────────────────────────────

    /** SCM 商品分页搜索：产品售价选择商品时使用。 */
    public ScmMaterialPage searchMaterials(String keyword, long pageNum, long pageSize) {
        String auth = currentBearerToken();
        StringBuilder path = new StringBuilder("/api/bd/material/page?pageNum=")
                .append(Math.max(1, pageNum)).append("&pageSize=").append(Math.max(1, pageSize));
        if (keyword != null && !keyword.isBlank()) {
            path.append("&keyword=").append(UriUtils.encodeQueryParam(keyword.trim(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        Map<String, Object> body = getJson(auth, path.toString());
        Object data = body.get("data");
        List<ScmMaterial> records = new ArrayList<>();
        long total = 0;
        if (data instanceof Map<?, ?> pageMap) {
            Object rawRecords = pageMap.get("records");
            if (rawRecords instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> row) records.add(toMaterial(row));
                }
            }
            Object rawTotal = pageMap.get("total");
            Long parsedTotal = toLong(rawTotal);
            total = parsedTotal != null ? parsedTotal : records.size();
        }
        return new ScmMaterialPage(records, total);
    }

    /** 按 SCM 商品ID查询权威商品资料（品牌/型号/品类/名称）。 */
    public ScmMaterial getMaterial(Long productId) {
        if (productId == null || productId <= 0) {
            throw BusinessException.of("请选择供应链商品");
        }
        String auth = currentBearerToken();
        Map<String, Object> body = getJson(auth, "/api/bd/material/get?id=" + productId);
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> row)) {
            throw BusinessException.of("供应链商品不存在或已删除");
        }
        return toMaterial(row);
    }

    private ScmMaterial toMaterial(Map<?, ?> row) {
        Long id = toLong(row.get("id"));
        String name = str(row.get("name"));
        String model = str(row.get("model"));
        // 与型号下拉一致：SCM 部分商品未填规格型号时以商品名称兜底
        if (model.isEmpty()) model = name;
        return new ScmMaterial(id, str(row.get("number")), name,
                str(row.get("brandName")), str(row.get("groupName")), model);
    }


    private boolean isLastPage(Map<?, ?> pageMap, int recordCount, int page) {
        Object total = pageMap.get("total");
        if (total instanceof Number n && (long) page * MAX_PAGE_SIZE >= n.longValue()) {
            return true;
        }
        return recordCount < MAX_PAGE_SIZE;
    }

    // ── HTTP helpers ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String auth, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", auth);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) {
                throw BusinessException.of("供应链基础资料服务返回为空");
            }
            Object code = body.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                int codeValue = n.intValue();
                if (codeValue == 401) throw BusinessException.of("登录已过期，请重新登录");
                if (codeValue == 403) {
                    throw BusinessException.of("无权访问供应链基础资料，请联系管理员");
                }
                throw BusinessException.of("供应链基础资料查询失败："
                        + body.getOrDefault("msg", "未知错误"));
            }
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) throw BusinessException.of("登录已过期，请重新登录");
            if (status == 403) {
                throw BusinessException.of("无权访问供应链基础资料，请联系管理员");
            }
            log.warn("SCM 基础资料请求失败 path={}: {}", path, e.getMessage());
            throw BusinessException.of("供应链基础资料服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.warn("SCM 基础资料请求失败 path={}: {}", path, e.getMessage());
            throw BusinessException.of("供应链基础资料服务暂不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(Map<String, Object> body) {
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private String currentBearerToken() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            throw BusinessException.of("登录已过期，请重新登录");
        }
        String header = attrs.getRequest().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw BusinessException.of("登录已过期，请重新登录");
        }
        return header;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** SCM/金蝶 JSON 的 ID 可能是数字或字符串，统一转 Long。 */
    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ── records ──

    public record CategoryOption(String key, String name) {}

    public record MaterialOption(Long id, String number, String brand, String category,
                                 String model, String name, String unit) {}

    public record ScmMaterial(Long id, String number, String name, String brand,
                              String category, String model) {}

    public record ScmMaterialPage(List<ScmMaterial> records, long total) {}

    public record ScmOptions(List<String> brands, List<CategoryOption> categories,
                             List<MaterialOption> materials) {}

    private record CachedOptions(ScmOptions options, long expireAt) {}
}
