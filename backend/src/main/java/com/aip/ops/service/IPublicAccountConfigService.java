package com.aip.ops.service;
import com.aip.ops.entity.PublicAccountConfig;
import com.baomidou.mybatisplus.extension.service.IService;
public interface IPublicAccountConfigService extends IService<PublicAccountConfig> {
    PublicAccountConfig getByAppId(String appId, Long corpId);

    /**
     * 取默认公众号配置：优先 is_default=1，其次任意启用配置。
     *
     * @param corpId 可空；非空时仅在该组织下查找
     */
    PublicAccountConfig getDefaultConfig(Long corpId);
}
