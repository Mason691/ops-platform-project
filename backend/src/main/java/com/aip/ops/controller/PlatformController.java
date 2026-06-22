package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.GsActivityService;
import com.aip.ops.service.IGsProductService;
import com.aip.ops.util.ImageUrlUtil;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 运营平台 — 核心 Controller。
 * 定位: 商品/活动展示 + 积分消耗 + 引流跳转第三方小程序，不提供交易功能。
 *
 * 商品数据通过 ops_product（映射表）JOIN mall_product 获取。
 */
@Slf4j
@RestController @RequestMapping("/api/ops") @RequiredArgsConstructor
public class PlatformController {

    private final OpsMemberMapper userMapper;
    private final IGsProductService productService;
    private final GsOrderMapper orderMapper;
    private final GsActivityMapper activityMapper;
    private final GsActivityService activityService;
    private final GsAddressMapper addressMapper;
    private final GsPointsLogMapper pointsLogMapper;
    private final GsCouponMapper couponMapper;
    private final GsUserCouponMapper userCouponMapper;
    private final GsClickLogMapper clickLogMapper;
    private final ProductCategoryMapper categoryMapper;
    private final OpsActivityProductMapper activityProductMapper;
    private final OpsLiveRoomMapper liveRoomMapper;
    private final OpsActivityBookingMapper bookingMapper;
    private final OpsRemindLogMapper remindLogMapper;
    private final org.springframework.web.client.RestTemplate restTemplate;
    private final ImageUrlUtil imageUrlUtil;

    @org.springframework.beans.factory.annotation.Value("${platform.base-url}")
    private String platformBaseUrl;
    @org.springframework.beans.factory.annotation.Value("${platform.internal-token}")
    private String internalToken;
    @org.springframework.beans.factory.annotation.Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    // ═══ 用户登录 ═══
    // 登录接口已迁移至 AuthController：POST /api/ops/auth/login

