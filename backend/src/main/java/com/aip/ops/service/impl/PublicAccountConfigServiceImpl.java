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
}
