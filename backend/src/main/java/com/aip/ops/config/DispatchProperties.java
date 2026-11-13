package com.aip.ops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 派工单相关配置。
 *
 * <p>{@code company-code-rules} 用于「公司简称 → 派工单公司代码」的业务口径覆盖
 * （如德保=DB、康乐=KL、广河=GH）；未配置时回退 {@code sys_corp.org_code}，
 * 再回退 {@code C{corpId}}。派工单号格式：{公司代码}-PG-{yyyyMMdd}-{3位流水}。</p>
 *
 * <p>使用对象列表而非 {@code Map<String,String>}：Spring Boot 对非 ASCII Map key
 * 的 relaxed binding 不稳定，中文简称作为普通字段值可可靠绑定。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops.dispatch")
public class DispatchProperties {

    /** 公司简称 → 公司代码（可选覆盖，short-name 对应 sys_corp.short_name）。 */
    private List<CompanyCodeRule> companyCodeRules = new ArrayList<>();

    /** 公众号模板消息点击跳转地址，{id} 会被替换为派工单 ID。 */
    private String workOrderUrl =
            "https://boss.jdzn99.com/ops/#/pages/employee/work-order-detail?id={id}";

    /** 模板「测试发送」的点击跳转地址（员工待处理列表）。 */
    private String testUrl =
            "https://boss.jdzn99.com/ops/#/pages/employee/pending";

    @Data
    public static class CompanyCodeRule {
        private String shortName;
        private String code;
    }
}
