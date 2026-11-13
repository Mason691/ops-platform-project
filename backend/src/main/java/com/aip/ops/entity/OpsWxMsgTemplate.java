package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公众号消息模板配置。
 *
 * <p>模板 ID 来自微信公众号后台；{@link #fieldMapping} 描述逻辑变量到微信模板字段
 * 的映射（微信模板消息要求 data 的 key 与后台模板字段一致），例如：
 * {@code {"customerName":"thing1","dispatchType":"thing2","expectTime":"time3","dispatchNo":"character_string4"}}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_wx_msg_template")
public class OpsWxMsgTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0=平台全局；>0=组织覆盖。 */
    @TableField("corp_id")
    private Long corpId;

    /** 业务场景：DISPATCH_NOTICE（派工通知）/ DISPATCH_CANCELLED（取消通知）。 */
    @TableField("scene_code")
    private String sceneCode;

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

    /** 指定公众号 appId；为空时使用派工单组织的默认公众号。 */
    @TableField("app_id")
    private String appId;

    /** 1=启用 0=禁用；同场景同组织仅取一条启用模板。 */
    @TableField("status")
    private Integer status;
}
