package com.aip.ops.service;
import com.aip.ops.entity.PublicAccountConfig;
import com.baomidou.mybatisplus.extension.service.IService;
public interface IPublicAccountConfigService extends IService<PublicAccountConfig> {
    PublicAccountConfig getByAppId(String appId, Long corpId);
}
