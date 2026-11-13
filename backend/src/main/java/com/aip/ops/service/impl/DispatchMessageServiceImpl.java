package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsDispatchMessage;
import com.aip.ops.entity.OpsDispatchOrder;
import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.mapper.OpsDispatchMessageMapper;
import com.aip.ops.mapper.OpsDispatchOrderMapper;
import com.aip.ops.service.DispatchMessageService;
import com.aip.ops.service.IPublicAccountConfigService;
import com.aip.ops.service.WechatOfficialAccountClient;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.sdk.client.PlatformInternalClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 派工单消息推送实现。
 *
 * <p>异步线程无 SecurityContext，所有查询显式按 corpId/dispatchId 过滤；
 * 微信 openid 由底座内部接口按 userId + appId 返回（不落地到前端）。</p>
 */
@Slf4j
@Service
public class DispatchMessageServiceImpl implements DispatchMessageService {

    public static final String SCENE_DISPATCH_NOTICE = "DISPATCH_NOTICE";
    public static final String SCENE_DISPATCH_CANCELLED = "DISPATCH_CANCELLED";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DEFAULT_MAPPING =
            "{\"customerName\":\"thing1\",\"dispatchType\":\"thing2\","
                    + "\"expectTime\":\"time3\",\"dispatchNo\":\"character_string4\"}";

    private final Executor executor;
    private final OpsDispatchOrderMapper dispatchMapper;
    private final OpsDispatchMessageMapper messageMapper;
    private final WxMsgTemplateService templateService;
    private final IPublicAccountConfigService accountConfigService;
    private final WechatOfficialAccountClient wechatClient;
    private final PlatformInternalClient platformClient;
    private final ObjectMapper objectMapper;
    private final com.aip.ops.config.DispatchProperties properties;

