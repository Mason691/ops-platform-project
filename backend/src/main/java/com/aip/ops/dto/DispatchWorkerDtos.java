package com.aip.ops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 派工外部施工员 DTO（JDK 17 Record）。
 */
public final class DispatchWorkerDtos {

    private DispatchWorkerDtos() {
    }

    /**
     * 新增外部施工员请求。corpId 由服务端从 orderId 解析，前端不传。
     * phone 设为只写，操作审计序列化时不落明文手机号。
     */
    public record ExternalWorkerCreateReq(
            Long orderId,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String phone,
            String name,
            Boolean confirmSameName
    ) {
    }

    public record OrgBrief(Long corpId, String corpName) {
    }

    public record WorkerCheckResp(
            boolean exists,
            boolean deleted,
            boolean reusable,
            boolean wechatBound,
            String userName,
            String phoneMasked,
            List<OrgBrief> orgs,
            boolean inCurrentOrg,
            boolean hasWorkerRole,
            String errorCode,
            String message
    ) {
    }

    public record ExternalWorkerCreateResp(
            Long userId,
            Long userOrgId,
            String userName,
            String phoneMasked,
            boolean reused,
            boolean newNaturalPerson,
            boolean wechatBound,
            boolean sameNameConfirmRequired,
            String sameNamePhoneMasked,
            String errorCode,
            String message
    ) {
    }

    /** 审计事件：不含明文手机号，供 @OperationLog 切面序列化。 */
    public record ExternalWorkerAuditEvent(
            Long orderId,
            Long orderCorpId,
            Long userId,
            String userName,
            String phoneMasked,
            boolean reused,
            boolean newNaturalPerson,
            boolean sameNameConfirmed
    ) {
    }
}
