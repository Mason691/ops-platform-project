package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsExportTask;
import com.aip.ops.mapper.OpsExportTaskMapper;
import com.aip.ops.service.OpsExportTaskService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class OpsExportTaskServiceImpl extends ServiceImpl<OpsExportTaskMapper, OpsExportTask>
        implements OpsExportTaskService {

    @Override
    @Transactional
    public OpsExportTask createTask(Long corpId, Long userId, String userName,
                                     String filters, String fileName) {
        OpsExportTask task = new OpsExportTask();
        task.setCorpId(corpId != null ? corpId : 0L);
        task.setUserId(userId);
        task.setUserName(userName);
        task.setFilters(filters);
        task.setFileName(fileName);
        task.setStatus("PENDING");
        task.setUniqueId(UUID.randomUUID().toString().replace("-", ""));
        task.setCreateTime(LocalDateTime.now());
        task.setModifyTime(LocalDateTime.now());
        baseMapper.insert(task);
        log.info("Export task created: id={}, fileName={}", task.getId(), fileName);
        return task;
    }

    @Override
    @Transactional
    public void updateStatus(Long taskId, String status, String progressMsg) {
        update(null, Wrappers.<OpsExportTask>lambdaUpdate()
                .eq(OpsExportTask::getId, taskId)
                .set(OpsExportTask::getStatus, status)
                .set(OpsExportTask::getProgressMsg, progressMsg)
                .set(OpsExportTask::getModifyTime, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void markDone(Long taskId, String filePath, Long fileSize) {
        update(null, Wrappers.<OpsExportTask>lambdaUpdate()
                .eq(OpsExportTask::getId, taskId)
                .set(OpsExportTask::getStatus, "DONE")
                .set(OpsExportTask::getFilePath, filePath)
                .set(OpsExportTask::getFileSize, fileSize)
                .set(OpsExportTask::getFinishTime, LocalDateTime.now())
                .set(OpsExportTask::getModifyTime, LocalDateTime.now()));
        log.info("Export task done: id={}, path={}, size={}", taskId, filePath, fileSize);
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String errorMsg) {
        update(null, Wrappers.<OpsExportTask>lambdaUpdate()
                .eq(OpsExportTask::getId, taskId)
                .set(OpsExportTask::getStatus, "FAILED")
                .set(OpsExportTask::getErrorMsg, errorMsg)
                .set(OpsExportTask::getFinishTime, LocalDateTime.now())
                .set(OpsExportTask::getModifyTime, LocalDateTime.now()));
        log.warn("Export task failed: id={}, error={}", taskId, errorMsg);
    }
}
