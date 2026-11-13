package com.aip.ops.service.impl;

import com.aip.ops.config.DispatchProperties;
import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.mapper.OpsWxMsgTemplateMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.ops.service.WxTemplateMessageSupport;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公众号消息模板实现。
 *
 * <p>共享模型：
 * <ul>
 *   <li>PRIVATE：仅归属组织可见（子公司自建默认）；</li>
 *   <li>OPERATOR_TREE：归属运营商 + 其下所有城市合伙人（按 sys_corp.parent_corp_id 动态生效）；</li>
 *   <li>PLATFORM：owner=0，所有组织可见（超管维护）。</li>
 * </ul>
 * 运营商可查看下属自建模板用于审计，但不能编辑；城市合伙人只能维护本公司 PRIVATE 模板。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxMsgTemplateServiceImpl
        extends ServiceImpl<OpsWxMsgTemplateMapper, OpsWxMsgTemplate>
        implements WxMsgTemplateService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SCOPES = Set.of(
            OpsWxMsgTemplate.SCOPE_PRIVATE,
            OpsWxMsgTemplate.SCOPE_OPERATOR_TREE,
            OpsWxMsgTemplate.SCOPE_PLATFORM);

    private final ObjectMapper objectMapper;
    private final OrgRoleService orgRoleService;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;
    private final WxTemplateMessageSupport messageSupport;
    private final DispatchProperties dispatchProperties;
    private final JwtTokenInspector jwtTokenInspector;

    // ═══ 业务发送取模板 ═══

    @Override
    public OpsWxMsgTemplate findActive(Long corpId, String sceneCode) {
        if (corpId == null || corpId <= 0 || sceneCode == null || sceneCode.isBlank()) {
            return null;
        }
        // 1) 当前组织自建（含运营商自己的 PRIVATE/OPERATOR_TREE）
        OpsWxMsgTemplate self = latest(corpId, null, sceneCode);
        if (self != null) return self;

        // 2) 上级运营商共享（城市合伙人）
        Long operatorCorp = orgRoleService.getOperatorCorpId(corpId);
        if (operatorCorp != null && operatorCorp > 0 && !operatorCorp.equals(corpId)) {
            OpsWxMsgTemplate shared = latest(operatorCorp,
                    OpsWxMsgTemplate.SCOPE_OPERATOR_TREE, sceneCode);
            if (shared != null) return shared;
        }

        // 3) 平台全局
        return latest(0L, OpsWxMsgTemplate.SCOPE_PLATFORM, sceneCode);
    }

    private OpsWxMsgTemplate latest(Long ownerCorpId, String scope, String sceneCode) {
        return getOne(new LambdaQueryWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getOwnerCorpId, ownerCorpId)
                .eq(scope != null, OpsWxMsgTemplate::getShareScope, scope)
                .eq(OpsWxMsgTemplate::getSceneCode, sceneCode)
                .eq(OpsWxMsgTemplate::getStatus, 1)
                .orderByDesc(OpsWxMsgTemplate::getModifyTime)
                .orderByDesc(OpsWxMsgTemplate::getId)
                .last("LIMIT 1"));
    }

    // ═══ 列表 / 详情 ═══

    @Override
    public Map<String, Object> listForCurrentUser(String sceneCode, Integer status,
                                                  Long ownerCorpId, String keyword) {
        Ctx ctx = context();
        LambdaQueryWrapper<OpsWxMsgTemplate> qw = new LambdaQueryWrapper<>();
        if (!ctx.superAdmin()) {
            if (ctx.partner()) {
                // 自建全部 + 上级共享启用 + 平台启用
                qw.and(w -> w.eq(OpsWxMsgTemplate::getOwnerCorpId, ctx.corpId())
                        .or(x -> x.eq(OpsWxMsgTemplate::getOwnerCorpId, ctx.operatorCorp())
                                .eq(OpsWxMsgTemplate::getShareScope,
                                        OpsWxMsgTemplate.SCOPE_OPERATOR_TREE)
                                .eq(OpsWxMsgTemplate::getStatus, 1))
                        .or(x -> x.eq(OpsWxMsgTemplate::getOwnerCorpId, 0L)
                                .eq(OpsWxMsgTemplate::getShareScope,
                                        OpsWxMsgTemplate.SCOPE_PLATFORM)
                                .eq(OpsWxMsgTemplate::getStatus, 1)));
            } else {
                // 运营商：自己 + 下属自建（审计）+ 平台启用
                qw.and(w -> {
                    w.eq(OpsWxMsgTemplate::getOwnerCorpId, ctx.corpId());
                    if (!ctx.children().isEmpty()) {
                        w.or(x -> x.in(OpsWxMsgTemplate::getOwnerCorpId, ctx.children()));
                    }
                    w.or(x -> x.eq(OpsWxMsgTemplate::getOwnerCorpId, 0L)
                            .eq(OpsWxMsgTemplate::getShareScope,
                                    OpsWxMsgTemplate.SCOPE_PLATFORM)
                            .eq(OpsWxMsgTemplate::getStatus, 1));
                });
            }
        }
        if (sceneCode != null && !sceneCode.isBlank()) {
            qw.eq(OpsWxMsgTemplate::getSceneCode, sceneCode.trim());
        }
        if (status != null) qw.eq(OpsWxMsgTemplate::getStatus, status);
        if (ownerCorpId != null) qw.eq(OpsWxMsgTemplate::getOwnerCorpId, ownerCorpId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(OpsWxMsgTemplate::getTitle, kw)
                    .or().like(OpsWxMsgTemplate::getTemplateId, kw));
        }
        qw.orderByDesc(OpsWxMsgTemplate::getId);
        List<OpsWxMsgTemplate> rows = list(qw);
        Map<Long, String> ownerNames = ownerNames(rows);
        List<Map<String, Object>> views = rows.stream()
                .map(t -> toView(t, ctx, ownerNames))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", views);
        result.put("total", views.size());
        result.put("canCreate", ctx.superAdmin()
                || (ctx.corpId() != null && ctx.corpId() > 0));
        result.put("roleType", ctx.roleType());
        return result;
    }

    @Override
    public Map<String, Object> detailForCurrentUser(Long id) {
        Ctx ctx = context();
        OpsWxMsgTemplate template = requireVisible(id, ctx);
        return toView(template, ctx, ownerNames(List.of(template)));
    }

    // ═══ 新增 / 修改 / 启停 / 删除 ═══

    @Override
    @Transactional
    public OpsWxMsgTemplate createForCurrentUser(OpsWxMsgTemplate template) {
        Ctx ctx = context();
        if (template == null) throw BusinessException.of("模板不能为空");
        if (!ctx.superAdmin() && (ctx.corpId() == null || ctx.corpId() <= 0)) {
            throw BusinessException.forbidden("当前组织不可维护模板");
        }
        normalizeOwnership(template, ctx, true);
        normalizeNullableText(template);
        template.setCorpId(template.getOwnerCorpId()); // 旧 JAR 兼容镜像
        template.setId(null);
        template.setUseCount(0);
        template.setLastUsedTime(null);
        if (template.getStatus() == null) template.setStatus(1);
        validate(template);
        save(template);
        disableOtherEnabled(template);
        return getById(template.getId());
    }

    @Override
    @Transactional
    public OpsWxMsgTemplate updateForCurrentUser(Long id, OpsWxMsgTemplate template) {
        Ctx ctx = context();
        OpsWxMsgTemplate existing = requireEditable(id, ctx);
        if (template == null) throw BusinessException.of("模板不能为空");
        if (template.getOwnerCorpId() == null) {
            template.setOwnerCorpId(existing.getOwnerCorpId());
        }
        normalizeOwnership(template, ctx, false);
        normalizeNullableText(template);
        template.setId(id);
        if (!ctx.superAdmin()) {
            template.setOwnerCorpId(existing.getOwnerCorpId());
        }
        template.setUseCount(existing.getUseCount());
        template.setLastUsedTime(existing.getLastUsedTime());
        if (template.getStatus() == null) template.setStatus(existing.getStatus());
        validate(template);
        updateById(template);
        disableOtherEnabled(template);
        return getById(id);
    }

    @Override
    @Transactional
    public void changeStatusForCurrentUser(Long id, Integer status) {
        Ctx ctx = context();
        OpsWxMsgTemplate existing = requireEditable(id, ctx);
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.of("状态值必须为0或1");
        }
        existing.setStatus(status);
        updateById(existing);
        if (status == 1) disableOtherEnabled(existing);
    }

    @Override
    @Transactional
    public void deleteForCurrentUser(Long id) {
        Ctx ctx = context();
        OpsWxMsgTemplate existing = requireEditable(id, ctx);
        if (Integer.valueOf(1).equals(existing.getStatus())) {
            throw BusinessException.of("请先停用模板再删除");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        String userName = SecurityUtils.getCurrentUser().getUsername();
        update(null, new LambdaUpdateWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getId, id)
                .set(OpsWxMsgTemplate::getDeleteFlag, 1)
                .set(OpsWxMsgTemplate::getModifyTime, LocalDateTime.now())
                .set(OpsWxMsgTemplate::getModifyUser, userId)
                .set(OpsWxMsgTemplate::getModifyUserName, userName));
    }

    // ═══ 可选模板 / 测试发送 / 使用统计 ═══

    @Override
    public List<Map<String, Object>> availableForCurrentUser(String sceneCode) {
        Ctx ctx = context();
        if (ctx.corpId() == null || ctx.corpId() <= 0) return List.of();
        LinkedHashMap<Long, OpsWxMsgTemplate> merged = new LinkedHashMap<>();
        list(enabledQuery(ctx.corpId(), null, sceneCode))
                .forEach(t -> merged.put(t.getId(), t));
        if (ctx.partner()) {
            list(enabledQuery(ctx.operatorCorp(),
                    OpsWxMsgTemplate.SCOPE_OPERATOR_TREE, sceneCode))
                    .forEach(t -> merged.putIfAbsent(t.getId(), t));
        }
        list(enabledQuery(0L, OpsWxMsgTemplate.SCOPE_PLATFORM, sceneCode))
                .forEach(t -> merged.putIfAbsent(t.getId(), t));
        List<OpsWxMsgTemplate> rows = new ArrayList<>(merged.values());
        Map<Long, String> ownerNames = ownerNames(rows);
        return rows.stream().map(t -> toView(t, ctx, ownerNames)).toList();
    }

    private LambdaQueryWrapper<OpsWxMsgTemplate> enabledQuery(Long ownerCorpId,
                                                              String scope, String sceneCode) {
        return new LambdaQueryWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getOwnerCorpId, ownerCorpId)
                .eq(scope != null, OpsWxMsgTemplate::getShareScope, scope)
                .eq(sceneCode != null && !sceneCode.isBlank(),
                        OpsWxMsgTemplate::getSceneCode, sceneCode)
                .eq(OpsWxMsgTemplate::getStatus, 1)
                .orderByDesc(OpsWxMsgTemplate::getModifyTime)
                .orderByDesc(OpsWxMsgTemplate::getId);
    }

    @Override
    public Map<String, Object> testSendForCurrentUser(Long id) {
        Ctx ctx = context();
        OpsWxMsgTemplate template = requireVisible(id, ctx);
        Map<String, String> vars = sampleVariables();
        WxTemplateMessageSupport.SendResult result = messageSupport.send(
                template, ctx.corpId(), SecurityUtils.getCurrentUserId(),
                vars, dispatchProperties.getTestUrl());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("success", result.success());
        view.put("error", result.error());
        view.put("templateId", result.templateId());
        view.put("appId", result.appId());
        return view;
    }

    @Override
    public void recordUsage(Long templateId) {
        if (templateId == null) return;
        update(null, new LambdaUpdateWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getId, templateId)
                .setSql("use_count = COALESCE(use_count, 0) + 1")
                .set(OpsWxMsgTemplate::getLastUsedTime, LocalDateTime.now()));
    }

    // ═══ 权限 / 归属规范化 ═══

    private Ctx context() {
        // 后台管理接口不接受会员 SSO 令牌（会员 sub 与 sys_user.id 可能撞号）
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.forbidden("仅员工/管理员账号可维护公众号消息模板");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) {
            throw BusinessException.forbidden("登录已过期，请重新登录");
        }
        boolean superAdmin = SecurityUtils.isSuperAdmin();
        String roleType = orgRoleService.getOrgRoleType();
        boolean partner = "partner".equals(roleType);
        Long operatorCorp = orgRoleService.getOperatorCorpId(corpId);
        Set<Long> children = Set.of();
        if (!superAdmin && !partner) {
            Set<Long> visible = dataScopeService.getVisibleCorpIdsForCorp(corpId);
            if (visible != null) {
                children = new HashSet<>(visible);
                children.remove(corpId);
            }
        }
        return new Ctx(corpId, superAdmin, roleType, partner, operatorCorp, children);
    }

    /** 归属组织与共享范围按角色强制，不信任前端。 */
    private void normalizeOwnership(OpsWxMsgTemplate template, Ctx ctx, boolean creating) {
        if (!ctx.superAdmin()) {
            template.setOwnerCorpId(ctx.corpId());
            if (ctx.partner()) {
                template.setShareScope(OpsWxMsgTemplate.SCOPE_PRIVATE);
            } else if (!OpsWxMsgTemplate.SCOPE_OPERATOR_TREE.equals(template.getShareScope())) {
                template.setShareScope(OpsWxMsgTemplate.SCOPE_PRIVATE);
            }
            return;
        }
        if (template.getOwnerCorpId() == null) {
            template.setOwnerCorpId(creating ? 0L : template.getOwnerCorpId());
        }
        if (template.getOwnerCorpId() == null) {
            throw BusinessException.of("请指定归属组织");
        }
        if (template.getOwnerCorpId() == 0L) {
            template.setShareScope(OpsWxMsgTemplate.SCOPE_PLATFORM);
            return;
        }
        if (template.getShareScope() == null || template.getShareScope().isBlank()) {
            template.setShareScope(OpsWxMsgTemplate.SCOPE_PRIVATE);
        }
        if (OpsWxMsgTemplate.SCOPE_OPERATOR_TREE.equals(template.getShareScope())
                && !orgRoleService.isOperatorCorp(template.getOwnerCorpId())) {
            throw BusinessException.of("共享模板只能归属运营商组织（城市合伙人请选择仅本组织）");
        }
    }

    /** 可空文本统一为去空格字符串：updateById 才能把历史值真正清空（null 会被忽略）。 */
    private static void normalizeNullableText(OpsWxMsgTemplate template) {
        template.setAppId(template.getAppId() == null ? "" : template.getAppId().trim());
        template.setTemplateIdMap(template.getTemplateIdMap() == null
                ? "" : template.getTemplateIdMap().trim());
    }

    private OpsWxMsgTemplate requireVisible(Long id, Ctx ctx) {        OpsWxMsgTemplate template = getById(id);
        if (template == null) throw BusinessException.notFound("模板不存在");
        if (ctx.superAdmin()) return template;
        if (Objects.equals(template.getOwnerCorpId(), ctx.corpId())) return template;
        if (ctx.partner()
                && Objects.equals(template.getOwnerCorpId(), ctx.operatorCorp())
                && OpsWxMsgTemplate.SCOPE_OPERATOR_TREE.equals(template.getShareScope())
                && Integer.valueOf(1).equals(template.getStatus())) {
            return template;
        }
        if (!ctx.partner() && ctx.children().contains(template.getOwnerCorpId())) {
            return template;
        }
        if (Objects.equals(template.getOwnerCorpId(), 0L)
                && OpsWxMsgTemplate.SCOPE_PLATFORM.equals(template.getShareScope())
                && Integer.valueOf(1).equals(template.getStatus())) {
            return template;
        }
        throw BusinessException.forbidden("无权查看该模板");
    }

    private OpsWxMsgTemplate requireEditable(Long id, Ctx ctx) {
        OpsWxMsgTemplate template = getById(id);
        if (template == null) throw BusinessException.notFound("模板不存在");
        if (!canEdit(template, ctx)) {
            throw BusinessException.forbidden("无权修改该模板（运营商共享模板对子公司只读）");
        }
        return template;
    }

    private static boolean canEdit(OpsWxMsgTemplate template, Ctx ctx) {
        return ctx.superAdmin()
                || Objects.equals(template.getOwnerCorpId(), ctx.corpId());
    }

    // ═══ 视图 / 辅助 ═══

    private Map<String, Object> toView(OpsWxMsgTemplate t, Ctx ctx,
                                       Map<Long, String> ownerNames) {
        String sourceType = sourceType(t, ctx);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("ownerCorpId", t.getOwnerCorpId());
        m.put("ownerCorpName", ownerNames.getOrDefault(t.getOwnerCorpId(),
                t.getOwnerCorpId() == null ? "未知" : "组织#" + t.getOwnerCorpId()));
        m.put("shareScope", t.getShareScope());
        m.put("shareScopeLabel", shareScopeLabel(t.getShareScope()));
        m.put("sourceType", sourceType);
        m.put("sourceLabel", sourceLabel(sourceType));
        m.put("sceneCode", t.getSceneCode());
        m.put("templateId", t.getTemplateId());
        m.put("templateIdMap", t.getTemplateIdMap());
        m.put("title", t.getTitle());
        m.put("content", t.getContent());
        m.put("variableDesc", t.getVariableDesc());
        m.put("fieldMapping", t.getFieldMapping());
        m.put("appId", t.getAppId());
        m.put("status", t.getStatus());
        m.put("useCount", t.getUseCount());
        m.put("lastUsedTime", t.getLastUsedTime());
        m.put("createTime", t.getCreateTime());
        m.put("modifyTime", t.getModifyTime());
        boolean editable = canEdit(t, ctx);
        m.put("canEdit", editable);
        m.put("canDelete", editable && !Integer.valueOf(1).equals(t.getStatus()));
        m.put("canTest", true);
        return m;
    }

    private static String sourceType(OpsWxMsgTemplate t, Ctx ctx) {
        Long owner = t.getOwnerCorpId();
        if (Objects.equals(owner, 0L)) return "PLATFORM";
        if (ctx.superAdmin()) return "ORG";
        if (Objects.equals(owner, ctx.corpId())) return "SELF";
        if (ctx.partner()
                && Objects.equals(owner, ctx.operatorCorp())
                && OpsWxMsgTemplate.SCOPE_OPERATOR_TREE.equals(t.getShareScope())) {
            return "SHARED";
        }
        if (!ctx.partner() && ctx.children().contains(owner)) return "SUBORDINATE";
        return "OTHER";
    }

    private static String sourceLabel(String sourceType) {
        return switch (sourceType) {
            case "SELF" -> "自建";
            case "SHARED" -> "运营商共享";
            case "PLATFORM" -> "平台";
            case "SUBORDINATE" -> "子公司自建";
            case "ORG" -> "组织自建";
            default -> "其他";
        };
    }

    private static String shareScopeLabel(String scope) {
        if (OpsWxMsgTemplate.SCOPE_OPERATOR_TREE.equals(scope)) return "运营商及下属共享";
        if (OpsWxMsgTemplate.SCOPE_PLATFORM.equals(scope)) return "平台全局";
        return "仅本组织可见";
    }

    private Map<Long, String> ownerNames(List<OpsWxMsgTemplate> rows) {
        Set<Long> ids = rows.stream()
                .map(OpsWxMsgTemplate::getOwnerCorpId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> names = new HashMap<>();
        names.put(0L, "平台");
        if (ids.isEmpty()) return names;
        try {
            String placeholders = ids.stream().map(x -> "?")
                    .collect(Collectors.joining(","));
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                    "SELECT id, COALESCE(NULLIF(short_name,''), name) AS name "
                            + "FROM ai_platform.sys_corp WHERE id IN (" + placeholders + ")",
                    ids.toArray());
            for (Map<String, Object> row : list) {
                Object id = row.get("id");
                if (id instanceof Number n) {
                    names.put(n.longValue(), String.valueOf(row.get("name")));
                }
            }
        } catch (Exception e) {
            log.warn("加载模板归属组织名称失败: {}", e.getMessage());
        }
        return names;
    }

    private void disableOtherEnabled(OpsWxMsgTemplate template) {
        if (template == null || !Integer.valueOf(1).equals(template.getStatus())
                || template.getOwnerCorpId() == null || template.getSceneCode() == null) {
            return;
        }
        Long userId;
        String userName;
        try {
            userId = SecurityUtils.getCurrentUserId();
            userName = SecurityUtils.getCurrentUser().getUsername();
        } catch (Exception e) {
            userId = null;
            userName = null;
        }
        LambdaUpdateWrapper<OpsWxMsgTemplate> update = new LambdaUpdateWrapper<OpsWxMsgTemplate>()
                .eq(OpsWxMsgTemplate::getOwnerCorpId, template.getOwnerCorpId())
                .eq(OpsWxMsgTemplate::getSceneCode, template.getSceneCode())
                .ne(OpsWxMsgTemplate::getId, template.getId())
                .eq(OpsWxMsgTemplate::getStatus, 1)
                .set(OpsWxMsgTemplate::getStatus, 0)
                .set(OpsWxMsgTemplate::getModifyTime, LocalDateTime.now());
        if (userId != null) update.set(OpsWxMsgTemplate::getModifyUser, userId);
        if (userName != null) update.set(OpsWxMsgTemplate::getModifyUserName, userName);
        update(null, update);
    }

    private Map<String, String> sampleVariables() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        String userName;
        try {
            userName = SecurityUtils.getCurrentUser().getUsername();
        } catch (Exception e) {
            userName = "测试员工";
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("customerName", "测试客户");
        vars.put("customerPhone", "13800000000");
        vars.put("communityName", "测试小区");
        vars.put("detailedAddress", "1栋1单元101");
        vars.put("dispatchType", "施工");
        vars.put("expectTime", now.plusDays(1).format(TIME_FMT));
        vars.put("dispatchTime", now.format(TIME_FMT));
        vars.put("dispatchNo", "TEST-PG-00000000-001");
        vars.put("assigneeName", userName);
        vars.put("remark", "这是一条测试消息");
        return vars;
    }

    // ═══ 校验 ═══

    @Override
    public void validate(OpsWxMsgTemplate template) {
        if (template.getSceneCode() == null || template.getSceneCode().isBlank()) {
            throw BusinessException.of("请选择业务场景");
        }
        if (template.getTemplateId() == null || template.getTemplateId().isBlank()) {
            throw BusinessException.of("请填写微信公众号模板ID");
        }
        if (template.getTemplateId().trim().length() > 128) {
            throw BusinessException.of("模板ID不能超过128个字符");
        }
        if (template.getTitle() != null && template.getTitle().length() > 100) {
            throw BusinessException.of("模板标题不能超过100个字符");
        }
        if (template.getContent() != null && template.getContent().length() > 2000) {
            throw BusinessException.of("模板内容不能超过2000个字符");
        }
        if (template.getVariableDesc() != null && template.getVariableDesc().length() > 1000) {
            throw BusinessException.of("变量说明不能超过1000个字符");
        }
        if (template.getAppId() != null && template.getAppId().length() > 64) {
            throw BusinessException.of("公众号 appId 不能超过64个字符");
        }
        if (template.getTemplateIdMap() != null && template.getTemplateIdMap().length() > 1000) {
            throw BusinessException.of("按公众号映射模板ID不能超过1000个字符");
        }
        if (template.getFieldMapping().length() > 1000) {
            throw BusinessException.of("字段映射不能超过1000个字符");
        }
        String scope = template.getShareScope();
        if (scope == null || !SCOPES.contains(scope)) {
            throw BusinessException.of("共享范围不合法");
        }
        Long owner = template.getOwnerCorpId();
        if (owner == null) throw BusinessException.of("归属组织不能为空");
        if (OpsWxMsgTemplate.SCOPE_PLATFORM.equals(scope) && owner != 0L) {
            throw BusinessException.of("平台全局模板的归属组织必须为0");
        }
        if (!OpsWxMsgTemplate.SCOPE_PLATFORM.equals(scope) && owner <= 0L) {
            throw BusinessException.of("请指定模板归属组织");
        }
        if (template.getFieldMapping() == null || template.getFieldMapping().isBlank()) {
            throw BusinessException.of("请配置变量字段映射（JSON）");
        }
        try {
            JsonNode node = objectMapper.readTree(template.getFieldMapping());
            if (!node.isObject() || node.isEmpty()) {
                throw BusinessException.of("变量字段映射必须是 JSON 对象");
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            Set<String> wxFields = new HashSet<>();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                    throw BusinessException.of("变量字段映射的值必须是非空字符串：" + entry.getKey());
                }
                if (!wxFields.add(entry.getValue().asText())) {
                    throw BusinessException.of("微信模板字段重复映射：" + entry.getValue().asText());
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("变量字段映射不是合法的 JSON");
        }
        validateTemplateIdMap(template.getTemplateIdMap());
    }

    private void validateTemplateIdMap(String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw BusinessException.of("按公众号映射模板ID必须是 JSON 对象");
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (entry.getKey().isBlank() || !entry.getValue().isTextual()
                        || entry.getValue().asText().isBlank()) {
                    throw BusinessException.of("按公众号映射的 appId/template_id 不能为空");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("按公众号映射模板ID不是合法的 JSON");
        }
    }

    private record Ctx(Long corpId, boolean superAdmin, String roleType,
                       boolean partner, Long operatorCorp, Set<Long> children) {
    }
}
