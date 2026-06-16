package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.GsActivityService;
import com.aip.ops.service.IGsProductService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 运营商管理后台 — 绑定销售商、商品管理、分类管理。
 *
 * 销售商来源：底座平台 sys_corp 表中，在 sys_corp_role 中拥有 role_name='销售商' 的组织。
 * 商品数据：通过 ops_product（映射表）JOIN mall_product 获取完整商品信息。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin")
public class AdminController {

    private final OperatorMerchantMapper omMapper;
    private final IGsProductService productService;
    private final ProductCategoryMapper categoryMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantMiniappConfigMapper miniappConfigMapper;
    private final OpsLiveRoomMapper liveRoomMapper;
    private final PublicAccountConfigMapper publicAccountConfigMapper;
    private final GsActivityService activityService;
    private final org.springframework.web.client.RestTemplate restTemplate;
    private final String merchantRoleName;

    public AdminController(OperatorMerchantMapper omMapper,
                           IGsProductService productService,
                           ProductCategoryMapper categoryMapper,
                           MerchantMapper merchantMapper,
                           MerchantMiniappConfigMapper miniappConfigMapper,
                           OpsLiveRoomMapper liveRoomMapper,
                           PublicAccountConfigMapper publicAccountConfigMapper,
                           GsActivityService activityService,
                           org.springframework.web.client.RestTemplate restTemplate,
                           @Value("${platform.merchant-role-name:销售商}") String merchantRoleName) {
        this.omMapper = omMapper;
        this.productService = productService;
        this.categoryMapper = categoryMapper;
        this.merchantMapper = merchantMapper;
        this.miniappConfigMapper = miniappConfigMapper;
        this.liveRoomMapper = liveRoomMapper;
        this.publicAccountConfigMapper = publicAccountConfigMapper;
        this.activityService = activityService;
        this.restTemplate = restTemplate;
        this.merchantRoleName = merchantRoleName;
    }

    /** 当前用户ID（用于个人身份） */
    private Long currentUserId() { return SecurityUtils.getCurrentUserId(); }

    /** 当前运营商组织ID（corp_id），用于商品/销售商等运营操作 */
    private Long currentOperatorId() { return SecurityUtils.getCurrentCorpId(); }

    // ═══ 绑定销售商 ═══

    /**
     * 获取销售商列表（含已绑定状态）。
     * 销售商 = 底座平台中拥有"销售商"企业角色的组织（sys_corp × sys_corp_role）。
     */
    @GetMapping("/merchants")
    public R<?> merchantList(@RequestParam(defaultValue="1") int pageNum,
                             @RequestParam(defaultValue="20") int pageSize,
                             @RequestParam(required=false) String keyword) {
        Long operatorId = currentOperatorId();

        Set<Long> bound = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());

        List<Map<String,Object>> merchants = merchantMapper.selectMerchants(
                (pageNum - 1) * pageSize, pageSize, merchantRoleName, keyword);

        merchants.forEach(m -> {
            Long mid = Long.valueOf(m.get("id").toString());
            m.put("bound", bound.contains(mid));
        });

