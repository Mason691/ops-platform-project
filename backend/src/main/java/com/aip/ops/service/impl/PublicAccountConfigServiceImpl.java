package com.aip.ops.service.impl;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.mapper.PublicAccountConfigMapper;
import com.aip.ops.service.IPublicAccountConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class PublicAccountConfigServiceImpl
        extends ServiceImpl<PublicAccountConfigMapper, PublicAccountConfig>
        implements IPublicAccountConfigService {

    public PublicAccountConfig getByAppId(String appId, Long corpId) {
        return getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getAppId, appId)
                .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                .eq(PublicAccountConfig::getStatus, 1)
                .last("LIMIT 1"));
    }

    @Override
    public PublicAccountConfig getDefaultConfig(Long corpId) {
        boolean scoped = corpId != null && corpId > 0;
        PublicAccountConfig cfg = getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(scoped, PublicAccountConfig::getCorpId, corpId)
                .eq(PublicAccountConfig::getIsDefault, 1)
                .eq(PublicAccountConfig::getStatus, 1)
                .orderByAsc(PublicAccountConfig::getId)
                .last("LIMIT 1"));
        if (cfg != null) return cfg;
        cfg = getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(scoped, PublicAccountConfig::getCorpId, corpId)
                .eq(PublicAccountConfig::getStatus, 1)
                .orderByAsc(PublicAccountConfig::getId)
                .last("LIMIT 1"));
        if (cfg != null || !scoped) return cfg;
        // 组织未单独配置公众号时，回退到平台默认公众号（H5 共用同一公众号）
        return getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getStatus, 1)
                .orderByDesc(PublicAccountConfig::getIsDefault)
                .orderByAsc(PublicAccountConfig::getId)
                .last("LIMIT 1"));
    }
}
