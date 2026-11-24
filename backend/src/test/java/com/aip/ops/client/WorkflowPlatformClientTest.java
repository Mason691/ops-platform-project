package com.aip.ops.client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 通用业务进度通知：场景、接收人、变量与去重键透传给平台内部接口。 */
class WorkflowPlatformClientTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final WorkflowPlatformClient client =
            new WorkflowPlatformClient(restTemplate, "http://platform", "token");

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notifyBusinessProgressPostsSceneAndVars() {
        when(restTemplate.postForObject(eq("http://platform/api/internal/wf/notify"),
                org.mockito.ArgumentMatchers.any(), eq(Map.class))).thenReturn(Map.of());
        Map<String, Object> vars = Map.of("taskId", 11L, "orderNo", "23005",
                "handlerPhone", "13800000000");

        client.notifyBusinessProgress("BALANCE_PAID", 68L, 16L, vars, "BALANCE_PAID:8818:11");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).postForObject(eq("http://platform/api/internal/wf/notify"),
                captor.capture(), eq(Map.class));
        Map<String, Object> body = captor.getValue().getBody();
        assertEquals("BALANCE_PAID", body.get("sceneCode"));
        assertEquals(68L, body.get("instanceId"));
        assertEquals(16L, body.get("receiverUserId"));
        assertEquals("BALANCE_PAID:8818:11", body.get("dedupKey"));
        assertEquals(vars, body.get("vars"));
        assertEquals("token", captor.getValue().getHeaders().getFirst("X-Internal-Token"));
    }

    @Test
    void skipsWhenReceiverMissing() {
        client.notifyBusinessProgress("BALANCE_PAID", 68L, null, Map.of(), "dedup");

        verifyNoInteractions(restTemplate);
    }
}
