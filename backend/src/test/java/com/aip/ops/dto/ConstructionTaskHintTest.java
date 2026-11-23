package com.aip.ops.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstructionTaskHintTest {

    @Test
    void completionHintContainsWorkerOrderAndHandler() {
        ConstructionTaskHint hint = ConstructionTaskHint.of("江清兵", "23004", "孙莉娟");

        assertThat(hint.buttonText()).isEqualTo("提交验收申请");
        assertThat(hint.hintText()).isEqualTo(
                "施工任务完成后，施工员（江清兵）需向订单编号（23004）经办人：孙莉娟 "
                        + "提交施工完成验收，确认施工完成。");
        assertThat(hint.workerName()).isEqualTo("江清兵");
        assertThat(hint.orderNo()).isEqualTo("23004");
        assertThat(hint.handlerName()).isEqualTo("孙莉娟");
    }

    @Test
    void blankValuesFallBackWithoutBreakingTemplate() {
        ConstructionTaskHint hint = ConstructionTaskHint.of(" ", "23004", null);

        assertThat(hint.workerName()).isEqualTo("未指派");
        assertThat(hint.handlerName()).isEqualTo("订单经办人");
        assertThat(hint.hintText()).doesNotContain("null");
    }
}
