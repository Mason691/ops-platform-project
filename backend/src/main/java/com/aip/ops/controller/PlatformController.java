package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.OpsActivityService;
import com.aip.ops.service.IOpsProductService;
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
    private final IOpsProductService productService;
    private final OpsOrderMapper orderMapper;
    private final OpsActivityMapper activityMapper;
    private final OpsActivityService activityService;
    private final OpsAddressMapper addressMapper;
    private final OpsPointsLogMapper pointsLogMapper;
    private final OpsCouponMapper couponMapper;
    private final OpsUserCouponMapper userCouponMapper;
    private final OpsClickLogMapper clickLogMapper;
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
        Long platformUserId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        // JWT userId = platform sys_member.id, stored as ops_member.platform_member_id
        OpsMember u = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, platformUserId)
                .eq(OpsMember::getCorpId, corpId));
        // Fallback: legacy users whose platformMemberId was never populated
        if (u == null) {
            u = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                    .eq(OpsMember::getId, platformUserId)
                    .eq(OpsMember::getCorpId, corpId));
        }
        if (u == null) return R.fail("用户不存在");
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("id",u.getId()); r.put("phone",u.getPhone());
        r.put("nickname",u.getNickname()); r.put("avatar",u.getAvatar());
        r.put("pointsBalance",u.getPointsBalance());
        return R.ok(r);
    }

    @PutMapping("/user/profile")
    public R<?> updateProfile(@RequestBody Map<String,String> body) {
        Long localId = resolveLocalMemberId();
        if (localId == null) return R.fail("用户不存在");
        OpsMember u = new OpsMember(); u.setId(localId);
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
        Long platformUserId = SecurityUtils.getCurrentUserId();
        String imageData = body.get("imageData");
        if (imageData == null || imageData.isEmpty()) return R.fail("图片数据为空");

        // resolve local member ID (JWT userId is platform sys_member.id)
        Long localId = resolveLocalMemberId();
        if (localId == null) return R.fail("用户不存在");

        try {
            // 解析 base64 data URL: "data:image/png;base64,xxxxx"
            String base64 = imageData.contains(",") ? imageData.split(",")[1] : imageData;
            String ext = imageData.contains("png") ? "png" : imageData.contains("gif") ? "gif" : "jpg";
            String extFromData = imageData.contains("image/") ?
                    imageData.substring(imageData.indexOf("image/") + 6).split(";")[0] : "jpg";
            if (!extFromData.equals("jpeg")) ext = extFromData;
            if ("jpeg".equals(ext)) ext = "jpg";

            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            String fileName = "avatar_" + platformUserId + "_" + System.currentTimeMillis() + "." + ext;

            // 保存到文件系统（绝对路径，确保 Nginx 可读）
            java.io.File uploadDir = new java.io.File(uploadBaseDir + "/avatar/");
            if (!uploadDir.exists()) uploadDir.mkdirs();
            java.io.File file = new java.io.File(uploadDir, fileName);
            java.nio.file.Files.write(file.toPath(), bytes);

            String avatarUrl = "/uploads/avatar/" + fileName;

            // 更新数据库
            OpsMember u = new OpsMember();
            u.setId(localId);
            u.setAvatar(avatarUrl);
            userMapper.updateById(u);

            // 同步到底座平台会员头像
            syncAvatarToPlatform(localId, avatarUrl);

            return R.ok(Map.of("avatar", avatarUrl));
        } catch (Exception e) {
            log.error("头像上传失败: localId={}", localId, e);
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
        OpsClickLog cl = new OpsClickLog(); cl.setCorpId(corpId);
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

        OpsPointsLog log = new OpsPointsLog(); log.setCorpId(corpId); log.setUserId(userId);
        log.setChangeType("CONSUME"); log.setAmount(pointsSpent);
        log.setBalanceAfter(u.getPointsBalance()); log.setRemark("兑换商品：" + productName);
        pointsLogMapper.insert(log);

        String orderNo = "OPS" + System.currentTimeMillis() + userId;
        OpsOrder order = new OpsOrder(); order.setCorpId(corpId); order.setUserId(userId);
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
        Page<OpsOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsOrder>().eq(OpsOrder::getUserId, userId)
                        .orderByDesc(OpsOrder::getId));
        return R.ok(page);
    }

    /** 第三方回调：确认支付或退还积分 */
    @PostMapping("/order/{orderNo}/callback")
    public R<?> orderCallback(@PathVariable String orderNo, @RequestBody Map<String,Object> body) {
        OpsOrder o = orderMapper.selectOne(new LambdaQueryWrapper<OpsOrder>()
                .eq(OpsOrder::getOrderNo, orderNo));
        if (o == null) return R.fail("订单不存在");
        String action = (String) body.getOrDefault("action", "PAID");
        if ("PAID".equals(action)) {
            o.setStatus("PAID"); o.setThirdPartyOrderNo((String)body.get("thirdPartyOrderNo"));
        } else {
            o.setStatus("CANCELLED");
            OpsMember u = userMapper.selectById(o.getUserId());
            u.setPointsBalance(u.getPointsBalance() + o.getPointsSpent()); userMapper.updateById(u);
            OpsPointsLog log = new OpsPointsLog(); log.setCorpId(o.getCorpId());
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
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        int offset = (pageNum - 1) * pageSize;
        // Visibility: operator sees all under them, partner sees own + operator-assigned
        String corpIdStr = corpId != null ? String.valueOf(corpId) : "";
        List<OpsActivity> records = activityMapper.selectActivityPageVisible(
                offset, pageSize, type, category, status, corpIdStr);
        long total = activityMapper.countActivityPageVisible(type, category, status, corpIdStr);

        // 规范化图片 URL：补全 coverUrl → 相对路径统一处理
        for (OpsActivity a : records) {
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
        OpsActivity act = (OpsActivity) detail.get("activity");
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

        // 规范化直播间封面图
        OpsLiveRoom lr = (OpsLiveRoom) detail.get("liveRoom");
        if (lr != null && lr.getCoverUrl() != null && !lr.getCoverUrl().isBlank()) {
            lr.setCoverUrl(imageUrlUtil.normalizeImage(lr.getCoverUrl()));
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
        return R.ok(addressMapper.selectList(new LambdaQueryWrapper<OpsAddress>()
                .eq(OpsAddress::getUserId, userId).orderByDesc(OpsAddress::getIsDefault)));
    }

    @GetMapping("/address/default")
    public R<?> defaultAddress() {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(addressMapper.selectOne(new LambdaQueryWrapper<OpsAddress>()
                .eq(OpsAddress::getUserId, userId).eq(OpsAddress::getIsDefault, 1).last("LIMIT 1")));
    }

    @PostMapping("/address")
    public R<?> saveAddress(@RequestBody OpsAddress addr) {
        addr.setUserId(SecurityUtils.getCurrentUserId());
        addr.setCorpId(SecurityUtils.getCurrentCorpId());
        if (Integer.valueOf(1).equals(addr.getIsDefault())) clearDefault(addr.getUserId());
        addressMapper.insert(addr);
        return R.ok();
    }

    @PutMapping("/address/{id}")
    public R<?> updateAddress(@PathVariable Long id, @RequestBody OpsAddress addr) {
        Long userId = SecurityUtils.getCurrentUserId();
        addr.setId(id); addr.setUserId(userId);
        if (Integer.valueOf(1).equals(addr.getIsDefault())) clearDefault(userId);
        addressMapper.updateById(addr);
        return R.ok();
    }

    @DeleteMapping("/address/{id}")
    public R<?> deleteAddress(@PathVariable Long id) { addressMapper.deleteById(id); return R.ok(); }

    private void clearDefault(Long userId) {
        addressMapper.update(null, new LambdaUpdateWrapper<OpsAddress>()
                .eq(OpsAddress::getUserId, userId).set(OpsAddress::getIsDefault, 0));
    }

    // ═══ 积分 ═══
    @GetMapping("/points/log")
    public R<?> pointsLog(@RequestParam(defaultValue="1") int pageNum,
                          @RequestParam(defaultValue="20") int pageSize,
                          @RequestParam(required=false) String changeType) {
        Page<OpsPointsLog> page = pointsLogMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OpsPointsLog>().eq(OpsPointsLog::getUserId,
                        SecurityUtils.getCurrentUserId())
                        .eq(changeType != null && !changeType.isEmpty(), OpsPointsLog::getChangeType, changeType)
                        .orderByDesc(OpsPointsLog::getId));
        return R.ok(page);
    }

    // ═══ 优惠券 ═══
    @GetMapping("/coupon/list")
    public R<?> couponList() {
        return R.ok(couponMapper.selectList(new LambdaQueryWrapper<OpsCoupon>()
                .eq(OpsCoupon::getStatus, 1).gt(OpsCoupon::getRemainQty, 0)));
    }

    @PostMapping("/coupon/draw")
    public R<?> drawCoupon(@RequestBody Map<String,Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long couponId = Long.valueOf(body.get("couponId").toString());
        OpsCoupon c = couponMapper.selectById(couponId);
        if (c == null || c.getRemainQty() <= 0) return R.fail("优惠券已抢完");
        Long count = userCouponMapper.selectCount(new LambdaQueryWrapper<OpsUserCoupon>()
                .eq(OpsUserCoupon::getUserId, userId).eq(OpsUserCoupon::getCouponId, couponId));
        if (count > 0) return R.fail("已领取过该优惠券");
        c.setRemainQty(c.getRemainQty() - 1); couponMapper.updateById(c);
        OpsUserCoupon uc = new OpsUserCoupon(); uc.setCorpId(c.getCorpId());
        uc.setUserId(userId); uc.setCouponId(couponId);
        uc.setExpireTime(LocalDateTime.now().plusDays(c.getExpireDays() != null ? c.getExpireDays() : 30));
        uc.setStatus("UNUSED"); userCouponMapper.insert(uc);
        return R.ok();
    }

    @GetMapping("/coupon/my")
    public R<?> myCoupons() {
        return R.ok(userCouponMapper.selectList(new LambdaQueryWrapper<OpsUserCoupon>()
                .eq(OpsUserCoupon::getUserId, SecurityUtils.getCurrentUserId())
                .orderByDesc(OpsUserCoupon::getId)));
    }

    // ═══ 活动预约 ═══

    @PostMapping("/activity/{activityId}/book")
    public R<?> bookActivity(@PathVariable Long activityId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();

        OpsActivity activity = activityMapper.selectById(activityId);
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
            OpsActivity act = activityMapper.selectById(b.getActivityId());
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
                new LambdaQueryWrapper<OpsClickLog>().eq(OpsClickLog::getUserId,
                        SecurityUtils.getCurrentUserId()).orderByDesc(OpsClickLog::getId)));
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

    /**
     * 公开：获取单个直播室详情（含简介、liveStatus 计算）。
     * 供 H5 活动详情页和直播落地页使用，无需认证。
     */
    @GetMapping("/live-rooms/{id}")
    public R<?> liveRoomDetail(@PathVariable Long id) {
        OpsLiveRoom room = liveRoomMapper.selectById(id);
        if (room == null) return R.fail("直播间不存在");
        if (room.getCoverUrl() != null && !room.getCoverUrl().isBlank()) {
            room.setCoverUrl(imageUrlUtil.normalizeImage(room.getCoverUrl()));
        }

        // 计算 liveStatus，与 activity detail 保持一致
        Map<String, Object> liveStatus = new LinkedHashMap<>();
        String displayStatus;
        if (room.getStatus() != null && room.getStatus() == 1) {
            displayStatus = "live";
        } else if (room.getStatus() != null && room.getStatus() == 2) {
            displayStatus = "ended";
        } else {
            if (room.getStartTime() != null && LocalDateTime.now().isAfter(room.getStartTime())) {
                displayStatus = "live";
            } else {
                displayStatus = "upcoming";
            }
        }
        liveStatus.put("displayStatus", displayStatus);
        liveStatus.put("viewerCount", room.getViewerCount() != null ? room.getViewerCount() : 0);
        liveStatus.put("hasReplay", room.getReplayUrl() != null && !room.getReplayUrl().isBlank());
        if ("upcoming".equals(displayStatus) && room.getStartTime() != null) {
            long diffMs = java.time.Duration.between(LocalDateTime.now(), room.getStartTime()).toMillis();
            liveStatus.put("countdownMs", Math.max(0, diffMs));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liveRoom", room);
        result.put("liveStatus", liveStatus);
        return R.ok(result);
    }

    /**
     * 设置直播开播提醒。
     * 记录到 ops_remind_log，由 ActivityReminderService 在开播前推送。
     */
    @PostMapping("/live-rooms/{id}/remind")
    public R<?> remindLiveRoom(@PathVariable Long id) {
        OpsLiveRoom room = liveRoomMapper.selectById(id);
        if (room == null) return R.fail("直播间不存在");
        Long userId = getUserIdOrNull();
        if (userId == null) return R.fail("请先登录");

        // 防重复：同一用户对同一直播间只记录一次
        Long exist = remindLogMapper.selectCount(new LambdaQueryWrapper<OpsRemindLog>()
                .eq(OpsRemindLog::getLiveRoomId, id)
                .eq(OpsRemindLog::getRemindType, "LIVE")
                .eq(OpsRemindLog::getCreateUser, userId));
        if (exist > 0) {
            return R.ok(Map.of("reminded", true, "msg", "已设置提醒"));
        }

        OpsRemindLog rl = new OpsRemindLog();
        rl.setLiveRoomId(id);
        rl.setRemindType("LIVE");
        rl.setStatus(0); // 待发送
        rl.setCorpId(getCorpId());
        remindLogMapper.insert(rl);

        log.info("设置直播提醒: liveRoomId={} userId={} title={}", id, userId, room.getLiveTitle());
        return R.ok(Map.of("reminded", true, "msg", "已设置开播提醒"));
    }

    // ═══ 辅助 ═══
    private Long getCorpId() { try { return SecurityUtils.getCurrentCorpId(); } catch (Exception e) { return 3L; } }
    private Long getUserIdOrNull() { try { return SecurityUtils.getCurrentUserId(); } catch (Exception e) { return null; } }

    /** Resolve local ops_member.id from JWT userId (which is platform sys_member.id). */
    private Long resolveLocalMemberId() {
        Long platformUserId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        OpsMember m = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, platformUserId)
                .eq(OpsMember::getCorpId, corpId));
        if (m != null) return m.getId();
        // Fallback for legacy users without platformMemberId
        m = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getId, platformUserId)
                .eq(OpsMember::getCorpId, corpId));
        return m != null ? m.getId() : null;
    }
}
