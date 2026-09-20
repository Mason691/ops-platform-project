package com.aip.ops.dto;

import lombok.Data;

/**
 * 导入型号匹配确认决策。
 *
 * <p>{@code key} 为校验阶段返回的确认项标识（品牌品类key型号关键词）；
 * {@code productId} 为选中的增值产品 id，null 表示拒绝该匹配（对应行校验失败）。</p>
 */
@Data
public class ModelConfirmDecision {

    private String key;

    private Long productId;
}
