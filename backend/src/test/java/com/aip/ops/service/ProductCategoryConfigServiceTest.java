package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 品类品牌规则字典解析：报警器可无品牌，存量品类默认必填。 */
@ExtendWith(MockitoExtension.class)
class ProductCategoryConfigServiceTest {

    @Mock
    private PlatformInternalClient platformInternalClient;

    @InjectMocks
    private ProductCategoryConfigService service;

    @Test
    void readsOptionalConfigurationAndDefaultsMissingToNull() {
        when(platformInternalClient.get("/api/dict/data?typeCode=PRODUCT_CATEGORY_BRAND", null))
                .thenReturn(Map.of("data", List.of(
                        Map.of("itemKey", "报警器", "itemValue", "0"),
                        Map.of("itemKey", "燃气灶", "itemValue", "1"))));

        assertTrue(service.isBrandOptional("报警器"));
        assertEquals(Boolean.FALSE, service.brandRequired("报警器"));
        assertEquals(Boolean.TRUE, service.brandRequired("燃气灶"));
        assertNull(service.brandRequired("未配置品类"));
        assertFalse(service.isBrandOptional("燃气灶"));
    }
}
