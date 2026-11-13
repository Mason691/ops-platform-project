package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.mapper.OpsWxMsgTemplateMapper;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WxMsgTemplateServiceImpl
        extends ServiceImpl<OpsWxMsgTemplateMapper, OpsWxMsgTemplate>
        implements WxMsgTemplateService {

    private final ObjectMapper objectMapper;

    @Override
    public OpsWxMsgTemplate findActive(Long corpId, String sceneCode) {
        if (sceneCode == null || sceneCode.isBlank()) return null;
        Long targetCorp = corpId == null ? 0L : corpId;
        OpsWxMsgTemplate scoped = getOne(new LambdaQueryWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getCorpId, targetCorp)
                .eq(OpsWxMsgTemplate::getSceneCode, sceneCode)
                .eq(OpsWxMsgTemplate::getStatus, 1)
                .orderByDesc(OpsWxMsgTemplate::getModifyTime)
                .orderByDesc(OpsWxMsgTemplate::getId)
                .last("LIMIT 1"));
        if (scoped != null) return scoped;
        return getOne(new LambdaQueryWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getCorpId, 0L)
                .eq(OpsWxMsgTemplate::getSceneCode, sceneCode)
                .eq(OpsWxMsgTemplate::getStatus, 1)
                .orderByDesc(OpsWxMsgTemplate::getModifyTime)
                .orderByDesc(OpsWxMsgTemplate::getId)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional
    public OpsWxMsgTemplate saveTemplate(OpsWxMsgTemplate template) {
        if (template == null) throw BusinessException.of("模板不能为空");
        if (template.getCorpId() == null) template.setCorpId(0L);
        if (template.getStatus() == null) template.setStatus(1);
        validate(template);
        if (template.getId() == null) {
            save(template);
        } else {
            OpsWxMsgTemplate existing = getById(template.getId());
            if (existing == null) throw BusinessException.notFound("模板不存在");
            updateById(template);
        }
        if (Integer.valueOf(1).equals(template.getStatus())) {
            // 同组织同场景只允许一条启用模板，避免推送时随机命中
            update(new LambdaUpdateWrapper<OpsWxMsgTemplate>()
                    .eq(OpsWxMsgTemplate::getCorpId, template.getCorpId())
                    .eq(OpsWxMsgTemplate::getSceneCode, template.getSceneCode())
                    .ne(OpsWxMsgTemplate::getId, template.getId())
                    .set(OpsWxMsgTemplate::getStatus, 0));
        }
        return getById(template.getId());
    }

    @Override
    public void validate(OpsWxMsgTemplate template) {
        if (template.getSceneCode() == null || template.getSceneCode().isBlank()) {
            throw BusinessException.of("请选择业务场景");
        }
        if (template.getTemplateId() == null || template.getTemplateId().isBlank()) {
            throw BusinessException.of("请填写微信公众号模板ID");
        }
        if (template.getTemplateId().trim().length() > 128) {
            throw BusinessException.of("模板ID不能超过128个字符");
        }
        String mapping = template.getFieldMapping();
        if (mapping == null || mapping.isBlank()) {
            throw BusinessException.of("请配置变量字段映射（JSON）");
        }
        try {
            JsonNode node = objectMapper.readTree(mapping);
            if (!node.isObject() || node.isEmpty()) {
                throw BusinessException.of("变量字段映射必须是 JSON 对象");
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            java.util.Set<String> wxFields = new java.util.HashSet<>();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                    throw BusinessException.of("变量字段映射的值必须是非空字符串：" + entry.getKey());
                }
                if (!wxFields.add(entry.getValue().asText())) {
                    throw BusinessException.of("微信模板字段重复映射：" + entry.getValue().asText());
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("变量字段映射不是合法的 JSON");
        }
    }
}
