package com.aip.ops.service;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.DispatchWorkerDtos;
import org.springframework.stereotype.Service;

/**
 * 现场新增外部施工员的成功审计。
 *
 * <p>把审计动作放在独立 Bean 上，确保 {@link OperationLog} AOP 生效；
 * 事件 DTO 只含脱敏手机号与创建结果，避免明文手机号落 JSON-Lines 日志。</p>
 */
@Service
public class DispatchWorkerAuditService {

    @OperationLog(module = "增值业务", action = "新增外部施工员", targetTable = "sys_user")
    public void record(DispatchWorkerDtos.ExternalWorkerAuditEvent event) {
        // 仅借助注解切面写审计，方法本身无业务逻辑。
    }
}
