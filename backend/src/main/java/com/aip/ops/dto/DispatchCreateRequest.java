package com.aip.ops.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台「发起派工」请求。
 *
 * <p>客户信息默认从来源订单带出；如派发人修改，服务端会记录原始值留痕。
 * 施工类型必须选择至少一个可施工商品（sourceItemIds 为业务订单明细 id），
 * 入户随访类型不关联商品，remark 必填。</p>
 */
@Data
public class DispatchCreateRequest {

    private Long sourceOrderId;

    /** CONSTRUCTION=施工 / VISIT=入户随访。 */
    private String dispatchType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectTime;

    private Long assigneeUserId;

    /** 以下客户字段为空时取来源订单值。 */
    private String customerName;
    private String customerPhone;
    private Long communityId;
    private String communityName;
    private String detailedAddress;

    /** 入户随访任务说明（VISIT 必填）；施工类型可作为派工备注。 */
    private String remark;

    /** 施工商品：来源业务订单明细 id；为空表示全部可施工商品。 */
    private List<Long> sourceItemIds;
}