    @GetMapping("/user/me")
    public R<?> me() {
        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        OpsMember u = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getId, userId).eq(OpsMember::getCorpId, corpId));
        if (u == null) return R.fail("用户不存在");
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("id",u.getId()); r.put("phone",u.getPhone());
        r.put("nickname",u.getNickname()); r.put("avatar",u.getAvatar());
        r.put("pointsBalance",u.getPointsBalance());
        return R.ok(r);
    }

    @PutMapping("/user/profile")
    public R<?> updateProfile(@RequestBody Map<String,String> body) {
        OpsMember u = new OpsMember(); u.setId(SecurityUtils.getCurrentUserId());
        if (body.containsKey("nickname")) u.setNickname(body.get("nickname"));
        if (body.containsKey("avatar")) u.setAvatar(body.get("avatar"));
        userMapper.updateById(u);
        return R.ok();
    }

    /**
     * 上传/更新用户头像。
     * 接受 base64 图片数据，保存到 uploads/avatar/，更新 ops_member.avatar。
     */
    @PostMapping("/user/avatar")
    public R<?> uploadAvatar(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        String imageData = body.get("imageData");
        if (imageData == null || imageData.isEmpty()) return R.fail("图片数据为空");

        try {
            // 解析 base64 data URL: "data:image/png;base64,xxxxx"
            String base64 = imageData.contains(",") ? imageData.split(",")[1] : imageData;
            String ext = imageData.contains("png") ? "png" : imageData.contains("gif") ? "gif" : "jpg";
            String extFromData = imageData.contains("image/") ?
                    imageData.substring(imageData.indexOf("image/") + 6).split(";")[0] : "jpg";
            if (!extFromData.equals("jpeg")) ext = extFromData;
            if ("jpeg".equals(ext)) ext = "jpg";

            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            String fileName = "avatar_" + userId + "_" + System.currentTimeMillis() + "." + ext;

            // 保存到文件系统（绝对路径，确保 Nginx 可读）
            java.io.File uploadDir = new java.io.File(uploadBaseDir + "/avatar/");
            if (!uploadDir.exists()) uploadDir.mkdirs();
            java.io.File file = new java.io.File(uploadDir, fileName);
            java.nio.file.Files.write(file.toPath(), bytes);

            String avatarUrl = "/uploads/avatar/" + fileName;

            // 更新数据库
            OpsMember u = new OpsMember();
            u.setId(userId);
            u.setAvatar(avatarUrl);
            userMapper.updateById(u);

            // 同步到底座平台会员头像
            syncAvatarToPlatform(userId, avatarUrl);

            return R.ok(Map.of("avatar", avatarUrl));
        } catch (Exception e) {
            log.error("头像上传失败: userId={}", userId, e);
            return R.fail("头像上传失败，请重试");
        }
    }

    /** 同步头像到底座平台 sys_member */
    private void syncAvatarToPlatform(Long userId, String avatarUrl) {
        try {
            OpsMember member = userMapper.selectById(userId);
            if (member == null || member.getPlatformMemberId() == null) return;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            Map<String, String> body = Map.of("avatar", avatarUrl);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);
            String url = platformBaseUrl + "/api/internal/member/" + member.getPlatformMemberId() + "/avatar";

            restTemplate.exchange(url, HttpMethod.PUT, req, Map.class);
            log.info("头像已同步到底座: userId={}, platformMemberId={}, avatar={}",
                    userId, member.getPlatformMemberId(), avatarUrl);
        } catch (Exception e) {
            log.warn("同步头像到底座失败（非致命）: userId={}, error={}", userId, e.getMessage());
        }
    }

    // ═══ 商品 (展示，非交易) ═══

    /**
     * 公众号端商品分类 — 公开接口，按 sort 排序返回所有分类。
     */
    @GetMapping("/product/categories")
    public R<?> productCategories() {
        List<ProductCategory> list = categoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getStatus, 1)
                        .orderByAsc(ProductCategory::getSortOrder));
        return R.ok(list);
    }

    /**
     * 用户端商品列表 — 仅展示已上架且通过 ops_product 映射引入的商品。
     */
    @GetMapping({"/product/page", "/product/list"})
    public R<?> productPage(@RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="10") int pageSize,
            @RequestParam(required=false) String name,
            @RequestParam(required=false) Long categoryId,
            @RequestParam(required=false) Integer status) {
        Long corpId = getCorpId();
        return R.ok(productService.publishedProductPage(
                corpId, pageNum, pageSize, categoryId, name));
    }

    /**
     * 用户端商品详情 — 通过 ops_product 映射ID获取 mall_product 商品信息。
     */
    @GetMapping("/product/{id}")
    public R<?> productDetail(@PathVariable Long id) {
        Map<String, Object> product = productService.productDetailByMappingId(id);
        if (product == null) return R.fail("商品不存在");
        // 点击统计
        Long corpId = getCorpId();
        GsClickLog cl = new GsClickLog(); cl.setCorpId(corpId);
        cl.setUserId(getUserIdOrNull()); cl.setTargetType("PRODUCT"); cl.setTargetId(id);
        clickLogMapper.insert(cl);
        return R.ok(product);
    }

    /**
     * 新增商品 — 仅用于兼容，实际商品在 mall_product 中管理。
     * 映射关系由管理员在后台通过绑定销售商后引入。
     */
    @PostMapping("/product")
    public R<?> createProduct(@RequestBody Map<String,Object> body) {
        return R.fail("商品由销售商(mall_product)提供，请通过管理员后台引入商品");
    }

    @PutMapping("/product/{id}")
    public R<?> updateProduct(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        return R.fail("商品由销售商(mall_product)提供，请通过管理员后台管理");
    }

    @PutMapping("/product/{id}/status")
    public R<?> productStatus(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        return R.fail("请使用上下架接口 /api/ops/admin/products/{id}/shelf-status");
    }

    @DeleteMapping("/product/{id}")
    public R<?> deleteProduct(@PathVariable Long id) {
        return R.fail("商品由销售商(mall_product)提供，请通过管理员后台管理");
    }

    // ═══ 积分兑换订单 (预扣积分，跳转第三方) ═══
    @PostMapping("/order/create")
    public R<?> createOrder(@RequestBody Map<String,Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long mappingId = Long.valueOf(body.get("productId").toString());

        // 通过映射关系获取商品信息
        Map<String, Object> product = productService.productDetailByMappingId(mappingId);
        if (product == null) return R.fail("商品不存在或已下架");

        Integer status = (Integer) product.get("status");
        Integer shelfStatus = (Integer) product.get("shelfStatus");
        if (status == null || status != 1 || shelfStatus == null || shelfStatus != 1)
            return R.fail("商品不存在或已下架");

        OpsMember u = userMapper.selectById(userId);
        int pointsSpent = product.get("pointsPrice") != null ?
                ((Number) product.get("pointsPrice")).intValue() : 0;
        if (u.getPointsBalance() < pointsSpent)
            return R.fail("积分不足，当前余额" + u.getPointsBalance());

        String productName = (String) product.get("name");
        u.setPointsBalance(u.getPointsBalance() - pointsSpent);
        userMapper.updateById(u);

        GsPointsLog log = new GsPointsLog(); log.setCorpId(corpId); log.setUserId(userId);
        log.setChangeType("CONSUME"); log.setAmount(pointsSpent);
        log.setBalanceAfter(u.getPointsBalance()); log.setRemark("兑换商品：" + productName);
        pointsLogMapper.insert(log);

        String orderNo = "OPS" + System.currentTimeMillis() + userId;
        GsOrder order = new GsOrder(); order.setCorpId(corpId); order.setUserId(userId);
        order.setOrderNo(orderNo); order.setProductId(mappingId);
        order.setProductName(productName);
        order.setProductImage((String) product.get("coverImage"));
        order.setPointsSpent(pointsSpent);
        int cashAmount = product.get("cashPrice") != null ?
                ((Number) product.get("cashPrice")).intValue() : 0;
        order.setCashAmount(new java.math.BigDecimal(cashAmount));
        order.setStatus("PENDING");
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        orderMapper.insert(order);
        log.setOrderNo(orderNo); pointsLogMapper.updateById(log);

        return R.ok(Map.of("orderNo", orderNo, "orderId", order.getId(),
                "pointsSpent", pointsSpent, "status", "PENDING",
                "expireTime", order.getExpireTime().toString()));
    }

    @GetMapping("/order/my")
    public R<?> myOrders(@RequestParam(defaultValue="1") int pageNum,
                         @RequestParam(defaultValue="10") int pageSize) {
        Long userId = SecurityUtils.getCurrentUserId();
        Page<GsOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<GsOrder>().eq(GsOrder::getUserId, userId)
                        .orderByDesc(GsOrder::getId));
        return R.ok(page);
    }

    /** 第三方回调：确认支付或退还积分 */
    @PostMapping("/order/{orderNo}/callback")
    public R<?> orderCallback(@PathVariable String orderNo, @RequestBody Map<String,Object> body) {
        GsOrder o = orderMapper.selectOne(new LambdaQueryWrapper<GsOrder>()
                .eq(GsOrder::getOrderNo, orderNo));
        if (o == null) return R.fail("订单不存在");
        String action = (String) body.getOrDefault("action", "PAID");
        if ("PAID".equals(action)) {
            o.setStatus("PAID"); o.setThirdPartyOrderNo((String)body.get("thirdPartyOrderNo"));
        } else {
            o.setStatus("CANCELLED");
            OpsMember u = userMapper.selectById(o.getUserId());
            u.setPointsBalance(u.getPointsBalance() + o.getPointsSpent()); userMapper.updateById(u);
            GsPointsLog log = new GsPointsLog(); log.setCorpId(o.getCorpId());
            log.setUserId(o.getUserId()); log.setChangeType("REFUND");
            log.setAmount(o.getPointsSpent()); log.setBalanceAfter(u.getPointsBalance());
            log.setOrderNo(orderNo); log.setRemark("订单取消退还积分");
            pointsLogMapper.insert(log);
        }
        orderMapper.updateById(o);
        return R.ok(Map.of("status", o.getStatus()));
    }

    // ═══ 活动 ═══
    @GetMapping("/activity/page")
    public R<?> activityPage(@RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="10") int pageSize,
            @RequestParam(required=false) String category,
            @RequestParam(required=false) String type,
            @RequestParam(required=false) Integer status) {
        int offset = (pageNum - 1) * pageSize;
        List<GsActivity> records = activityMapper.selectActivityPage(
                offset, pageSize, type, category, status);
        long total = activityMapper.countActivityPage(type, category, status);

        // 规范化图片 URL：补全 coverUrl → 相对路径统一处理
        for (GsActivity a : records) {
            // 若 coverUrl 为空，回退到 coverImage
            if (a.getCoverUrl() == null || a.getCoverUrl().isBlank()) {
                a.setCoverUrl(a.getCoverImage());
            }
            // 规范化所有图片路径为相对路径
            a.setCoverUrl(imageUrlUtil.normalizeImage(a.getCoverUrl()));
            a.setCoverImage(imageUrlUtil.normalizeImage(a.getCoverImage()));
            a.setVideoCoverUrl(imageUrlUtil.normalizeImage(a.getVideoCoverUrl()));
            a.setVideoUrl(imageUrlUtil.normalizeImage(a.getVideoUrl()));
            a.setDetailVideoUrl(imageUrlUtil.normalizeImage(a.getDetailVideoUrl()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return R.ok(result);
    }

    @GetMapping("/activity/{id}")
    public R<Map<String, Object>> activityDetail(@PathVariable Long id) {
        Map<String, Object> detail = activityService.activityDetail(id);
        if (detail == null) return R.fail("活动不存在");

        // 规范化活动图片 URL
        GsActivity act = (GsActivity) detail.get("activity");
        if (act != null) {
            if (act.getCoverUrl() == null || act.getCoverUrl().isBlank()) {
                act.setCoverUrl(act.getCoverImage());
            }
            act.setCoverUrl(imageUrlUtil.normalizeImage(act.getCoverUrl()));
            act.setCoverImage(imageUrlUtil.normalizeImage(act.getCoverImage()));
            act.setVideoCoverUrl(imageUrlUtil.normalizeImage(act.getVideoCoverUrl()));
            act.setVideoUrl(imageUrlUtil.normalizeImage(act.getVideoUrl()));
            act.setDetailVideoUrl(imageUrlUtil.normalizeImage(act.getDetailVideoUrl()));
            // 规范化 detailImages JSON 数组中的路径
            String detailImages = act.getDetailImages();
            if (detailImages != null && !detailImages.isBlank()) {
                act.setDetailImages(imageUrlUtil.normalizeImage(detailImages));
            }
        }

        // 规范化关联商品图片
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) detail.get("products");
        if (products != null) {
            for (Map<String, Object> p : products) {
                Object coverImage = p.get("cover_image");
                if (coverImage == null) coverImage = p.get("coverImage");
                if (coverImage instanceof String) {
                    p.put("cover_image", imageUrlUtil.normalizeProductImage((String) coverImage));
                }
            }
        }

        return R.ok(detail);
    }

    @PostMapping("/activity")
    public R<?> createActivity(@RequestBody Map<String, Object> body) {
        return R.fail("请通过管理员接口 /api/ops/admin/activities 创建活动");
    }

    @PutMapping("/activity/{id}")
    public R<?> updateActivity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.fail("请通过管理员接口 /api/ops/admin/activities/" + id + " 更新活动");
    }

    @PutMapping("/activity/{id}/status")
    public R<?> activityStatus(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        return R.fail("请通过管理员接口 /api/ops/admin/activities/" + id + "/status 更新状态");
    }

    @DeleteMapping("/activity/{id}")
    public R<?> deleteActivity(@PathVariable Long id) {
        return R.fail("请通过管理员接口 /api/ops/admin/activities/" + id + " 删除活动");
    }

    // ═══ 地址 ═══
    @GetMapping("/address/list")
    public R<?> addressList() {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(addressMapper.selectList(new LambdaQueryWrapper<GsAddress>()
                .eq(GsAddress::getUserId, userId).orderByDesc(GsAddress::getIsDefault)));
    }

    @GetMapping("/address/default")
    public R<?> defaultAddress() {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(addressMapper.selectOne(new LambdaQueryWrapper<GsAddress>()
                .eq(GsAddress::getUserId, userId).eq(GsAddress::getIsDefault, 1).last("LIMIT 1")));
    }

    @PostMapping("/address")
    public R<?> saveAddress(@RequestBody GsAddress addr) {
        addr.setUserId(SecurityUtils.getCurrentUserId());
        addr.setCorpId(SecurityUtils.getCurrentCorpId());
        if (Integer.valueOf(1).equals(addr.getIsDefault())) clearDefault(addr.getUserId());
        addressMapper.insert(addr);
        return R.ok();
    }

    @PutMapping("/address/{id}")
    public R<?> updateAddress(@PathVariable Long id, @RequestBody GsAddress addr) {
        Long userId = SecurityUtils.getCurrentUserId();
        addr.setId(id); addr.setUserId(userId);
        if (Integer.valueOf(1).equals(addr.getIsDefault())) clearDefault(userId);
        addressMapper.updateById(addr);
        return R.ok();
    }

    @DeleteMapping("/address/{id}")
    public R<?> deleteAddress(@PathVariable Long id) { addressMapper.deleteById(id); return R.ok(); }

    private void clearDefault(Long userId) {
        addressMapper.update(null, new LambdaUpdateWrapper<GsAddress>()
                .eq(GsAddress::getUserId, userId).set(GsAddress::getIsDefault, 0));
    }

    // ═══ 积分 ═══
    @GetMapping("/points/log")
    public R<?> pointsLog(@RequestParam(defaultValue="1") int pageNum,
                          @RequestParam(defaultValue="20") int pageSize,
                          @RequestParam(required=false) String changeType) {
        Page<GsPointsLog> page = pointsLogMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<GsPointsLog>().eq(GsPointsLog::getUserId,
                        SecurityUtils.getCurrentUserId())
                        .eq(changeType != null && !changeType.isEmpty(), GsPointsLog::getChangeType, changeType)
                        .orderByDesc(GsPointsLog::getId));
        return R.ok(page);
    }

    // ═══ 优惠券 ═══
    @GetMapping("/coupon/list")
    public R<?> couponList() {
        return R.ok(couponMapper.selectList(new LambdaQueryWrapper<GsCoupon>()
                .eq(GsCoupon::getStatus, 1).gt(GsCoupon::getRemainQty, 0)));
    }

    @PostMapping("/coupon/draw")
    public R<?> drawCoupon(@RequestBody Map<String,Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long couponId = Long.valueOf(body.get("couponId").toString());
        GsCoupon c = couponMapper.selectById(couponId);
        if (c == null || c.getRemainQty() <= 0) return R.fail("优惠券已抢完");
        Long count = userCouponMapper.selectCount(new LambdaQueryWrapper<GsUserCoupon>()
                .eq(GsUserCoupon::getUserId, userId).eq(GsUserCoupon::getCouponId, couponId));
        if (count > 0) return R.fail("已领取过该优惠券");
        c.setRemainQty(c.getRemainQty() - 1); couponMapper.updateById(c);
        GsUserCoupon uc = new GsUserCoupon(); uc.setCorpId(c.getCorpId());
        uc.setUserId(userId); uc.setCouponId(couponId);
        uc.setExpireTime(LocalDateTime.now().plusDays(c.getExpireDays() != null ? c.getExpireDays() : 30));
        uc.setStatus("UNUSED"); userCouponMapper.insert(uc);
        return R.ok();
    }

    @GetMapping("/coupon/my")
    public R<?> myCoupons() {
        return R.ok(userCouponMapper.selectList(new LambdaQueryWrapper<GsUserCoupon>()
                .eq(GsUserCoupon::getUserId, SecurityUtils.getCurrentUserId())
                .orderByDesc(GsUserCoupon::getId)));
    }

    // ═══ 活动预约 ═══

    @PostMapping("/activity/{activityId}/book")
    public R<?> bookActivity(@PathVariable Long activityId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();

        GsActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1)
            return R.fail("活动不存在或已结束");

        // Check not already booked
        Long exist = bookingMapper.selectCount(new LambdaQueryWrapper<OpsActivityBooking>()
                .eq(OpsActivityBooking::getActivityId, activityId)
                .eq(OpsActivityBooking::getUserId, userId)
                .eq(OpsActivityBooking::getStatus, 0));
        if (exist > 0) return R.fail("您已预约该活动");

        OpsMember member = userMapper.selectById(userId);
        OpsActivityBooking booking = new OpsActivityBooking();
        booking.setActivityId(activityId);
        booking.setUserId(userId);
        booking.setPhone(member != null ? member.getPhone() : null);
        booking.setStatus(0);
        bookingMapper.insert(booking);
        return R.ok(Map.of("bookingId", booking.getId()));
    }

    @GetMapping("/activity/{activityId}/bookings")
    public R<?> activityBookings(@PathVariable Long activityId,
                                  @RequestParam(defaultValue="1") int pageNum,
                                  @RequestParam(defaultValue="20") int pageSize) {
        Page<OpsActivityBooking> page = bookingMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsActivityBooking>()
                        .eq(OpsActivityBooking::getActivityId, activityId)
                        .orderByDesc(OpsActivityBooking::getCreateTime));
        return R.ok(page);
    }

    @GetMapping("/my-bookings")
    public R<?> myBookings() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<OpsActivityBooking> bookings = bookingMapper.selectList(
                new LambdaQueryWrapper<OpsActivityBooking>()
                        .eq(OpsActivityBooking::getUserId, userId)
                        .orderByDesc(OpsActivityBooking::getCreateTime));
        // Enrich with activity titles
        List<Map<String,Object>> result = new ArrayList<>();
        for (OpsActivityBooking b : bookings) {
            GsActivity act = activityMapper.selectById(b.getActivityId());
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("activityId", b.getActivityId());
            m.put("status", b.getStatus());
            m.put("createTime", b.getCreateTime());
            m.put("activityTitle", act != null ? act.getTitle() : "活动已删除");
            m.put("activityTime", act != null ? act.getStartTime() : null);
            result.add(m);
        }
        return R.ok(result);
    }

    // ═══ 浏览历史 ═══
    @GetMapping("/history")
    public R<?> history(@RequestParam(defaultValue="1") int pageNum,
                        @RequestParam(defaultValue="20") int pageSize) {
        return R.ok(clickLogMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<GsClickLog>().eq(GsClickLog::getUserId,
                        SecurityUtils.getCurrentUserId()).orderByDesc(GsClickLog::getId)));
    }

    // ═══ 视频直播 ═══

    /** 公众号端：获取正在直播/预告的直播间列表 */
    @GetMapping("/live-rooms/active")
    public R<?> activeLiveRooms(@RequestParam(defaultValue="10") int limit) {
        List<OpsLiveRoom> list = liveRoomMapper.selectList(
                new LambdaQueryWrapper<OpsLiveRoom>()
                        .in(OpsLiveRoom::getStatus, 0, 1)
                        .orderByDesc(OpsLiveRoom::getStatus)
                        .orderByAsc(OpsLiveRoom::getSortOrder)
                        .last("LIMIT " + limit));
        // 规范化封面 URL，过滤已知占位图域名
        for (OpsLiveRoom room : list) {
            if (room.getCoverUrl() != null && !room.getCoverUrl().isBlank()) {
                room.setCoverUrl(imageUrlUtil.normalizeImage(room.getCoverUrl()));
            }
        }
        return R.ok(list);
    }

    // ═══ 辅助 ═══
    private Long getCorpId() { try { return SecurityUtils.getCurrentCorpId(); } catch (Exception e) { return 3L; } }
    private Long getUserIdOrNull() { try { return SecurityUtils.getCurrentUserId(); } catch (Exception e) { return null; } }
}
