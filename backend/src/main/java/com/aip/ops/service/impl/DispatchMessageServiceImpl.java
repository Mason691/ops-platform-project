package com.aip.ops.service.impl;

import com.aip.ops.config.DispatchProperties;
import com.aip.ops.entity.OpsDispatchMessage;
import com.aip.ops.entity.OpsDispatchOrder;
import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.mapper.OpsDispatchMessageMapper;
import com.aip.ops.mapper.OpsDispatchOrderMapper;
import com.aip.ops.service.DispatchMessageService;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.ops.service.WxTemplateMessageSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 派工单消息推送实现。
 *
 * <p>异步线程无 SecurityContext，所有查询显式按 corpId/dispatchId 过滤。
 * 模板按「当前组织自建 → 上级运营商共享 → 平台全局」选取；公众号/openid/
 * 按公众号模板ID 由 {@link WxTemplateMessageSupport} 统一处理。</p>
 */
@Slf4j
@Service
public class DispatchMessageServiceImpl implements DispatchMessageService {

    public static final String SCENE_DISPATCH_NOTICE = "DISPATCH_NOTICE";
    public static final String SCENE_DISPATCH_CANCELLED = "DISPATCH_CANCELLED";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Executor executor;
    private final OpsDispatchOrderMapper dispatchMapper;
    private final OpsDispatchMessageMapper messageMapper;
    private final WxMsgTemplateService templateService;
    private final WxTemplateMessageSupport messageSupport;
    private final DispatchProperties properties;

    public DispatchMessageServiceImpl(
            @Qualifier("dispatchMessageExecutor") Executor executor,
            OpsDispatchOrderMapper dispatchMapper,
            OpsDispatchMessageMapper messageMapper,
            WxMsgTemplateService templateService,
            WxTemplateMessageSupport messageSupport,
            DispatchProperties properties) {
        this.executor = executor;
        this.dispatchMapper = dispatchMapper;
        this.messageMapper = messageMapper;
        this.templateService = templateService;
        this.messageSupport = messageSupport;
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
            String reason = "未配置可用公众号消息模板（当前组织、上级运营商、平台均无启用模板）";
            record(dispatch, scene, null, null, "SKIPPED", reason, retry);
            updateMainStatus(scene, dispatch.getId(), "SKIPPED", reason);
            return;
        }

        Map<String, String> vars = buildVariables(dispatch);
        String url = properties.getWorkOrderUrl().replace("{id}", String.valueOf(dispatch.getId()));
        WxTemplateMessageSupport.SendResult result = messageSupport.send(
                template, dispatch.getCorpId(), dispatch.getAssigneeUserId(), vars, url);
        record(dispatch, scene, result.templateId(), result.openid(),
                result.success() ? "SUCCESS" : "FAILED", result.error(), retry);
        updateMainStatus(scene, dispatch.getId(),
                result.success() ? "SUCCESS" : "FAILED", result.error());
        if (result.success()) {
            templateService.recordUsage(template.getId());
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

    private void record(OpsDispatchOrder dispatch, String scene, String templateId,
                        String openid, String status, String error, int retry) {
        OpsDispatchMessage message = new OpsDispatchMessage();
        message.setCorpId(dispatch.getCorpId());
        message.setDispatchId(dispatch.getId());
        message.setSceneCode(scene);
        message.setTemplateId(templateId);
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
