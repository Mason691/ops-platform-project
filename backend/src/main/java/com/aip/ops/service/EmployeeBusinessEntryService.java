package com.aip.ops.service;

import com.aip.ops.dto.BalanceReceiveRequest;
import com.aip.ops.dto.CommunityCreateRequest;
import com.aip.ops.dto.EmployeeBusinessEntryRequest;

import java.util.List;
import java.util.Map;

/**
 * 员工手工新增业务数据。
 *
 * <p>所有方法仅面向已登录员工：组织/当前经办人信息从 JWT + 平台 sys_user 派生，
 * 不接受前端传入的 corp_id / agent / agent_phone；共同经办人仅接受员工ID，由服务端回查组织归属。</p>
 */
public interface EmployeeBusinessEntryService {

    /** 表单选项：员工/公司信息、可选批次、品牌/品类/型号。 */
    Map<String, Object> getManualEntryOptions();

    /** 按客户手机号查询平台会员姓名（仅返回会员 id + 姓名）。 */
    Map<String, Object> lookupMember(String phone);

    /** 员工制单新建小区：自动关联当前组织所在区县。 */
    Map<String, Object> createCommunity(CommunityCreateRequest request);

    /** 保存手工录入的业务数据（同步发起工作流并带回下一审批人）。 */
    Map<String, Object> createManualEntry(EmployeeBusinessEntryRequest request);

    /** 草稿编辑数据：订单头 + 商品/旧品/凭证附件回显。 */
    Map<String, Object> draftEditData(Long orderId);

    /** 草稿编辑提交：沿用原订单编号/业务组号，替换明细并重新发起审批。 */
    Map<String, Object> updateDraft(Long orderId, EmployeeBusinessEntryRequest request);

    /** 以旧换新采购单创建失败/状态待同步时重试（SCM 侧幂等）。 */
    Map<String, Object> retryTradeInPurchase(Long orderId);

    /**
     * 货款补录：当前经办人名下定金支付且未全款的业务订单。
     * 经办人判定：当前员工手机号出现在 agent_phones，或 create_user = 当前用户。
     */
    List<Map<String, Object>> listDepositPending();

    /** 货款补录详情（校验订单属于当前经办人）。 */
    Map<String, Object> depositPendingDetail(Long orderId);

    /**
     * 提交货款补录：实收余款必须等于应补余款；凭证必填；成功后 is_full_payment=1。
     * 已全款订单幂等返回 alreadyReceived=true。
     */
    Map<String, Object> receiveBalance(Long orderId, BalanceReceiveRequest request);

    /** 结果页轮询/详情兜底：查询订单当前审批人。 */
    Map<String, Object> currentApprover(Long orderId);

    /**
     * 收据号查重（制单阶段失焦提示）：同组织内仅允许同一客户复用，
     * 被其他客户占用时返回 duplicate=true + message。
     */
    Map<String, Object> checkReceiptNo(String receiptNo, String customer);
}
