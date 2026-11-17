package com.aip.ops.service.impl;

import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.config.MaterialWorkflowProperties;
import com.aip.ops.dto.MaterialRequisitionRequest;
import com.aip.ops.dto.MaterialRequisitionSummary;
import com.aip.ops.dto.MaterialRequisitionView;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsMaterialRequisition;
import com.aip.ops.entity.OpsMaterialRequisitionEntry;
import com.aip.ops.mapper.OpsMaterialRequisitionEntryMapper;
import com.aip.ops.mapper.OpsMaterialRequisitionMapper;
import com.aip.ops.service.EmployeeMaterialSupport;
import com.aip.ops.service.MaterialNoGenerator;
import com.aip.ops.service.MaterialRequisitionService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 员工物资领用实现。
 *
 * <p>提交即落库（PENDING）并同步发起底座审批；审批通过后可核销，核销审批通过后
 * 累加明细的已核销/已退回数量。库存不阻断（申请制）：物资领用不绑仓库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialRequisitionServiceImpl implements MaterialRequisitionService {

    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    private static final int MAX_ENTRIES = 50;
    private static final int MAX_QTY = 99999;

    private final OpsMaterialRequisitionMapper requisitionMapper;
    private final OpsMaterialRequisitionEntryMapper entryMapper;
    private final EmployeeMaterialSupport support;
    private final MaterialNoGenerator noGenerator;
    private final WorkflowPlatformClient workflowClient;
    private final MaterialWorkflowProperties workflowProperties;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public MaterialRequisitionView create(MaterialRequisitionRequest request) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        if (request == null) {
            throw BusinessException.of("领用信息不能为空");
        }
        String purpose = requireText(request.purpose(), "领用用途", 500);
        String remark = optionalText(request.remark(), "备注", 500);
        LocalDate expectedDate = request.expectedDate() == null
                ? LocalDate.now() : request.expectedDate();
        EmployeeMaterialSupport.ParticipantSelection people =
                support.participants(ctx.corpId(), ctx.userId(), request.applicantUserIds());
        List<OpsMaterialRequisitionEntry> entries = validateEntries(ctx.corpId(), request.entries());
        String no = noGenerator.next("LY");
        Long id = transactionTemplate.execute(status ->
                persist(ctx, people, no, purpose, expectedDate, remark, entries));
        if (id == null) {
            throw BusinessException.of("领用单保存失败，请重试");
        }
        WorkflowPlatformClient.StartResult started = startWorkflow(id, ctx, no, purpose, entries);
        return detailInternal(id, started);
    }

    @Override
    public Page<MaterialRequisitionSummary> page(String status, String keyword,
                                                 LocalDate dateFrom, LocalDate dateTo,
                                                 long pageNum, long pageSize) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        LambdaQueryWrapper<OpsMaterialRequisition> qw = visibleWrapper(ctx);
        if (status != null && !status.isBlank()) qw.eq(OpsMaterialRequisition::getStatus, status);
        String word = trim(keyword);
        if (word != null) {
            String like = "%" + word + "%";
            qw.and(w -> w.like(OpsMaterialRequisition::getRequisitionNo, word)
                    .or().like(OpsMaterialRequisition::getPurpose, word)
                    .or().apply("EXISTS (SELECT 1 FROM ops_material_requisition_entry e "
                            + "WHERE e.requisition_id = ops_material_requisition.id "
                            + "AND e.delete_flag = 0 AND e.material_name LIKE {0})", like));
        }
        if (dateFrom != null) qw.ge(OpsMaterialRequisition::getExpectedDate, dateFrom);
        if (dateTo != null) qw.le(OpsMaterialRequisition::getExpectedDate, dateTo);
        qw.orderByDesc(OpsMaterialRequisition::getId);
        IPage<OpsMaterialRequisition> page = requisitionMapper.selectPage(
                new Page<>(pageNum, pageSize), qw);
        Map<Long, List<OpsMaterialRequisitionEntry>> grouped = entriesOf(
                page.getRecords().stream().map(OpsMaterialRequisition::getId).toList());
        Page<MaterialRequisitionSummary> result =
                new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(h -> summaryOf(h, grouped.getOrDefault(h.getId(), List.of())))
                .toList());
        return result;
    }

    @Override
    public MaterialRequisitionView detail(Long id) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        OpsMaterialRequisition header = requireVisible(id, ctx);
        WorkflowPlatformClient.StartResult approver = currentApprover(header);
        return toView(header, entriesOf(id), approver);
    }

    @Override
    public List<MaterialRequisitionSummary> pickable(String keyword,
                                                     LocalDate dateFrom, LocalDate dateTo) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        LambdaQueryWrapper<OpsMaterialRequisition> qw = visibleWrapper(ctx)
                .in(OpsMaterialRequisition::getStatus, APPROVED, "ISSUED")
                .ne(OpsMaterialRequisition::getWriteOffStatus, "COMPLETED")
                .apply("NOT EXISTS (SELECT 1 FROM ops_material_write_off w "
                        + "WHERE w.requisition_id = ops_material_requisition.id "
                        + "AND w.status = 'PENDING' AND w.delete_flag = 0)");
        String word = trim(keyword);
        if (word != null) {
            String like = "%" + word + "%";
            qw.and(w -> w.like(OpsMaterialRequisition::getRequisitionNo, word)
                    .or().like(OpsMaterialRequisition::getPurpose, word)
                    .or().apply("EXISTS (SELECT 1 FROM ops_material_requisition_entry e "
                            + "WHERE e.requisition_id = ops_material_requisition.id "
                            + "AND e.delete_flag = 0 AND e.material_name LIKE {0})", like));
        }
        if (dateFrom != null) qw.ge(OpsMaterialRequisition::getExpectedDate, dateFrom);
        if (dateTo != null) qw.le(OpsMaterialRequisition::getExpectedDate, dateTo);
        qw.orderByDesc(OpsMaterialRequisition::getId).last("LIMIT 200");
        List<OpsMaterialRequisition> rows = requisitionMapper.selectList(qw);
        Map<Long, List<OpsMaterialRequisitionEntry>> grouped = entriesOf(
                rows.stream().map(OpsMaterialRequisition::getId).toList());
        List<MaterialRequisitionSummary> result = new ArrayList<>();
        for (OpsMaterialRequisition row : rows) {
            List<OpsMaterialRequisitionEntry> entries =
                    grouped.getOrDefault(row.getId(), List.of());
            if (entries.stream().anyMatch(e -> remaining(e) > 0)) {
                result.add(summaryOf(row, entries));
            }
        }
        return result;
    }

    @Override
    public void handleWorkflowEvent(WorkflowBusinessEvent event) {
        Long id = parseLong(event.getBusinessId());
        if (id == null) {
            log.warn("物资领用回调业务ID不合法: {}", event.getBusinessId());
            return;
        }
        String instanceId = event.getInstanceId() == null
                ? null : String.valueOf(event.getInstanceId());
        if ("NODE_ENTERED".equals(event.getEvent())) {
            jdbcTemplate.update("UPDATE ops_material_requisition SET wf_node_order=?, "
                            + "wf_node_name=?, modify_time=NOW() WHERE id=? AND delete_flag=0 "
                            + "AND (process_instance_id IS NULL OR process_instance_id=?)",
                    event.getNodeOrder(), event.getNodeName(), id, instanceId);
            return;
        }
        String status = terminalStatus(event.getEvent());
        if (status == null) return;
        OpsMaterialRequisition header = requisitionMapper.selectById(id);
        if (header == null || Integer.valueOf(1).equals(header.getDeleteFlag())
                || !PENDING.equals(header.getStatus())) {
            return;
        }
        if (instanceId != null && header.getProcessInstanceId() != null
                && !instanceId.equals(header.getProcessInstanceId())) {
            log.warn("物资领用回调实例不匹配 id={} event={} local={}",
                    id, instanceId, header.getProcessInstanceId());
            return;
        }
        header.setStatus(status);
        header.setApprovalRemark(opinion(event, status));
        header.setApproveTime(LocalDateTime.now());
        requisitionMapper.updateById(header);
        log.info("物资领用审批完成 id={} status={}", id, status);
    }

    // ── 落库与校验 ────────────────────────────────────────────────────

    private Long persist(EmployeeMaterialSupport.EmployeeContext ctx,
                         EmployeeMaterialSupport.ParticipantSelection people,
                         String no, String purpose, LocalDate expectedDate, String remark,
                         List<OpsMaterialRequisitionEntry> entries) {
        OpsMaterialRequisition header = new OpsMaterialRequisition();
        header.setCorpId(ctx.corpId());
        header.setRequisitionNo(no);
        header.setApplicantUserId(ctx.userId());
        header.setApplicantName(ctx.name());
        header.setApplicantUserIds(people.idsCsv());
        header.setApplicantNames(people.namesCsv());
        header.setDeptId(ctx.deptId());
        header.setDeptName(ctx.deptName());
        header.setPurpose(purpose);
        header.setExpectedDate(expectedDate);
        header.setStatus(PENDING);
        header.setWriteOffStatus("NONE");
        header.setRemark(remark);
        requisitionMapper.insert(header);
        for (OpsMaterialRequisitionEntry entry : entries) {
            entry.setCorpId(ctx.corpId());
            entry.setRequisitionId(header.getId());
            entry.setWrittenOffQty(0);
            entry.setReturnedQty(0);
            entryMapper.insert(entry);
        }
        return header.getId();
    }

    private List<OpsMaterialRequisitionEntry> validateEntries(
            Long corpId, List<MaterialRequisitionRequest.Entry> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            throw BusinessException.of("请至少添加一条物料");
        }
        Map<Long, MaterialRequisitionRequest.Entry> merged = new LinkedHashMap<>();
        for (MaterialRequisitionRequest.Entry req : reqs) {
            if (req == null || req.materialId() == null || req.materialId() <= 0) {
                throw BusinessException.of("请选择商品");
            }
            int qty = req.quantity() == null ? 0 : req.quantity();
            if (qty <= 0) throw BusinessException.of("领用数量必须为正整数");
            MaterialRequisitionRequest.Entry current = merged.get(req.materialId());
            if (current == null) {
                merged.put(req.materialId(), new MaterialRequisitionRequest.Entry(
                        req.materialId(), qty, req.remark()));
            } else {
                int sum = current.quantity() + qty;
                if (sum > MAX_QTY) throw BusinessException.of("领用数量不能超过" + MAX_QTY);
                merged.put(req.materialId(), new MaterialRequisitionRequest.Entry(
                        req.materialId(), sum, current.remark()));
            }
        }
        if (merged.size() > MAX_ENTRIES) {
            throw BusinessException.of("单笔最多" + MAX_ENTRIES + "种物料");
        }
        List<OpsMaterialRequisitionEntry> result = new ArrayList<>();
        for (MaterialRequisitionRequest.Entry req : merged.values()) {
            var material = support.requireMaterial(corpId, req.materialId());
            if (req.quantity() > MAX_QTY) {
                throw BusinessException.of("领用数量不能超过" + MAX_QTY);
            }
            OpsMaterialRequisitionEntry entry = new OpsMaterialRequisitionEntry();
            entry.setMaterialId(material.id());
            entry.setMaterialNumber(material.number());
            entry.setMaterialName(material.name());
            entry.setCategory(material.category());
            entry.setBrand(material.brand());
            entry.setModel(material.model());
            entry.setUnit(material.unit());
            entry.setQuantity(req.quantity());
            entry.setRemark(optionalText(req.remark(), "明细备注", 500));
            result.add(entry);
        }
        return result;
    }

    // ── 工作流与视图 ──────────────────────────────────────────────────

    private WorkflowPlatformClient.StartResult startWorkflow(
            Long id, EmployeeMaterialSupport.EmployeeContext ctx, String no,
            String purpose, List<OpsMaterialRequisitionEntry> entries) {
        int total = entries.stream().mapToInt(e -> e.getQuantity() == null ? 0 : e.getQuantity()).sum();
        try {
            WorkflowPlatformClient.StartResult started = workflowClient.start(
                    new WorkflowPlatformClient.StartRequest(
                            workflowProperties.getRequisitionDefinitionCode(),
                            workflowProperties.getRequisitionBusinessType(),
                            String.valueOf(id),
                            "物资领用 " + no + " | " + purpose + " | "
                                    + entries.size() + "种 " + total + "件",
                            ctx.deptName() == null ? ctx.corpName() : ctx.deptName(),
                            "MATERIAL_REQ:" + id + ":" + System.currentTimeMillis(),
                            ctx.corpId(), ctx.userId(), ctx.name()));
            jdbcTemplate.update("UPDATE ops_material_requisition SET process_instance_id=? "
                    + "WHERE id=?", started.instanceId(), id);
            return started;
        } catch (Exception e) {
            log.warn("物资领用发起审批失败 id={}: {}", id, e.getMessage());
            return WorkflowPlatformClient.StartResult.unresolved(null,
                    "审批流程发起失败，请联系管理员");
        }
    }

    private OpsMaterialRequisition requireVisible(
            Long id, EmployeeMaterialSupport.EmployeeContext ctx) {
        OpsMaterialRequisition header = id == null ? null : requisitionMapper.selectById(id);
        if (header == null || Integer.valueOf(1).equals(header.getDeleteFlag())
                || !Objects.equals(header.getCorpId(), ctx.corpId())
                || !csvContains(header.getApplicantUserIds(), ctx.userId())) {
            throw BusinessException.notFound("领用单不存在");
        }
        return header;
    }

    private LambdaQueryWrapper<OpsMaterialRequisition> visibleWrapper(
            EmployeeMaterialSupport.EmployeeContext ctx) {
        return new LambdaQueryWrapper<OpsMaterialRequisition>()
                .eq(OpsMaterialRequisition::getCorpId, ctx.corpId())
                .apply("FIND_IN_SET({0}, applicant_user_ids) > 0", ctx.userId());
    }

    private WorkflowPlatformClient.StartResult currentApprover(OpsMaterialRequisition header) {
        if (!PENDING.equals(header.getStatus()) || header.getProcessInstanceId() == null
                || header.getProcessInstanceId().isBlank()) {
            return null;
        }
        return workflowClient.getCurrentApprover(
                parseLong(header.getProcessInstanceId()));
    }

    private MaterialRequisitionView detailInternal(
            Long id, WorkflowPlatformClient.StartResult approver) {
        OpsMaterialRequisition header = requisitionMapper.selectById(id);
        return toView(header, entriesOf(id), approver);
    }

    private MaterialRequisitionView toView(
            OpsMaterialRequisition header, List<OpsMaterialRequisitionEntry> entries,
            WorkflowPlatformClient.StartResult approver) {
        List<MaterialRequisitionView.EntryView> views = entries.stream()
                .map(e -> new MaterialRequisitionView.EntryView(
                        e.getId(), e.getMaterialId(), e.getMaterialNumber(), e.getMaterialName(),
                        e.getCategory(), e.getBrand(), e.getModel(), e.getUnit(),
                        e.getQuantity(), e.getWrittenOffQty(), e.getReturnedQty(),
                        remaining(e), e.getRemark()))
                .toList();
        int total = entries.stream().mapToInt(e -> e.getQuantity() == null ? 0 : e.getQuantity()).sum();
        return new MaterialRequisitionView(
                header.getId(), header.getRequisitionNo(), header.getStatus(),
                header.getApplicantUserId(), header.getApplicantName(), header.getApplicantNames(),
                header.getDeptId(), header.getDeptName(), header.getPurpose(),
                header.getExpectedDate(), header.getRemark(), header.getWriteOffStatus(),
                header.getCreateTime(), header.getApprovalRemark(), header.getApproveTime(),
                header.getWfNodeName(),
                approver == null ? null : approver.approverName(),
                approver == null ? null : approver.approverRole(),
                approver != null ? approver.resolved()
                        : (PENDING.equals(header.getStatus()) ? Boolean.FALSE : null),
                approver != null ? approver.message()
                        : (PENDING.equals(header.getStatus())
                            ? "审批流程未发起，请联系管理员" : null),
                views, views.size(), total);
    }

    private MaterialRequisitionSummary summaryOf(
            OpsMaterialRequisition header, List<OpsMaterialRequisitionEntry> entries) {
        int total = entries.stream().mapToInt(e -> e.getQuantity() == null ? 0 : e.getQuantity()).sum();
        return new MaterialRequisitionSummary(
                header.getId(), header.getRequisitionNo(), header.getStatus(),
                header.getExpectedDate(), header.getApplicantNames(), header.getPurpose(),
                materialSummary(entries), entries.size(), total, header.getWriteOffStatus(),
                header.getCreateTime(), header.getWfNodeName());
    }

    private Map<Long, List<OpsMaterialRequisitionEntry>> entriesOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<OpsMaterialRequisitionEntry> rows = entryMapper.selectList(
                new LambdaQueryWrapper<OpsMaterialRequisitionEntry>()
                        .in(OpsMaterialRequisitionEntry::getRequisitionId, ids)
                        .eq(OpsMaterialRequisitionEntry::getDeleteFlag, 0)
                        .orderByAsc(OpsMaterialRequisitionEntry::getId));
        Map<Long, List<OpsMaterialRequisitionEntry>> grouped = new LinkedHashMap<>();
        for (OpsMaterialRequisitionEntry row : rows) {
            grouped.computeIfAbsent(row.getRequisitionId(), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private List<OpsMaterialRequisitionEntry> entriesOf(Long id) {
        return entriesOf(List.of(id)).getOrDefault(id, List.of());
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private static int remaining(OpsMaterialRequisitionEntry entry) {
        return n(entry.getQuantity()) - n(entry.getWrittenOffQty()) - n(entry.getReturnedQty());
    }

    private static int n(Integer value) {
        return value == null ? 0 : value;
    }

    private static String materialSummary(List<OpsMaterialRequisitionEntry> entries) {
        if (entries.isEmpty()) return "";
        String first = entries.get(0).getMaterialName();
        return entries.size() == 1 ? first : first + " 等" + entries.size() + "种";
    }

    private static boolean csvContains(String csv, Long userId) {
        if (csv == null || userId == null) return false;
        for (String part : csv.split(",")) {
            if (part.trim().equals(String.valueOf(userId))) return true;
        }
        return false;
    }

    private static String terminalStatus(String event) {
        if ("INSTANCE_COMPLETED".equals(event)) return APPROVED;
        if ("INSTANCE_REJECTED".equals(event) || "INSTANCE_TERMINATED".equals(event)) {
            return REJECTED;
        }
        return null;
    }

    private static String opinion(WorkflowBusinessEvent event, String status) {
        String opinion = event.getOpinion();
        if (opinion != null && !opinion.isBlank()) return opinion;
        return APPROVED.equals(status) ? "审批通过" : "审批驳回";
    }

    private static String requireText(String value, String label, int max) {
        String text = trim(value);
        if (text == null) throw BusinessException.of(label + "不能为空");
        if (text.length() > max) throw BusinessException.of(label + "不能超过" + max + "字");
        return text;
    }

    private static String optionalText(String value, String label, int max) {
        String text = trim(value);
        if (text != null && text.length() > max) {
            throw BusinessException.of(label + "不能超过" + max + "字");
        }
        return text;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { /* ignore */ }
        }
        return null;
    }
}