    public DispatchMessageServiceImpl(
            @Qualifier("dispatchMessageExecutor") Executor executor,
            OpsDispatchOrderMapper dispatchMapper,
            OpsDispatchMessageMapper messageMapper,
            WxMsgTemplateService templateService,
            IPublicAccountConfigService accountConfigService,
            WechatOfficialAccountClient wechatClient,
            PlatformInternalClient platformClient,
            ObjectMapper objectMapper,
            com.aip.ops.config.DispatchProperties properties) {
        this.executor = executor;
        this.dispatchMapper = dispatchMapper;
        this.messageMapper = messageMapper;
        this.templateService = templateService;
        this.accountConfigService = accountConfigService;
        this.wechatClient = wechatClient;
        this.platformClient = platformClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void sendDispatchNoticeAsync(Long dispatchId) {
        submit(dispatchId, SCENE_DISPATCH_NOTICE, 0);
    }

    @Override
    public void sendCancelNoticeAsync(Long dispatchId) {
        submit(dispatchId, SCENE_DISPATCH_CANCELLED, 0);
    }

    @Override
    public void resendDispatchNotice(Long dispatchId) {
        int retry = 1;
        OpsDispatchMessage latest = latestMessage(dispatchId);
        if (latest != null && latest.getRetryCount() != null) {
            retry = latest.getRetryCount() + 1;
        }
        submit(dispatchId, SCENE_DISPATCH_NOTICE, retry);
    }

    private void submit(Long dispatchId, String scene, int retry) {
        try {
            executor.execute(() -> {
                try {
                    send(dispatchId, scene, retry);
                } catch (Exception e) {
                    log.error("派工单消息推送异常 dispatchId={}, scene={}", dispatchId, scene, e);
                }
            });
        } catch (Exception e) {
            log.error("派工单消息提交线程池失败 dispatchId={}, scene={}", dispatchId, scene, e);
        }
    }

    private void send(Long dispatchId, String scene, int retry) {
        OpsDispatchOrder dispatch = dispatchMapper.selectById(dispatchId);
        if (dispatch == null || Integer.valueOf(1).equals(dispatch.getDeleteFlag())) {
            log.warn("派工单不存在，跳过消息推送 dispatchId={}", dispatchId);
            return;
        }
        OpsWxMsgTemplate template = templateService.findActive(
                dispatch.getCorpId(), scene);
        if (template == null) {
            record(dispatch, scene, null, null, "SKIPPED",
                    "未配置公众号消息模板（" + scene + "）", retry);
            updateMainStatus(scene, dispatch.getId(), "SKIPPED",
                    "未配置公众号消息模板（" + scene + "）");
            return;
        }

        PublicAccountConfig account = resolveAccount(dispatch.getCorpId(), template.getAppId());
        if (account == null) {
            record(dispatch, scene, template, null, "FAILED", "未配置可用公众号", retry);
            updateMainStatus(scene, dispatch.getId(), "FAILED", "未配置可用公众号");
            return;
        }
        String openid = fetchOpenid(dispatch.getAssigneeUserId(), account.getAppId());
        if (openid == null) {
            record(dispatch, scene, template, null, "FAILED",
                    "员工未绑定微信或未关注公众号", retry);
            updateMainStatus(scene, dispatch.getId(), "FAILED",
                    "员工未绑定微信或未关注公众号");
            return;
        }

        Map<String, String> data = buildTemplateData(dispatch, template);
        String url = properties.getWorkOrderUrl().replace("{id}", String.valueOf(dispatch.getId()));
        WechatOfficialAccountClient.SendResult result = wechatClient.sendTemplateMessage(
                account, openid, template.getTemplateId(), url, data);
        record(dispatch, scene, template, openid,
                result.success() ? "SUCCESS" : "FAILED", result.error(), retry);
        updateMainStatus(scene, dispatch.getId(),
                result.success() ? "SUCCESS" : "FAILED", result.error());
    }

    private PublicAccountConfig resolveAccount(Long corpId, String appId) {
        if (appId != null && !appId.isBlank()) {
            PublicAccountConfig cfg = accountConfigService.getByAppId(appId, corpId);
            if (cfg != null) return cfg;
        }
        return accountConfigService.getDefaultConfig(corpId);
    }

    @SuppressWarnings("unchecked")
    private String fetchOpenid(Long userId, String appId) {
        if (userId == null || appId == null || appId.isBlank()) return null;
        try {
            String path = "/api/internal/user/wechat/openid?userId=" + userId
                    + "&appId=" + URLEncoder.encode(appId, StandardCharsets.UTF_8);
            Map<String, Object> resp = platformClient.get(path, null);
            Object data = resp == null ? null : resp.get("data");
            if (data instanceof Map<?, ?> map) {
                Object openid = map.get("openid");
                return openid instanceof String s && !s.isBlank() ? s : null;
            }
        } catch (Exception e) {
            log.warn("查询员工微信 openid 失败 userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private Map<String, String> buildTemplateData(OpsDispatchOrder dispatch,
                                                  OpsWxMsgTemplate template) {
        Map<String, String> vars = buildVariables(dispatch);
        Map<String, String> mapping = parseMapping(template.getFieldMapping());
        Map<String, String> data = new LinkedHashMap<>();
        mapping.forEach((logical, wxField) ->
                data.put(wxField, vars.getOrDefault(logical, "")));
        return data;
    }

    private Map<String, String> parseMapping(String json) {
        String source = json == null || json.isBlank() ? DEFAULT_MAPPING : json;
        try {
            return objectMapper.readValue(source, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("消息模板字段映射解析失败，使用默认映射: {}", e.getMessage());
            try {
                return objectMapper.readValue(DEFAULT_MAPPING,
                        new TypeReference<LinkedHashMap<String, String>>() {
                        });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
    }

    private Map<String, String> buildVariables(OpsDispatchOrder dispatch) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("customerName", nvl(dispatch.getCustomerName()));
        vars.put("customerPhone", nvl(dispatch.getCustomerPhone()));
        vars.put("communityName", nvl(dispatch.getCommunityName()));
        vars.put("detailedAddress", nvl(dispatch.getDetailedAddress()));
        vars.put("dispatchType", "VISIT".equals(dispatch.getDispatchType())
                ? "入户随访" : "施工");
        vars.put("expectTime", format(dispatch.getExpectTime()));
        vars.put("dispatchTime", format(dispatch.getDispatchTime()));
        vars.put("dispatchNo", nvl(dispatch.getDispatchNo()));
        vars.put("assigneeName", nvl(dispatch.getAssigneeName()));
        vars.put("remark", nvl(dispatch.getRemark()));
        return vars;
    }

    private void record(OpsDispatchOrder dispatch, String scene, OpsWxMsgTemplate template,
                        String openid, String status, String error, int retry) {
        OpsDispatchMessage message = new OpsDispatchMessage();
        message.setCorpId(dispatch.getCorpId());
        message.setDispatchId(dispatch.getId());
        message.setSceneCode(scene);
        message.setTemplateId(template == null ? null : template.getTemplateId());
        message.setReceiverUserId(dispatch.getAssigneeUserId());
        message.setOpenid(openid);
        message.setSendStatus(status);
        message.setErrorMsg(truncate(error, 500));
        message.setSendTime(LocalDateTime.now());
        message.setReadStatus("UNREAD");
        message.setRetryCount(retry);
        messageMapper.insert(message);
    }

    /** 只有派工通知才回写主表消息状态；取消通知只落记录，避免覆盖派工送达结果。 */
    private void updateMainStatus(String scene, Long dispatchId, String status, String error) {
        if (SCENE_DISPATCH_NOTICE.equals(scene)) {
            updateDispatchMessageStatus(dispatchId, status, error);
        }
    }

    private void updateDispatchMessageStatus(Long dispatchId, String status, String error) {
        dispatchMapper.update(null, new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, dispatchId)
                .set(OpsDispatchOrder::getMessageStatus, status)
                .set(OpsDispatchOrder::getMessageError, truncate(error, 500)));
    }

    private OpsDispatchMessage latestMessage(Long dispatchId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<OpsDispatchMessage>()
                .eq(OpsDispatchMessage::getDispatchId, dispatchId)
                .orderByDesc(OpsDispatchMessage::getId)
                .last("LIMIT 1"));
    }

    @Override
    public void markRead(Long dispatchId, Long receiverUserId) {
        if (dispatchId == null || receiverUserId == null) return;
        messageMapper.update(null, new LambdaUpdateWrapper<OpsDispatchMessage>()
                .eq(OpsDispatchMessage::getDispatchId, dispatchId)
                .eq(OpsDispatchMessage::getReceiverUserId, receiverUserId)
                .eq(OpsDispatchMessage::getReadStatus, "UNREAD")
                .set(OpsDispatchMessage::getReadStatus, "READ")
                .set(OpsDispatchMessage::getReadTime, LocalDateTime.now()));
    }

    @Override
    public List<OpsDispatchMessage> listByDispatch(Long dispatchId, Long corpId) {
        List<OpsDispatchMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<OpsDispatchMessage>()
                        .eq(OpsDispatchMessage::getDispatchId, dispatchId)
                        .eq(OpsDispatchMessage::getCorpId, corpId)
                        .orderByDesc(OpsDispatchMessage::getId));
        messages.forEach(m -> m.setOpenid(maskOpenid(m.getOpenid())));
        return messages;
    }

    private static String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 10) return openid;
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
