package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公众号消息模板配置。
 *
 * <p>模板为「一套数据、多组织共用」：{@link #ownerCorpId} 标识归属组织，
 * {@link #shareScope} 决定可见范围。运营商创建的 {@code OPERATOR_TREE} 模板
 * 对所有下属城市合伙人动态生效（按 sys_corp.parent_corp_id 关系，不逐组织授权）。</p>
 *
 * <p>{@link #templateIdMap} 支持子公司自有公众号：JSON 形如
 * {@code {"wxSubAppId":"对应公众号下的template_id"}}；发送时按实际使用的
 * 公众号 appId 取对应模板 ID，未配置则明确失败。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_wx_msg_template")
public class OpsWxMsgTemplate extends BaseEntity {

    public static final String SCOPE_PRIVATE = "PRIVATE";
    public static final String SCOPE_OPERATOR_TREE = "OPERATOR_TREE";
    public static final String SCOPE_PLATFORM = "PLATFORM";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属组织ID；0=平台全局。 */
    @TableField("owner_corp_id")
    private Long ownerCorpId;

    /**
     * 兼容旧 JAR 的归属组织镜像列；新代码以 ownerCorpId 为准。
     * 新模板创建时由 Service 写入；历史行保留原值，便于滚动发布与回滚。
     */
    @JsonIgnore
    @TableField("corp_id")
    private Long corpId;

    /** PRIVATE / OPERATOR_TREE / PLATFORM。 */
    @TableField("share_scope")
    private String shareScope;

    /** 业务场景：DISPATCH_NOTICE / DISPATCH_CANCELLED。 */
    @TableField("scene_code")
    private String sceneCode;

    /** 微信公众号模板ID（默认/主公众号）。 */
    @TableField("template_id")
    private String templateId;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("variable_desc")
    private String variableDesc;

    /** 逻辑变量 -> 微信模板字段 的 JSON 对象。 */
    @TableField("field_mapping")
    private String fieldMapping;

    /** 指定公众号 appId；为空时使用业务组织默认公众号。 */
    @TableField("app_id")
    private String appId;

    /** 按公众号 appId 映射 template_id 的 JSON，如 {"wxSubAppId":"templateId"}。 */
    @TableField("template_id_map")
    private String templateIdMap;

    /** 1=启用 0=禁用；停用后所有组织不可再选用。 */
    @TableField("status")
    private Integer status;

    /** 累计使用次数。 */
    @TableField("use_count")
    private Integer useCount;

    /** 最近使用时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("last_used_time")
    private LocalDateTime lastUsedTime;
}
