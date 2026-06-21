package com.aip.ops.controller;

import com.aip.ops.service.HomeConfigService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公众号 H5 首页数据 Controller — 公开接口。
 * 返回首页所需的 banners、videos、recommends 三部分数据。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class HomeController {

    private final HomeConfigService homeConfigService;

    /**
     * 获取首页展示数据。
     * 公开接口，无需登录。通过 X-Corp-Id 头或默认值确定运营商。
     */
    @GetMapping("/home-data")
    public R<?> homeData(@RequestParam(required = false) Long corpId) {
        if (corpId == null) {
            try {
                corpId = SecurityUtils.getCurrentCorpId();
            } catch (Exception e) {
                corpId = 3L; // fallback
            }
        }
        return R.ok(homeConfigService.getHomeData(corpId));
    }
}