        return R.ok(merchants);
    }

    /** 批量绑定销售商 */
    @PostMapping("/bind-merchants")
    @Transactional
    public R<?> bindMerchants(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("merchantIds");
        if (ids == null || ids.isEmpty()) return R.fail("请选择销售商");

        int count = 0;
        for (Integer id : ids) {
            Long merchantId = Long.valueOf(id);
            // 校验该组织确实拥有销售商角色
            if (merchantMapper.checkMerchantRole(merchantId, merchantRoleName) == null) {
                throw new IllegalArgumentException("组织ID " + merchantId + " 不是销售商，无法绑定");
            }
            Long exist = omMapper.selectCount(new LambdaQueryWrapper<OperatorMerchant>()
                    .eq(OperatorMerchant::getOperatorId, operatorId)
                    .eq(OperatorMerchant::getMerchantId, merchantId)
                    .eq(OperatorMerchant::getStatus, 1));
            if (exist > 0) continue;
            OperatorMerchant om = new OperatorMerchant();
            om.setOperatorId(operatorId); om.setMerchantId(merchantId); om.setStatus(1);
            omMapper.insert(om);
            count++;
        }
        return R.ok(Map.of("boundCount", count));
    }

    /** 解绑单个销售商 */
    @DeleteMapping("/bind-merchants/{merchantId}")
    public R<?> unbind(@PathVariable Long merchantId) {
        Long operatorId = currentOperatorId();
        // 双重校验：该组织确实是销售商
        if (merchantMapper.checkMerchantRole(merchantId, merchantRoleName) == null) {
            throw new IllegalArgumentException("组织ID " + merchantId + " 不是销售商");
        }
        OperatorMerchant om = omMapper.selectOne(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getMerchantId, merchantId)
                .eq(OperatorMerchant::getStatus, 1));
        if (om == null) return R.fail("未找到绑定关系");
        om.setStatus(0);
        omMapper.updateById(om);
        return R.ok();
    }

    // ═══ 小程序跳转配置 ═══

    /**
     * 获取指定销售商的小程序跳转配置。
     * 仅允许当前运营商已绑定的销售商。
     */
    @GetMapping("/merchant/{merchantId}/miniapp-config")
    public R<?> getMiniappConfig(@PathVariable Long merchantId) {
        Long operatorId = currentOperatorId();
        assertMerchantBound(operatorId, merchantId);
        MerchantMiniappConfig cfg = miniappConfigMapper.selectOne(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .eq(MerchantMiniappConfig::getMerchantId, merchantId));
        return R.ok(cfg != null ? cfg : new MerchantMiniappConfig());
    }

    /**
     * 新增或更新指定销售商的小程序跳转配置（upsert）。
     * 仅允许当前运营商已绑定的销售商。
     */
    @PutMapping("/merchant/{merchantId}/miniapp-config")
    @Transactional
    public R<?> saveMiniappConfig(@PathVariable Long merchantId,
                                   @RequestBody MerchantMiniappConfig body) {
        Long operatorId = currentOperatorId();
        assertMerchantBound(operatorId, merchantId);

        MerchantMiniappConfig existing = miniappConfigMapper.selectOne(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .eq(MerchantMiniappConfig::getMerchantId, merchantId));

        if (existing != null) {
            existing.setAppId(body.getAppId());
            existing.setPathTemplate(body.getPathTemplate());
            existing.setEnvVersion(body.getEnvVersion());
            existing.setRemark(body.getRemark());
            miniappConfigMapper.updateById(existing);
        } else {
            body.setMerchantId(merchantId);
            body.setId(null);
            miniappConfigMapper.insert(body);
        }
        return R.ok();
    }

    /** 校验当前运营商是否已绑定指定销售商，未绑定则抛出权限异常 */
    private void assertMerchantBound(Long operatorId, Long merchantId) {
        Long count = omMapper.selectCount(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getMerchantId, merchantId)
                .eq(OperatorMerchant::getStatus, 1));
        if (count == null || count == 0) {
            throw new IllegalArgumentException("无权操作：当前运营商未绑定该销售商");
        }
    }

    // ═══ 商品展示管理 ═══

    /**
     * 获取当前运营商的商品展示列表。
     * 通过 ops_product 映射表 JOIN mall_product 获取完整商品信息。
     */
    @GetMapping("/products")
    public R<?> products(@RequestParam(defaultValue="1") int pageNum,
                         @RequestParam(defaultValue="10") int pageSize,
                         @RequestParam(required=false) Long merchantId,
                         @RequestParam(required=false) Long categoryId,
                         @RequestParam(required=false) Integer shelfStatus,
                         @RequestParam(required=false) String keyword) {
        Long operatorId = currentOperatorId();
        return R.ok(productService.adminProductPage(
                operatorId, pageNum, pageSize, shelfStatus, categoryId, merchantId, keyword));
    }

    /** 单个商品上下架 */
    @PutMapping("/products/{id}/shelf-status")
    public R<?> toggleShelf(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        productService.updateShelfStatus(id, shelfStatus);
        return R.ok();
    }

    /** 批量上下架 */
    @PostMapping("/products/batch-shelf")
    public R<?> batchShelf(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer shelfStatus = Integer.valueOf(body.getOrDefault("shelfStatus", 1).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdateShelfStatus(longIds, shelfStatus, operatorId);
        return R.ok(Map.of("count", count));
    }

    /** 批量设置分类 */
    @PutMapping("/products/batch-category")
    public R<?> batchCategory(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Long categoryId = Long.valueOf(body.getOrDefault("categoryId", 0).toString());
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdateCategory(longIds, categoryId, operatorId);
        return R.ok(Map.of("count", count));
    }

    /** 设置单个商品积分配额 */
    @PutMapping("/products/{id}/points-config")
    public R<?> updatePointsConfig(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer points = body.get("points") != null ? Integer.valueOf(body.get("points").toString()) : 0;
        java.math.BigDecimal exchangeAmount = body.get("exchangeAmount") != null
                ? new java.math.BigDecimal(body.get("exchangeAmount").toString()) : java.math.BigDecimal.ZERO;
        productService.updatePointsConfig(id, points, exchangeAmount, operatorId);
        return R.ok();
    }

    /** 批量设置积分配额 */
    @PostMapping("/products/batch-points-config")
    public R<?> batchPointsConfig(@RequestBody Map<String,Object> body) {
        Long operatorId = currentOperatorId();
        Integer points = body.get("points") != null ? Integer.valueOf(body.get("points").toString()) : 0;
        java.math.BigDecimal exchangeAmount = body.get("exchangeAmount") != null
                ? new java.math.BigDecimal(body.get("exchangeAmount").toString()) : java.math.BigDecimal.ZERO;
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.fail("请选择商品");
        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        int count = productService.batchUpdatePointsConfig(longIds, points, exchangeAmount, operatorId);
        return R.ok(Map.of("count", count));
    }

    // ═══ 商品分类 CRUD ═══

    @GetMapping("/product-categories")
    public R<?> categories() {
        List<ProductCategory> list = categoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>().orderByAsc(ProductCategory::getSortOrder));
        List<Map<String,Object>> roots = new ArrayList<>();
        Map<Long,List<ProductCategory>> childrenMap = new LinkedHashMap<>();
        for (ProductCategory c : list) {
            if (c.getParentId() == null || c.getParentId() == 0)
                roots.add(catToMap(c));
            else
                childrenMap.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        for (Map<String,Object> root : roots) {
            Long rid = Long.valueOf(root.get("id").toString());
            List<ProductCategory> kids = childrenMap.getOrDefault(rid, Collections.emptyList());
            root.put("children", kids.stream().map(this::catToMap).collect(Collectors.toList()));
        }
        return R.ok(roots);
    }

    @PostMapping("/product-categories")
    public R<?> createCategory(@RequestBody ProductCategory cat) { categoryMapper.insert(cat); return R.ok(); }

    @PutMapping("/product-categories/{id}")
    public R<?> updateCategory(@PathVariable Long id, @RequestBody ProductCategory cat) {
        cat.setId(id); categoryMapper.updateById(cat); return R.ok();
    }

    @DeleteMapping("/product-categories/{id}")
    public R<?> deleteCategory(@PathVariable Long id) { categoryMapper.deleteById(id); return R.ok(); }

    private Map<String,Object> catToMap(ProductCategory c) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("name", c.getName());
        m.put("parentId", c.getParentId()); m.put("sortOrder", c.getSortOrder());
        return m;
    }

    // ═══ 视频直播管理 ═══

    @GetMapping("/live-rooms")
    public R<?> liveRoomList(@RequestParam(defaultValue="20") int pageSize) {
        Long operatorId = currentOperatorId();
        // 仅返回当前运营商绑定的销售商的直播间
        Set<Long> boundMerchantIds = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
        if (boundMerchantIds.isEmpty()) return R.ok(Collections.emptyList());

        List<OpsLiveRoom> list = liveRoomMapper.selectList(
                new LambdaQueryWrapper<OpsLiveRoom>()
                        .in(OpsLiveRoom::getMerchantId, boundMerchantIds)
                        .orderByDesc(OpsLiveRoom::getStatus)
                        .orderByAsc(OpsLiveRoom::getSortOrder)
                        .last("LIMIT " + pageSize));
        return R.ok(list);
    }

    @PostMapping("/live-rooms")
    public R<?> createLiveRoom(@RequestBody OpsLiveRoom room) {
        Long operatorId = currentOperatorId();
        assertMerchantBound(operatorId, room.getMerchantId());
        room.setId(null);
        liveRoomMapper.insert(room);
        return R.ok();
    }

    @PutMapping("/live-rooms/{id}")
    public R<?> updateLiveRoom(@PathVariable Long id, @RequestBody OpsLiveRoom room) {
        Long operatorId = currentOperatorId();
        OpsLiveRoom existing = liveRoomMapper.selectById(id);
        if (existing == null) return R.fail("直播间不存在");
        assertMerchantBound(operatorId, existing.getMerchantId());
        room.setId(id);
        liveRoomMapper.updateById(room);
        return R.ok();
    }

    @DeleteMapping("/live-rooms/{id}")
    public R<?> deleteLiveRoom(@PathVariable Long id) {
        Long operatorId = currentOperatorId();
        OpsLiveRoom existing = liveRoomMapper.selectById(id);
        if (existing == null) return R.fail("直播间不存在");
        assertMerchantBound(operatorId, existing.getMerchantId());
        liveRoomMapper.deleteById(id);
        return R.ok();
    }

    /**
     * 测试直播 API 连通性。
     * 接收 finderUserName，通过微信 access_token 验证配置是否有效。
     */
    @PostMapping("/live-rooms/test-connection")
    public R<?> testLiveConnection(@RequestBody Map<String, String> body) {
        String finderUsername = body.get("finderUsername");
        if (finderUsername == null || finderUsername.isBlank()) return R.fail("视频号 ID 不能为空");

        Long operatorId = currentOperatorId();
        // Verify operator has bound merchants
        Set<Long> bound = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
        if (bound.isEmpty()) return R.fail("当前运营商未绑定任何销售商");

        // Fetch public account config — try operator's own org ID first,
        // then fall back to bound merchant's org ID
        PublicAccountConfig pubCfg = publicAccountConfigMapper.selectOne(
                new LambdaQueryWrapper<PublicAccountConfig>()
                        .eq(PublicAccountConfig::getCorpId, operatorId)
                        .eq(PublicAccountConfig::getStatus, 1));

        if (pubCfg == null) {
            Long merchantId = bound.iterator().next();
            pubCfg = publicAccountConfigMapper.selectOne(
                    new LambdaQueryWrapper<PublicAccountConfig>()
                            .eq(PublicAccountConfig::getCorpId, merchantId)
                            .eq(PublicAccountConfig::getStatus, 1));
        }

        if (pubCfg == null || pubCfg.getAppId() == null)
            return R.fail("公众号未配置 appId，请先在「公众号配置」中完成配置");

        // Try to fetch access_token to verify credentials
        try {
            String appId = pubCfg.getAppId();
            String appSecret = pubCfg.getAppSecret();

            if (appSecret == null || appSecret.isBlank())
                return R.fail("公众号 appSecret 未配置，无法获取 access_token。请先在「公众号配置」中完善");

            // Call WeChat token API
            String tokenUrl = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                    + appId + "&secret=" + appSecret;

            log.info("测试连接: finderUsername={}, appId={}", finderUsername, appId);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> resp = restTemplate.exchange(
                    tokenUrl, HttpMethod.GET, null, Map.class);

            if (resp.getBody() != null && resp.getBody().get("access_token") != null) {
                String accessToken = resp.getBody().get("access_token").toString();
                log.info("测试连接成功: access_token 获取成功, finderUsername={}", finderUsername);
                return R.ok(Map.of(
                        "success", true,
                        "message", "微信 API 连通正常（access_token 获取成功），finderUserName=" + finderUsername,
                        "detail", "appId=" + appId.substring(0, Math.min(appId.length(), 10)) + "...",
                        "tokenPrefix", accessToken.length() > 10
                                ? accessToken.substring(0, 10) + "..." : accessToken
                ));
            } else {
                Object errcode = resp.getBody() != null ? resp.getBody().get("errcode") : "unknown";
                Object errmsg = resp.getBody() != null ? resp.getBody().get("errmsg") : "unknown";
                log.error("测试连接失败: finderUsername={}, 微信返回 errcode={}, errmsg={}",
                        finderUsername, errcode, errmsg);
                String hint = resolveWechatErrorHint(errcode, errmsg);
                return R.fail("微信API调用失败: " + hint + " [errcode=" + errcode + ", errmsg=" + errmsg + "]");
            }
        } catch (Exception e) {
            String errStr = e.getMessage() != null ? e.getMessage() : "未知错误";
            log.error("测试连接异常: finderUsername={}, error={}", finderUsername, errStr);
            String shortErr = errStr.length() > 100 ? errStr.substring(0, 100) + "..." : errStr;
            return R.fail("网络请求失败: " + shortErr);
        }
    }

    // ═══ 活动管理 ═══

    /**
     * 分页查询活动列表（含关联商品数量）。
     */
    @GetMapping("/activities")
    public R<?> activityPage(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "10") int pageSize,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) Integer status) {
        Long operatorId = currentOperatorId();
        return R.ok(activityService.adminActivityPage(
                operatorId, pageNum, pageSize, type, category, status));
    }

    /**
     * 活动详情（含关联商品 + 直播间信息）。
     */
    @GetMapping("/activities/{id}")
    public R<?> activityDetail(@PathVariable Long id) {
        Map<String, Object> detail = activityService.activityDetail(id);
        if (detail == null) return R.fail("活动不存在");
        return R.ok(detail);
    }

    /**
     * 创建活动。
     * 接收 type、liveRoomId、productIds 等字段。
     */
    @PostMapping("/activities")
    public R<?> createActivity(@RequestBody Map<String, Object> body) {
        GsActivity a = buildActivityFromBody(body);
        a.setCorpId(currentOperatorId());
        a.setStatus(1);
        @SuppressWarnings("unchecked")
        List<Long> productIds = body.get("productIds") != null
                ? ((List<Integer>) body.get("productIds")).stream()
                        .map(Long::valueOf).collect(Collectors.toList())
                : Collections.emptyList();
        Long id = activityService.createActivity(a, productIds);
        return R.ok(Map.of("id", id));
    }

    /**
     * 更新活动（含商品关联）。
     */
    @PutMapping("/activities/{id}")
    public R<?> updateActivity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        GsActivity a = buildActivityFromBody(body);
        @SuppressWarnings("unchecked")
        List<Long> productIds = body.get("productIds") != null
                ? ((List<Integer>) body.get("productIds")).stream()
                        .map(Long::valueOf).collect(Collectors.toList())
                : Collections.emptyList();
        activityService.updateActivity(id, a, productIds);
        return R.ok();
    }

    /**
     * 更新活动状态（上架/下架/结束）。
     */
    @PutMapping("/activities/{id}/status")
    public R<?> updateActivityStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer status = Integer.valueOf(body.get("status").toString());
        activityService.updateStatus(id, status);
        return R.ok();
    }

    /**
     * 删除活动（同时清除关联商品）。
     */
    @DeleteMapping("/activities/{id}")
    public R<?> deleteActivity(@PathVariable Long id) {
        activityService.deleteActivity(id);
        return R.ok();
    }

    /**
     * 获取可用直播间列表（仅 status=0 预告状态的直播间，限当前运营商绑定的销售商）。
     * 用于活动编辑时选择"关联直播主题"。
     */
    @GetMapping("/live-rooms/available")
    public R<?> availableLiveRooms() {
        Long operatorId = currentOperatorId();
        Set<Long> boundMerchantIds = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
        if (boundMerchantIds.isEmpty()) return R.ok(Collections.emptyList());

        List<OpsLiveRoom> list = liveRoomMapper.selectList(
                new LambdaQueryWrapper<OpsLiveRoom>()
                        .in(OpsLiveRoom::getMerchantId, boundMerchantIds)
                        .eq(OpsLiveRoom::getStatus, 0)
                        .orderByAsc(OpsLiveRoom::getSortOrder)
                        .orderByDesc(OpsLiveRoom::getStartTime));
        return R.ok(list);
    }

    // ── Activity helper ──

    private GsActivity buildActivityFromBody(Map<String, Object> body) {
        GsActivity a = new GsActivity();
        a.setTitle((String) body.get("title"));
        a.setCategory((String) body.get("category"));
        a.setType((String) body.getOrDefault("type", "activity"));
        a.setLocation((String) body.get("location"));
        a.setDetail((String) body.get("detail"));
        a.setCoverImage((String) body.get("coverImage"));
        a.setCoverUrl((String) body.get("coverUrl"));
        a.setVideoUrl((String) body.get("videoUrl"));
        a.setDetailVideoUrl((String) body.get("detailVideoUrl"));
        a.setImages((String) body.get("images"));
        a.setDetailImages((String) body.get("detailImages"));
        a.setPointsReward(body.get("pointsReward") != null
                ? Integer.valueOf(body.get("pointsReward").toString()) : 0);
        a.setMaxParticipants(body.get("maxParticipants") != null
                ? Integer.valueOf(body.get("maxParticipants").toString()) : null);
        if (body.get("liveRoomId") != null)
            a.setLiveRoomId(Long.valueOf(body.get("liveRoomId").toString()));
        if (body.get("startTime") != null)
            a.setStartTime(parseActivityDateTime(body.get("startTime").toString()));
        if (body.get("endTime") != null)
            a.setEndTime(parseActivityDateTime(body.get("endTime").toString()));
        return a;
    }

    private java.time.LocalDateTime parseActivityDateTime(String s) {
        try { return java.time.LocalDateTime.parse(s.replace(" ", "T")); }
        catch (Exception e) { return java.time.LocalDateTime.now(); }
    }

    /** Map common WeChat API error codes to human-readable hints */
    private String resolveWechatErrorHint(Object errcode, Object errmsg) {
        String code = errcode != null ? errcode.toString() : "";
        String msg = errmsg != null ? errmsg.toString() : "未知错误";
        switch (code) {
            case "40125": return "appSecret 无效（40125）— 请检查公众号配置中的 appSecret 是否正确";
            case "40001": return "access_token 无效（40001）— appSecret 可能被刷新，请重新测试";
            case "40013": return "无效的 appId（40013）— 请检查公众号配置中的 appId";
            case "48001": return "API 功能未授权（48001）— 公众号未获得该接口权限，请检查公众平台授权";
            case "41001": return "缺少 access_token 参数（41001）";
            case "42001": return "access_token 已过期（42001）";
            case "41002": return "缺少 appId 参数（41002）";
            default: return "errcode=" + code + ", errmsg=" + msg;
        }
    }
}
