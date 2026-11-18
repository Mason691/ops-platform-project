package com.aip.ops.scheduler;

import com.aip.ops.service.BusinessAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 未绑定业务附件清理。
 *
 * <p>员工上传后放弃制单、删除接口失败或上传中断时，会残留文件与
 * `ops_business_attachment` 记录（order_id 为空）。每日清理 N 天前的
 * 未绑定附件，避免占满与导入/导出共享的上传卷。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessAttachmentCleanupScheduler {

    private final BusinessAttachmentService attachmentService;

    @Value("${business-attachment.cleanup.days:7}")
    private int retentionDays;

    @Value("${business-attachment.cleanup.limit:500}")
    private int batchLimit;

    @Scheduled(cron = "${business-attachment.cleanup.cron:0 30 4 * * *}")
    public void purge() {
        try {
            int days = Math.max(retentionDays, 1);
            int count = attachmentService.purgeUnboundBefore(
                    LocalDateTime.now().minusDays(days), batchLimit);
            if (count > 0) {
                log.info("清理未绑定业务附件 {} 件（保留 {} 天）", count, days);
            }
        } catch (Exception e) {
            log.warn("清理未绑定业务附件失败：{}", e.getMessage());
        }
    }
}
