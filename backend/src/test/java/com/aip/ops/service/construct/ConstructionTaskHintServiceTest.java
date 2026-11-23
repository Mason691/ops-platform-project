package com.aip.ops.service.construct;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.sdk.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConstructionTaskHintServiceTest {

    @Mock private OpsBusinessOrderMapper orderMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    private ConstructionTaskHintService service;

    @BeforeEach
    void setUp() {
        service = new ConstructionTaskHintService(orderMapper, jdbcTemplate);
    }

    @Test
    void resolvesOnlyWorkerAssignedOnOrder() {
        when(orderMapper.selectById(8817L)).thenReturn(order(8817L, 577L, "江清兵"));

        ConstructionTaskHintService.OrderWorker worker = service.resolveOrderWorker(8817L);

        assertThat(worker.userId()).isEqualTo(577L);
        assertThat(worker.userName()).isEqualTo("江清兵");
    }

    @Test
    void missingWorkerFailsClosedInsteadOfRoleLookup() {
        when(orderMapper.selectById(8817L)).thenReturn(order(8817L, null, null));

        assertThatThrownBy(() -> service.resolveOrderWorker(8817L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未指派施工员");
    }

    private static OpsBusinessOrder order(Long id, Long workerId, String workerName) {
        OpsBusinessOrder order = new OpsBusinessOrder();
        order.setId(id);
        order.setOrderNo("23004");
        order.setWorkerId(workerId);
        order.setWorkerName(workerName);
        return order;
    }
}
