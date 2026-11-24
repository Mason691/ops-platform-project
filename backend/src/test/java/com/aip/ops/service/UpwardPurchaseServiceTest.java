package com.aip.ops.service;

import com.aip.ops.dto.UpwardPurchaseDtos;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsUpwardPurchase;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsUpwardPurchaseMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpwardPurchaseServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OpsUpwardPurchase.class);
        TableInfoHelper.initTableInfo(assistant, OpsBusinessData.class);
    }

    private final OpsUpwardPurchaseMapper purchaseMapper = mock(OpsUpwardPurchaseMapper.class);
    private final OpsBusinessDataMapper itemMapper = mock(OpsBusinessDataMapper.class);
    private final BusinessDataService businessDataService = mock(BusinessDataService.class);
    private final UpwardPurchaseService service = new UpwardPurchaseService(
            purchaseMapper, itemMapper, businessDataService, new ObjectMapper());

    @Test
    void parseSelectionsSupportsListAndCsvRowIds() {
        Map<String, Object> form = Map.of("upwardPurchaseItems", List.of(
                Map.of("productKey", "k1", "rowIds", List.of(101, 102), "checked", true),
                Map.of("productKey", "k2", "rowIds", "103,104", "checked", false)
        ));

        List<UpwardPurchaseDtos.SelectionItem> result = service.parseSelections(form);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).rowIds()).containsExactly(101L, 102L);
        assertThat(result.get(0).checked()).isTrue();
        assertThat(result.get(1).rowIds()).containsExactly(103L, 104L);
        assertThat(result.get(1).checked()).isFalse();
    }

    @Test
    void parseSelectionsMissingFormReturnsEmptyForOldClient() {
        assertThat(service.parseSelections(Map.of())).isEmpty();
        assertThat(service.parseSelections(null)).isEmpty();
    }

    @Test
    void createPendingBuildsRecordWithStockSnapshot() {
        OpsBusinessOrder order = order();
        Map<String, Object> product = Map.of(
                "productKey", "报警器|华为|X1|99.00",
                "isUpwardPurchase", true,
                "isReturnEntry", false,
                "rowIds", List.of(101L, 102L),
                "category", "报警器",
                "brand", "华为",
                "model", "X1",
                "saleQuantity", 2,
                "salePrice", new BigDecimal("99.00"));
        when(businessDataService.listProductsForOrder(1L)).thenReturn(List.of(product));
        when(purchaseMapper.selectList(any())).thenReturn(List.of());
        when(purchaseMapper.update(any(), any())).thenReturn(0);
        when(purchaseMapper.insert(any(OpsUpwardPurchase.class))).thenReturn(1);
        Map<String, Object> snapshot = Map.of(
                "localStock", 0,
                "unit", "个",
                "others", List.of(Map.of("corpId", 9L, "corpName", "临洮", "stock", 5)));
        Map<String, Object> form = Map.of("stockSnapshots", Map.of(
                "报警器|华为|X1|99.00", snapshot));

        int count = service.createPending(order, form, 16L, "孙莉娟");

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<OpsUpwardPurchase> captor =
                ArgumentCaptor.forClass(OpsUpwardPurchase.class);
        verify(purchaseMapper).insert(captor.capture());
        OpsUpwardPurchase record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo(UpwardPurchaseService.STATUS_PENDING);
        assertThat(record.getOrderId()).isEqualTo(1L);
        assertThat(record.getCorpId()).isEqualTo(9L);
        assertThat(record.getItemId()).isEqualTo(101L);
        assertThat(record.getItemIds()).isEqualTo("101,102");
        assertThat(record.getQuantity()).isEqualByComparingTo("2");
        assertThat(record.getLocalStock()).isEqualByComparingTo("0");
        assertThat(record.getOtherStockJson()).contains("临洮");
        assertThat(record.getFinanceCheckTime()).isNotNull();
    }

    @Test
    void createPendingSkipsReturnEntryAndUncheckedProduct() {
        OpsBusinessOrder order = order();
        Map<String, Object> returnRow = Map.of(
                "productKey", "return", "isUpwardPurchase", true, "isReturnEntry", true,
                "rowIds", List.of(101L));
        Map<String, Object> unchecked = Map.of(
                "productKey", "unchecked", "isUpwardPurchase", false, "isReturnEntry", false,
                "rowIds", List.of(102L));
        when(businessDataService.listProductsForOrder(1L)).thenReturn(List.of(returnRow, unchecked));
        when(purchaseMapper.selectList(any())).thenReturn(List.of());
        when(purchaseMapper.update(any(), any())).thenReturn(0);

        int count = service.createPending(order, Map.of(), 16L, "孙莉娟");

        assertThat(count).isZero();
        verify(purchaseMapper, never()).insert(any(OpsUpwardPurchase.class));
        // 未勾选/无选中时仍需失效该订单已有的待发起记录
        verify(purchaseMapper).update(any(), any());
    }

    @Test
    void pendingItemsReturnsAllRowsAcrossPagedList() {
        OpsUpwardPurchase first = new OpsUpwardPurchase();
        first.setId(1L);
        first.setItemIds("101,102");
        OpsUpwardPurchase second = new OpsUpwardPurchase();
        second.setId(2L);
        second.setItemIds("103");
        when(purchaseMapper.selectList(any())).thenReturn(List.of(first, second));

        UpwardPurchaseDtos.PendingItems result = service.pendingItems(9L, 1L);

        assertThat(result.itemIds()).containsExactly(101L, 102L, 103L);
        assertThat(result.recordIds()).containsExactly(1L, 2L);
    }

    private static OpsBusinessOrder order() {
        OpsBusinessOrder order = new OpsBusinessOrder();
        order.setId(1L);
        order.setCorpId(9L);
        order.setOrderNo("23005");
        order.setReceiptNo("999");
        order.setBatchNo("B1");
        order.setCustomer("张三");
        order.setDeleteFlag(0);
        return order;
    }
}
