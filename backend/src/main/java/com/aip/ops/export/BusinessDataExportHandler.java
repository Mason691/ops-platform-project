package com.aip.ops.export;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysExportTask;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.impl.BusinessDataServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务数据导出处理器。
 * 从 sys_export_task.filters JSON 中提取筛选条件，
 * 查询 ops_business_data 并输出 TSV 格式。
 */
@Slf4j
@Component
public class BusinessDataExportHandler implements ExportHandler {

    private final OpsBusinessDataMapper mapper;
    private final OpsValueAddedProductMapper productMapper;
    private final DataScopeService dataScopeService;
    private final ObjectMapper objectMapper;

    public BusinessDataExportHandler(OpsBusinessDataMapper mapper,
                                     OpsValueAddedProductMapper productMapper,
                                     DataScopeService dataScopeService,
                                     ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.dataScopeService = dataScopeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getTaskType() {
        return "business_data";
    }

    @Override
    public void export(SysExportTask task, OutputStream out) throws Exception {
        // 解析筛选条件
        String company = null, batchNo = null, agent = null, brand = null, category = null;
        if (task.getFilters() != null && !task.getFilters().isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> filters = objectMapper.readValue(task.getFilters(), Map.class);
            company = filters.get("company");
            batchNo = filters.get("batchNo");
            agent = filters.get("agent");
            brand = filters.get("brand");
            category = filters.get("category");
        }

        // 构建查询
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
        if (brand != null && !brand.isEmpty()) qw.eq(OpsBusinessData::getBrand, brand);
        if (category != null && !category.isEmpty()) qw.eq(OpsBusinessData::getCategory, category);

        // 组织级数据隔离
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIdsForCorp(task.getCorpId());
        if (visibleCorpIds != null && !visibleCorpIds.isEmpty()) {
            qw.in(OpsBusinessData::getCorpId, visibleCorpIds);
        }

        List<OpsBusinessData> rows = mapper.selectList(qw);
        log.info("BusinessData export query: {} rows", rows.size());

        // 收据号相同的行相邻（空收据号沉底、组内 id 升序）
        BusinessDataServiceImpl.sortByReceiptNo(rows);
        // 同一订单拆分多行的收款金额仅首行显示（口径与提成均分一致）
        Set<Long> blankAmountIds = BusinessDataServiceImpl.computeSplitOrderNonFirstIds(rows);
        // 型号关键字行（如武山）兜底填充 零售底价/集团最低价（仅导出文件）
        List<OpsValueAddedProduct> products = productMapper.selectList(null);
        BusinessDataServiceImpl.fillExportPrices(rows, products);

        // 写 TSV
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.write("公司\t批次号\t客户\t经办人\t品牌\t品类\t型号\t收款金额\t销售价\t零售底价\t集团最低价\t销售日期\t收据号\t市场收款日期\t财务收款日期\t资金动态表横排数\t返利比例\t返利金额\t状态\n");
            for (OpsBusinessData row : rows) {
                writer.write(String.join("\t",
                        nvl(row.getCompany()),
                        nvl(row.getBatchNo()),
                        nvl(row.getCustomer()),
                        nvl(row.getAgent()),
                        nvl(row.getBrand()),
                        nvl(BusinessDataServiceImpl.getCategoryDisplayName(row.getCategory())),
                        nvl(row.getModel()),
                        blankAmountIds.contains(row.getId()) ? ""
                                : (row.getSalePrice() != null ? row.getSalePrice().toString() : ""),
                        row.getSalePrice() != null ? row.getSalePrice().toString() : "",
                        row.getRetailFloorPrice() != null ? row.getRetailFloorPrice().toString() : "",
                        row.getGroupLowestPrice() != null ? row.getGroupLowestPrice().toString() : "",
                        nvl(row.getSaleDate()),
                        nvl(row.getReceiptNo()),
                        nvl(row.getReceiptDate()),
                        row.getFinanceReceiptDate() != null ? row.getFinanceReceiptDate().toString() : "",
                        row.getFundDynamicRows() != null ? row.getFundDynamicRows().toString() : "",
                        row.getCommissionRate() != null ? row.getCommissionRate().toString() : "",
                        row.getCommissionAmount() != null ? row.getCommissionAmount().toString() : "",
                        nvl(row.getCalcStatus())));
                writer.newLine();
            }
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
