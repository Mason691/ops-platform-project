package com.aip.ops.service.construct;

import com.aip.ops.dto.ConstructActionDtos;
import com.aip.ops.entity.OpsBusinessOrder;

import java.util.List;
import java.util.Map;

/**
 * 订单施工状态链（派工 → 待接受 → 已接受 → 已上门 → 客户已确认 → 施工中 → 待验收）。
 *
 * <p>所有写操作都在订单行锁内完成状态机校验 + CAS 更新 + 留痕；
 * 客户签字另存版本化告知书。施工员身份、订单归属组织由服务端强校验。</p>
 */
public interface OrderConstructService {

    /** 施工流转视图：状态、权限、时间线、告知书、施工记录（员工/派工人/经办人均可读）。 */
    Map<String, Object> view(Long orderId);

    /** 施工员确认接受：WAIT_ACCEPT → ACCEPTED。 */
    Map<String, Object> accept(Long orderId);

    /** 施工员拒绝接受：WAIT_ACCEPT → DISPATCH，并驳回工作流任务退回派工节点。 */
    Map<String, Object> reject(Long orderId, String reason);

    /** 上门打卡：ACCEPTED → ARRIVED（定位失败可手填地址，现场照片 0~3 张）。 */
    Map<String, Object> arrive(Long orderId, ConstructActionDtos.ArriveRequest request);

    /** 客户手写签字确认并自动进入施工中：ARRIVED/CUSTOMER_REFUSED → CONSTRUCTING。 */
    Map<String, Object> customerConfirm(Long orderId,
                                        ConstructActionDtos.CustomerConfirmRequest request);

    /** 客户拒绝确认：ARRIVED → CUSTOMER_REFUSED（异常，需运营介入）。 */
    Map<String, Object> customerRefuse(Long orderId, String reason);

    /** 兼容旧客户端：已签字未开工的历史订单可继续进入施工中；新流程签字后自动开工。 */
    Map<String, Object> start(Long orderId);

    /**
     * 提交验收申请时一次性保存施工记录（施工说明/完工说明/现场照片），
     * 状态进入 5 施工完成，等待订单经办人验收。
     */
    Map<String, Object> submitAcceptanceRecord(Long orderId, String constructionNote,
                                               String completionNote,
                                               List<Long> attachmentIds,
                                               Long operatorUserId, String operatorName);

    /** 复算有效告知书签字图片的 SHA-256，核验归档凭证未被篡改。 */
    Map<String, Object> verifyNotice(Long orderId);

    /**
     * 派工成功回调：写入施工员并置为待接受（施工）/施工中（入户随访，保持旧口径）。
     * 由工作流派工节点动作在同一业务流程内调用。
     */
    void markDispatched(OpsBusinessOrder order, Long workerUserId, String workerName,
                        String workerPhone, Integer workerType, String dispatchType,
                        Long operatorUserId, String operatorName, String remark);

    /**
     * 记录本次派工任务ID（独立事务先落库）：SCM 建单成功但后续状态回写失败时，
     * 同一任务重试可识别为幂等重试，复用 SCM 出库单而不是被防重守卫拦住。
     */
    void markDispatchAttempt(Long orderId, Long taskId);

    /** 是否存在「派工 → 拒绝接受」历史：重新派工复用已有出库单，不再新建。 */
    boolean isRedispatchAfterReject(Long orderId);
}
