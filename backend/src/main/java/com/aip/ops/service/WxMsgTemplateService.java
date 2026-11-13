package com.aip.ops.service;

import com.aip.ops.entity.OpsWxMsgTemplate;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 公众号消息模板：运营商一套数据、按组织层级共享。
 */
public interface WxMsgTemplateService extends IService<OpsWxMsgTemplate> {

    /**
     * 业务发送取模板：当前组织自建启用 → 上级运营商 OPERATOR_TREE 启用 → 平台 PLATFORM 启用。
     */
    OpsWxMsgTemplate findActive(Long corpId, String sceneCode);

    /** 当前用户可见模板列表（运营商含下属自建审计；城市合伙人含共享+自建）。 */
    Map<String, Object> listForCurrentUser(String sceneCode, Integer status,
                                           Long ownerCorpId, String keyword);

    /** 模板详情（含 canEdit 等前端控制字段）。 */
    Map<String, Object> detailForCurrentUser(Long id);

    /** 新增模板；非超管强制归属当前组织，城市合伙人强制 PRIVATE。 */
    OpsWxMsgTemplate createForCurrentUser(OpsWxMsgTemplate template);

    /** 修改模板；仅归属组织或超管可改，归属/共享范围受角色约束。 */
    OpsWxMsgTemplate updateForCurrentUser(Long id, OpsWxMsgTemplate template);

    /** 启用/停用；停用对所有共享组织立即生效。 */
    void changeStatusForCurrentUser(Long id, Integer status);

    /** 删除；启用中的模板必须先停用。 */
    void deleteForCurrentUser(Long id);

    /** 当前组织可选模板（仅启用）：自建 + 上级共享 + 平台。 */
    List<Map<String, Object>> availableForCurrentUser(String sceneCode);

    /** 模板测试发送（发送到当前登录员工的微信）。 */
    Map<String, Object> testSendForCurrentUser(Long id);

    /** 业务发送成功后累计使用次数/最近使用时间。 */
    void recordUsage(Long templateId);

    /** 校验模板字段（含字段映射/按公众号映射 JSON）。 */
    void validate(OpsWxMsgTemplate template);
}
