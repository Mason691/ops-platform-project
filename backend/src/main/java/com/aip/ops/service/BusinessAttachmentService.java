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

    /** 员工上传附件；收据原图保存、其余图片压缩，按 fileType 校验格式/大小。 */
    OpsBusinessAttachment upload(MultipartFile file, String fileType);

    /**
     * 指定上传人上传（PC 运营端验收凭证使用）：不依赖员工 JWT，
     * 但必须由已认证的内部用户传入 corpId/userId，仍做组织与配额校验。
     */
    OpsBusinessAttachment uploadForUser(MultipartFile file, String fileType, Long corpId,
                                        Long userId, String userName);

    /** 提交制单时校验附件归属与数量；返回按入参顺序排列的附件。 */
    List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId);

    /** 同上；allowOrderId 非空时允许引用已绑定到该订单的附件（草稿编辑复用）。 */
    List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId,
                                                 Long allowOrderId);

    /**
     * 工作流内部回调使用：按显式上传人校验附件归属（回调线程无 SecurityContext）。
     * operatorUserId 来自平台已认证任务处理人，调用方必须先完成任务归属校验。
     */
    List<OpsBusinessAttachment> requireOwnedListForUploader(List<Long> ids, String fileType,
                                                            Long corpId, Long allowOrderId,
                                                            Long uploaderUserId);

    /** 校验单个附件（OCR 收据图片使用）。 */
    OpsBusinessAttachment requireOwnedOne(Long id, String fileType, Long corpId);

    /** 提交成功后把附件绑定到订单；按组织过滤，防止跨租户绑定。 */
    void bindToOrder(List<Long> ids, Long orderId, Long corpId);

    /** 订单已绑定的全部附件（编辑草稿/详情展示用）。 */
    List<OpsBusinessAttachment> listByOrder(Long orderId);

    /** 解除指定附件与订单的绑定（草稿编辑替换凭证时使用，文件由定时任务清理）。 */
    void unbindFromOrder(Long orderId, List<Long> ids);

    /** URL 列表 → 逗号分隔字符串（订单头落库）。 */
    String toUrlCsv(List<OpsBusinessAttachment> files);

    /** 把受控 URL 解析为上传根目录下的本地文件（OCR 用，防目录穿越）。 */
    Path resolveLocalPath(OpsBusinessAttachment file);

    /** 员工在表单中移除附件：仅允许删除本人、未绑定订单的附件（文件+记录）。 */
    void deleteUnbound(Long id, Long corpId);

    /** 指定用户删除未绑定附件（PC 验收凭证使用，无需员工 JWT）。 */
    void deleteUnboundForUser(Long id, Long corpId, Long userId);

    /** 定时清理：删除 cutoff 之前未绑定订单的附件（文件+记录），返回清理数量。 */
    int purgeUnboundBefore(LocalDateTime cutoff, int limit);
}
