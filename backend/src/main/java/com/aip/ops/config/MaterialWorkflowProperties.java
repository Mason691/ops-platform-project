package com.aip.ops.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 物资领用/核销工作流定义配置（定义在底座工作流管理端维护）。 */
@Data
@Component
public class MaterialWorkflowProperties {

    @Value("${material.requisition.definition-code:OPS_MATERIAL_REQUISITION_APPROVAL}")
    private String requisitionDefinitionCode;

    @Value("${material.requisition.business-type:OPS_MATERIAL_REQUISITION}")
    private String requisitionBusinessType;

    @Value("${material.write-off.definition-code:OPS_MATERIAL_WRITE_OFF_APPROVAL}")
    private String writeOffDefinitionCode;

    @Value("${material.write-off.business-type:OPS_MATERIAL_WRITE_OFF}")
    private String writeOffBusinessType;
}
