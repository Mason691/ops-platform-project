package com.aip.ops.service.impl;

import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.config.MaterialWorkflowProperties;
import com.aip.ops.dto.MaterialWriteOffRequest;
import com.aip.ops.dto.MaterialWriteOffSummary;
import com.aip.ops.dto.MaterialWriteOffView;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsMaterialRequisition;
import com.aip.ops.entity.OpsMaterialRequisitionEntry;
import com.aip.ops.entity.OpsMaterialWriteOff;
import com.aip.ops.entity.OpsMaterialWriteOffEntry;
import com.aip.ops.mapper.OpsMaterialRequisitionEntryMapper;
import com.aip.ops.mapper.OpsMaterialRequisitionMapper;
import com.aip.ops.mapper.OpsMaterialWriteOffEntryMapper;
import com.aip.ops.mapper.OpsMaterialWriteOffMapper;
import com.aip.ops.service.EmployeeMaterialSupport;
import com.aip.ops.service.MaterialNoGenerator;
import com.aip.ops.service.MaterialWriteOffService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 员工物资核销实现。
 *
 * <p>允许部分核销：每条明细 核销数量 + 退回数量 ≤ 可核销余量，但整单至少有一条大于 0。
 * 同一领用单同一时间只允许一张 PENDING 核销单；审批通过后回写领用明细
 * written_off_qty/returned_qty，并重算领用单 write_off_status。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialWriteOffServiceImpl implements MaterialWriteOffService {

    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    private static final int MAX_ENTRIES = 50;
    private static final int MAX_VOUCHERS = 5;

    private final OpsMaterialWriteOffMapper writeOffMapper;
    private final OpsMaterialWriteOffEntryMapper entryMapper;
    private final OpsMaterialRequisitionMapper requisitionMapper;
    private final OpsMaterialRequisitionEntryMapper requisitionEntryMapper;
    private final EmployeeMaterialSupport support;
    private final MaterialNoGenerator noGenerator;
    private final WorkflowPlatformClient workflowClient;
    private final MaterialWorkflowProperties workflowProperties;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public MaterialWriteOffView create(MaterialWriteOffRequest request) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        if (request == null || request.requisitionId() == null) {
            throw BusinessException.of("请选择领用单");
        }
        String usageDesc = requireText(request.usageDesc(), "使用说明", 1000);
        String remark = optionalText(request.remark(), "备注", 500);
        LocalDate writeOffDate = request.writeOffDate() == null
                ? LocalDate.now() : request.writeOffDate();
        List<String> vouchers = validateVouchers(request.voucherUrls());
        EmployeeMaterialSupport.ParticipantSelection people =
                support.participants(ctx.corpId(), ctx.userId(), request.writeOffUserIds());
        Long id = transactionTemplate.execute(status ->
                persist(ctx, people, request, usageDesc, writeOffDate, remark, vouchers));
        if (id == null) {
            throw BusinessException.of("核销单保存失败，请重试");
        }
        WorkflowPlatformClient.StartResult started =
                startWorkflow(id, ctx, request.requisitionId(), usageDesc);
        return detailInternal(id, started);
    }

    @Override
    public Page<MaterialWriteOffSummary> page(String status, String keyword,
                                              LocalDate dateFrom, LocalDate dateTo,
                                              long pageNum, long pageSize) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        LambdaQueryWrapper<OpsMaterialWriteOff> qw = visibleWrapper(ctx);
        if (status != null && !status.isBlank()) qw.eq(OpsMaterialWriteOff::getStatus, status);
        String word = trim(keyword);
        if (word != null) {
            qw.and(w -> w.like(OpsMaterialWriteOff::getWriteOffNo, word)
                    .or().like(OpsMaterialWriteOff::getRequisitionNo, word));
        }
        if (dateFrom != null) qw.ge(OpsMaterialWriteOff::getWriteOffDate, dateFrom);
        if (dateTo != null) qw.le(OpsMaterialWriteOff::getWriteOffDate, dateTo);
        qw.orderByDesc(OpsMaterialWriteOff::getId);
        IPage<OpsMaterialWriteOff> page = writeOffMapper.selectPage(
                new Page<>(pageNum, pageSize), qw);
        Map<Long, List<OpsMaterialWriteOffEntry>> grouped = entriesOf(
                page.getRecords().stream().map(OpsMaterialWriteOff::getId).toList());
        Page<MaterialWriteOffSummary> result =
                new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(w -> summaryOf(w, grouped.getOrDefault(w.getId(), List.of())))
                .toList());
        return result;
    }

    @Override
    public MaterialWriteOffView detail(Long id) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        OpsMaterialWriteOff header = requireVisible(id, ctx);
        return toView(header, entriesOf(id), currentApprover(header));
    }

    @Override
    public MaterialWriteOffView approvalDetail(Long id) {
        // 安全边界在控制器：已用底座 taskId 校验当前用户是该单待办审批人且 businessId 匹配
        OpsMaterialWriteOff header = id == null ? null : writeOffMapper.selectById(id);
        if (header == null || Integer.valueOf(1).equals(header.getDeleteFlag())) {
            throw BusinessException.notFound("核销单不存在");
        }
        return toView(header, entriesOf(id), currentApprover(header));
    }

    @Override
    @Transactional
    public void handleWorkflowEvent(WorkflowBusinessEvent event) {
        Long id = parseLong(event.getBusinessId());
        if (id == null) {
            log.warn("物资核销回调业务ID不合法: {}", event.getBusinessId());
            return;
        }
        String instanceId = event.getInstanceId() == null
                ? null : String.valueOf(event.getInstanceId());
        if ("NODE_ENTERED".equals(event.getEvent())) {
            jdbcTemplate.update("UPDATE ops_material_write_off SET wf_node_order=?, "
                            + "wf_node_name=?, modify_time=NOW() WHERE id=? AND delete_flag=0 "
                            + "AND (process_instance_id IS NULL OR process_instance_id=?)",
                    event.getNodeOrder(), event.getNodeName(), id, instanceId);
            return;
        }
        String status = terminalStatus(event.getEvent());
        if (status == null) return;
        lockedWriteOff(id);
        OpsMaterialWriteOff header = writeOffMapper.selectById(id);
        if (header == null || Integer.valueOf(1).equals(header.getDeleteFlag())
                || !PENDING.equals(header.getStatus())) {
            return;
        }
        if (instanceId != null && header.getProcessInstanceId() != null
                && !instanceId.equals(header.getProcessInstanceId())) {
            log.warn("物资核销回调实例不匹配 id={} event={} local={}",
                    id, instanceId, header.getProcessInstanceId());
            return;
        }
        if (APPROVED.equals(status)) {
            applyApproval(header, entriesOf(id));
        }
        header.setStatus(status);
        header.setApprovalRemark(opinion(event, status));
        header.setApproveTime(LocalDateTime.now());
        writeOffMapper.updateById(header);
        log.info("物资核销审批完成 id={} status={}", id, status);
    }

    // ── 落库与校验 ────────────────────────────────────────────────────

    private Long persist(EmployeeMaterialSupport.EmployeeContext ctx,
                         EmployeeMaterialSupport.ParticipantSelection people,
                         MaterialWriteOffRequest request, String usageDesc,
                         LocalDate writeOffDate, String remark, List<String> vouchers) {
        OpsMaterialRequisition requisition = lockRequisition(ctx.corpId(), request.requisitionId());
        if (!"APPROVED".equals(requisition.getStatus())
                && !"ISSUED".equals(requisition.getStatus())) {
            throw BusinessException.of("领用单未审批通过，不能核销");
        }
        if (!csvContains(requisition.getApplicantUserIds(), ctx.userId())) {
            throw BusinessException.of("只能核销本人参与的领用单");
        }
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_material_write_off WHERE requisition_id=? "
                        + "AND status='PENDING' AND delete_flag=0",
                Integer.class, requisition.getId());
        if (pending != null && pending > 0) {
            throw BusinessException.of("该领用单已有待审核的核销单，请等待审批完成");
        }
        List<OpsMaterialWriteOffEntry> entries = validateEntries(
                requisition, request.entries());
        String no = noGenerator.next("WX");
        OpsMaterialWriteOff header = new OpsMaterialWriteOff();
        header.setCorpId(ctx.corpId());
        header.setWriteOffNo(no);
        header.setRequisitionId(requisition.getId());
        header.setRequisitionNo(requisition.getRequisitionNo());
        header.setApplicantUserId(requisition.getApplicantUserId());
        header.setApplicantName(requisition.getApplicantName());
        header.setDeptId(requisition.getDeptId());
        header.setDeptName(requisition.getDeptName());
        header.setWriteOffUserId(ctx.userId());
        header.setWriteOffUserName(ctx.name());
        header.setWriteOffUserIds(people.idsCsv());
        header.setWriteOffUserNames(people.namesCsv());
        header.setWriteOffDate(writeOffDate);
        header.setUsageDesc(usageDesc);
        header.setVoucherUrls(toJson(vouchers));
        header.setStatus(PENDING);
        header.setRemark(remark);
        writeOffMapper.insert(header);
        for (OpsMaterialWriteOffEntry entry : entries) {
            entry.setCorpId(ctx.corpId());
            entry.setWriteOffId(header.getId());
            entryMapper.insert(entry);
        }
        return header.getId();
    }

    private List<OpsMaterialWriteOffEntry> validateEntries(
            OpsMaterialRequisition requisition, List<MaterialWriteOffRequest.Entry> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            throw BusinessException.of("核销明细不能为空");
        }
        if (reqs.size() > MAX_ENTRIES) {
            throw BusinessException.of("单笔最多" + MAX_ENTRIES + "条明细");
        }
        List<Long> ids = reqs.stream().map(MaterialWriteOffRequest.Entry::requisitionEntryId)
                .filter(Objects::nonNull).toList();
        Map<Long, OpsMaterialRequisitionEntry> entryMap = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            requisitionEntryMapper.selectList(new LambdaQueryWrapper<OpsMaterialRequisitionEntry>()
                            .in(OpsMaterialRequisitionEntry::getId, ids)
                            .eq(OpsMaterialRequisitionEntry::getRequisitionId, requisition.getId())
                            .eq(OpsMaterialRequisitionEntry::getDeleteFlag, 0))
                    .forEach(e -> entryMap.put(e.getId(), e));
        }
        List<OpsMaterialWriteOffEntry> result = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        boolean anyPositive = false;
        for (MaterialWriteOffRequest.Entry req : reqs) {
            if (req == null || req.requisitionEntryId() == null) {
                throw BusinessException.of("核销明细缺少领用明细ID");
            }
            if (!seen.add(req.requisitionEntryId())) {
                throw BusinessException.of("同一领用明细不能重复核销");
            }
            OpsMaterialRequisitionEntry source = entryMap.get(req.requisitionEntryId());
            if (source == null) {
                throw BusinessException.of("核销明细不属于当前领用单");
            }
            int remaining = remaining(source);
            int writeOff = n(req.writeOffQty());
            int returnQty = n(req.returnQty());
            if (writeOff < 0 || returnQty < 0) {
                throw BusinessException.of("核销数量/退回数量必须为非负整数");
            }
            if (writeOff + returnQty > remaining) {
                throw BusinessException.of("核销数量+退回数量超过可核销余量：" + source.getMaterialName());
            }
            if (writeOff + returnQty > 0) anyPositive = true;
            OpsMaterialWriteOffEntry entry = new OpsMaterialWriteOffEntry();
            entry.setRequisitionEntryId(source.getId());
            entry.setMaterialId(source.getMaterialId());
            entry.setMaterialNumber(source.getMaterialNumber());
            entry.setMaterialName(source.getMaterialName());
            entry.setCategory(source.getCategory());
            entry.setBrand(source.getBrand());
            entry.setModel(source.getModel());
            entry.setUnit(source.getUnit());
            entry.setRequisitionQty(source.getQuantity());
            entry.setRemainingQty(remaining);
            entry.setWriteOffQty(writeOff);
            entry.setReturnQty(returnQty);
            entry.setRemark(optionalText(req.remark(), "明细备注", 500));
            result.add(entry);
        }
        if (!anyPositive) {
            throw BusinessException.of("请至少核销或退回一种物料");
        }
        return result;
    }

    private OpsMaterialRequisition lockRequisition(Long corpId, Long requisitionId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ops_material_requisition WHERE id=? AND corp_id=? "
                        + "AND delete_flag=0 FOR UPDATE", Long.class, requisitionId, corpId);
        if (ids.isEmpty()) {
            throw BusinessException.notFound("领用单不存在");
        }
        OpsMaterialRequisition header = requisitionMapper.selectById(requisitionId);
        if (header == null) throw BusinessException.notFound("领用单不存在");
        return header;
    }

    private void lockedWriteOff(Long id) {
        jdbcTemplate.queryForList(
                "SELECT id FROM ops_material_write_off WHERE id=? AND delete_flag=0 FOR UPDATE",
                Long.class, id);
    }

    /** 审批通过：按当前余量再次校验并回写领用明细，最后重算领用单核销状态。 */
    private void applyApproval(OpsMaterialWriteOff header, List<OpsMaterialWriteOffEntry> entries) {
        for (OpsMaterialWriteOffEntry entry : entries) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT quantity, written_off_qty, returned_qty "
                            + "FROM ops_material_requisition_entry WHERE id=? AND delete_flag=0 "
                            + "FOR UPDATE", entry.getRequisitionEntryId());
            int remaining = n(row.get("quantity")) - n(row.get("written_off_qty"))
                    - n(row.get("returned_qty"));
            int consume = n(entry.getWriteOffQty()) + n(entry.getReturnQty());
            if (consume > remaining) {
                throw BusinessException.of("可核销余量已变化，核销处理失败：" + entry.getMaterialName());
            }
            jdbcTemplate.update("UPDATE ops_material_requisition_entry SET "
                            + "written_off_qty=written_off_qty+?, returned_qty=returned_qty+?, "
                            + "modify_time=NOW() WHERE id=? AND delete_flag=0",
                    n(entry.getWriteOffQty()), n(entry.getReturnQty()),
                    entry.getRequisitionEntryId());
        }
        recomputeWriteOffStatus(header.getRequisitionId());
    }

    private void recomputeWriteOffStatus(Long requisitionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT quantity, written_off_qty, returned_qty "
                        + "FROM ops_material_requisition_entry WHERE requisition_id=? AND delete_flag=0",
                requisitionId);
        boolean anyRemaining = false;
        boolean anyProcessed = false;
        for (Map<String, Object> row : rows) {
            int remaining = n(row.get("quantity")) - n(row.get("written_off_qty"))
                    - n(row.get("returned_qty"));
            if (remaining > 0) anyRemaining = true;
            if (n(row.get("written_off_qty")) + n(row.get("returned_qty")) > 0) {
                anyProcessed = true;
            }
        }
        String status = !anyRemaining ? "COMPLETED" : (anyProcessed ? "PARTIAL" : "NONE");
        jdbcTemplate.update("UPDATE ops_material_requisition SET write_off_status=?, "
                + "modify_time=NOW() WHERE id=? AND delete_flag=0", status, requisitionId);
    }

    // ── 工作流与视图 ──────────────────────────────────────────────────

    private WorkflowPlatformClient.StartResult startWorkflow(
            Long id, EmployeeMaterialSupport.EmployeeContext ctx,
            Long requisitionId, String usageDesc) {
        OpsMaterialWriteOff saved = writeOffMapper.selectById(id);
        String no = saved != null ? saved.getWriteOffNo() : String.valueOf(id);
        try {
            WorkflowPlatformClient.StartResult started = workflowClient.start(
                    new WorkflowPlatformClient.StartRequest(
                            workflowProperties.getWriteOffDefinitionCode(),
                            workflowProperties.getWriteOffBusinessType(),
                            String.valueOf(id),
                            "物资核销 " + no + " | 领用单#" + requisitionId + " | " + usageDesc,
                            ctx.deptName() == null ? ctx.corpName() : ctx.deptName(),
                            "MATERIAL_WO:" + id + ":" + System.currentTimeMillis(),
                            ctx.corpId(), ctx.userId(), ctx.name()));
            jdbcTemplate.update("UPDATE ops_material_write_off SET process_instance_id=? "
                    + "WHERE id=?", started.instanceId(), id);
            return started;
        } catch (Exception e) {
            log.warn("物资核销发起审批失败 id={}: {}", id, e.getMessage());
            return WorkflowPlatformClient.StartResult.unresolved(null,
                    "审批流程发起失败，请联系管理员");
        }
    }

    private OpsMaterialWriteOff requireVisible(
            Long id, EmployeeMaterialSupport.EmployeeContext ctx) {
        OpsMaterialWriteOff header = id == null ? null : writeOffMapper.selectById(id);
        if (header == null || Integer.valueOf(1).equals(header.getDeleteFlag())
                || !Objects.equals(header.getCorpId(), ctx.corpId())
                || !csvContains(header.getWriteOffUserIds(), ctx.userId())) {
            throw BusinessException.notFound("核销单不存在");
        }
        return header;
    }

    private LambdaQueryWrapper<OpsMaterialWriteOff> visibleWrapper(
            EmployeeMaterialSupport.EmployeeContext ctx) {
        return new LambdaQueryWrapper<OpsMaterialWriteOff>()
                .eq(OpsMaterialWriteOff::getCorpId, ctx.corpId())
                .apply("FIND_IN_SET({0}, write_off_user_ids) > 0", ctx.userId());
    }

    private WorkflowPlatformClient.StartResult currentApprover(OpsMaterialWriteOff header) {
        if (!PENDING.equals(header.getStatus()) || header.getProcessInstanceId() == null
                || header.getProcessInstanceId().isBlank()) {
            return null;
        }
        return workflowClient.getCurrentApprover(parseLong(header.getProcessInstanceId()));
    }

    private MaterialWriteOffView detailInternal(
            Long id, WorkflowPlatformClient.StartResult approver) {
        OpsMaterialWriteOff header = writeOffMapper.selectById(id);
        return toView(header, entriesOf(id), approver);
    }

    private MaterialWriteOffView toView(OpsMaterialWriteOff header,
                                        List<OpsMaterialWriteOffEntry> entries,
                                        WorkflowPlatformClient.StartResult approver) {
        List<MaterialWriteOffView.EntryView> views = entries.stream()
                .map(e -> new MaterialWriteOffView.EntryView(
                        e.getId(), e.getRequisitionEntryId(), e.getMaterialId(),
                        e.getMaterialNumber(), e.getMaterialName(), e.getCategory(),
                        e.getBrand(), e.getModel(), e.getUnit(), e.getRequisitionQty(),
                        e.getRemainingQty(), e.getWriteOffQty(), e.getReturnQty(), e.getRemark()))
                .toList();
        int writeOffTotal = entries.stream().mapToInt(e -> n(e.getWriteOffQty())).sum();
        int returnTotal = entries.stream().mapToInt(e -> n(e.getReturnQty())).sum();
        return new MaterialWriteOffView(
                header.getId(), header.getWriteOffNo(), header.getRequisitionId(),
                header.getRequisitionNo(), header.getStatus(), header.getApplicantName(),
                header.getDeptId(), header.getDeptName(), header.getWriteOffUserNames(),
                header.getWriteOffDate(), header.getUsageDesc(), header.getRemark(),
                parseVouchers(header.getVoucherUrls()), header.getApprovalRemark(),
                header.getApproveTime(), header.getWfNodeName(),
                approver == null ? null : approver.approverName(),
                approver == null ? null : approver.approverRole(),
                approver == null || !approver.resolved()
                        ? null : approver.displayText(),
                approver != null ? approver.resolved()
                        : (PENDING.equals(header.getStatus()) ? Boolean.FALSE : null),
                approver != null ? approver.message()
                        : (PENDING.equals(header.getStatus())
                            ? "审批流程未发起，请联系管理员" : null),
                views, views.size(), writeOffTotal, returnTotal);
    }

    private MaterialWriteOffSummary summaryOf(OpsMaterialWriteOff header,
                                              List<OpsMaterialWriteOffEntry> entries) {
        int writeOffTotal = entries.stream().mapToInt(e -> n(e.getWriteOffQty())).sum();
        int returnTotal = entries.stream().mapToInt(e -> n(e.getReturnQty())).sum();
        return new MaterialWriteOffSummary(
                header.getId(), header.getWriteOffNo(), header.getRequisitionNo(),
                header.getStatus(), header.getWriteOffDate(), header.getApplicantName(),
                materialSummary(entries), entries.size(), writeOffTotal, returnTotal,
                header.getCreateTime(), header.getWfNodeName());
    }

    private Map<Long, List<OpsMaterialWriteOffEntry>> entriesOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<OpsMaterialWriteOffEntry> rows = entryMapper.selectList(
                new LambdaQueryWrapper<OpsMaterialWriteOffEntry>()
                        .in(OpsMaterialWriteOffEntry::getWriteOffId, ids)
                        .eq(OpsMaterialWriteOffEntry::getDeleteFlag, 0)
                        .orderByAsc(OpsMaterialWriteOffEntry::getId));
        Map<Long, List<OpsMaterialWriteOffEntry>> grouped = new LinkedHashMap<>();
        for (OpsMaterialWriteOffEntry row : rows) {
            grouped.computeIfAbsent(row.getWriteOffId(), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private List<OpsMaterialWriteOffEntry> entriesOf(Long id) {
        return entriesOf(List.of(id)).getOrDefault(id, List.of());
    }

    private List<String> validateVouchers(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw BusinessException.of("请至少上传1张使用凭证");
        }
        if (urls.size() > MAX_VOUCHERS) {
            throw BusinessException.of("使用凭证最多" + MAX_VOUCHERS + "个");
        }
        List<String> result = new ArrayList<>();
        for (String url : urls) {
            String text = trim(url);
            if (text == null || !text.startsWith("/uploads/") || text.length() > 500) {
                throw BusinessException.of("使用凭证地址不合法");
            }
            result.add(text);
        }
        return result;
    }

    private String toJson(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            throw BusinessException.of("凭证保存失败，请重试");
        }
    }

    private List<String> parseVouchers(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析核销凭证失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private static int remaining(OpsMaterialRequisitionEntry entry) {
        return n(entry.getQuantity()) - n(entry.getWrittenOffQty()) - n(entry.getReturnedQty());
    }

    private static int n(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int n(Integer value) {
        return value == null ? 0 : value;
    }

    private static String materialSummary(List<OpsMaterialWriteOffEntry> entries) {
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
