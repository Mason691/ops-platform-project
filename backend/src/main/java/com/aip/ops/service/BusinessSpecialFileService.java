package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessSpecialFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 低价特批文件上传与归属校验。
 */
public interface BusinessSpecialFileService {

    /** 员工上传特批文件（PDF/JPG/PNG，≤10MB），落库上传人/时间/原文件名。 */
    OpsBusinessSpecialFile upload(MultipartFile file);

    /** 提交制单时校验文件存在且属于当前员工组织。 */
    OpsBusinessSpecialFile requireOwned(Long id, Long corpId);
}
