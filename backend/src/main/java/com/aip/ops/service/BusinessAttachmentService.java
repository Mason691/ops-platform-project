package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessAttachment;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务订单附件：上传留痕、归属校验、订单绑定与 OCR 文件定位。
 */
public interface BusinessAttachmentService {

    /** 员工上传附件；按 fileType 校验格式/大小，图片服务端压缩后落盘。 */
    OpsBusinessAttachment upload(MultipartFile file, String fileType);

    /** 提交制单时校验附件归属与数量；返回按入参顺序排列的附件。 */
    List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId);

    /** 校验单个附件（OCR 收据图片使用）。 */
    OpsBusinessAttachment requireOwnedOne(Long id, String fileType, Long corpId);

    /** 提交成功后把附件绑定到订单。 */
    void bindToOrder(List<Long> ids, Long orderId);

    /** URL 列表 → 逗号分隔字符串（订单头落库）。 */
    String toUrlCsv(List<OpsBusinessAttachment> files);

    /** 把受控 URL 解析为上传根目录下的本地文件（OCR 用，防目录穿越）。 */
    Path resolveLocalPath(OpsBusinessAttachment file);

    /** 员工在表单中移除附件：仅允许删除本人、未绑定订单的附件（文件+记录）。 */
    void deleteUnbound(Long id, Long corpId);

    /** 定时清理：删除 cutoff 之前未绑定订单的附件（文件+记录），返回清理数量。 */
    int purgeUnboundBefore(LocalDateTime cutoff, int limit);
}
