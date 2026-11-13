package com.aip.ops.service;

import com.aip.ops.entity.OpsWxMsgTemplate;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 公众号消息模板配置。
 */
public interface WxMsgTemplateService extends IService<OpsWxMsgTemplate> {

    /**
     * 取组织生效模板：优先组织覆盖（corp_id=派工组织），其次平台全局（corp_id=0）；
     * 同场景同组织仅一条 status=1。
     */
    OpsWxMsgTemplate findActive(Long corpId, String sceneCode);

    /** 保存/更新并校验字段映射 JSON；启用时互斥关闭同组织同场景的其它模板。 */
    OpsWxMsgTemplate saveTemplate(OpsWxMsgTemplate template);

    /** 校验模板必填项与 fieldMapping JSON。 */
    void validate(OpsWxMsgTemplate template);
}
