package com.aip.ops.service;

import com.aip.ops.entity.OpsDispatchMessage;

import java.util.List;

/**
 * 派工单公众号消息推送。
 *
 * <p>推送失败不影响派工单创建：每次尝试落一条 {@link OpsDispatchMessage}，
 * 并把最近一次结果冗余到派工单主表，供列表提示与手动重发。</p>
 */
public interface DispatchMessageService {

    /** 异步发送派工通知；派工单事务提交后调用。 */
    void sendDispatchNoticeAsync(Long dispatchId);

    /** 异步发送取消通知（模板未配置时记录 SKIPPED，不阻断取消）。 */
    void sendCancelNoticeAsync(Long dispatchId);

    /** 手动重发最近一次失败的派工通知。 */
    void resendDispatchNotice(Long dispatchId);

    /** 员工打开工单详情时把本人未读消息置为已读。 */
    void markRead(Long dispatchId, Long receiverUserId);

    /** 某派工单的推送记录（按时间倒序）。 */
    List<OpsDispatchMessage> listByDispatch(Long dispatchId, Long corpId);
}
