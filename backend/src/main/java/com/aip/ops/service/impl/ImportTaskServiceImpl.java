package com.aip.ops.service.impl;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.SysImportTaskMapper;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ImportTaskServiceImpl implements ImportTaskService {

    private final SysImportTaskMapper mapper;

    public ImportTaskServiceImpl(SysImportTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public SysImportTask createTask(String taskType, String fileName, String filePath, Long fileSize) {
        SysImportTask task = new SysImportTask();
        task.setTaskType(taskType);
        task.setFileName(fileName);
        task.setFilePath(filePath);
        task.setFileSize(fileSize != null ? fileSize : 0L);
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setModifyTime(LocalDateTime.now());

        try {
            task.setCorpId(SecurityUtils.getCurrentCorpId());
            task.setUserId(SecurityUtils.getCurrentUserId());
            task.setUserName(SecurityUtils.getCurrentUser() != null
                    ? SecurityUtils.getCurrentUser().getUsername() : "");
        } catch (Exception ignored) {
            task.setCorpId(0L);
        }

        mapper.insert(task);
        log.info("Import task created: id={} type={} file={}", task.getId(), taskType, fileName);
        return task;
    }

    @Override
    @Transactional
    public SysImportTask createTaskWithoutFile(String taskType, String description) {
        SysImportTask task = new SysImportTask();
        task.setTaskType(taskType);
        task.setFileName(description);
        task.setFilePath("");  // no file for external DB imports
        task.setFileSize(0L);
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setModifyTime(LocalDateTime.now());

        try {
            task.setCorpId(SecurityUtils.getCurrentCorpId());
            task.setUserId(SecurityUtils.getCurrentUserId());
            task.setUserName(SecurityUtils.getCurrentUser() != null
                    ? SecurityUtils.getCurrentUser().getUsername() : "");
        } catch (Exception ignored) {
            task.setCorpId(0L);
        }

        mapper.insert(task);
        log.info("Import task created (no file): id={} type={} desc={}", task.getId(), taskType, description);
        return task;
    }

    @Override
    public SysImportTask getById(Long taskId) {
        return mapper.selectById(taskId);
    }

    @Override
    @Transactional
    public void updateStatus(Long taskId, String status, String phase, String progressMsg) {
        mapper.update(null, Wrappers.<SysImportTask>lambdaUpdate()
                .eq(SysImportTask::getId, taskId)
                .set(SysImportTask::getStatus, status)
                .set(SysImportTask::getPhase, phase)
                .set(SysImportTask::getProgressMsg, progressMsg)
                .set(SysImportTask::getModifyTime, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void markDone(Long taskId, int totalRows, int successRows) {
        mapper.update(null, Wrappers.<SysImportTask>lambdaUpdate()
                .eq(SysImportTask::getId, taskId)
                .set(SysImportTask::getStatus, "DONE")
                .set(SysImportTask::getPhase, "INSERT")
                .set(SysImportTask::getTotalRows, totalRows)
                .set(SysImportTask::getSuccessRows, successRows)
                .set(SysImportTask::getFinishTime, LocalDateTime.now())
                .set(SysImportTask::getModifyTime, LocalDateTime.now()));
        log.info("Import task done: id={} rows={}", taskId, successRows);
    }

    @Override
    @Transactional
    public void markDone(Long taskId, int totalRows, int successRows, String progressMsg) {
        mapper.update(null, Wrappers.<SysImportTask>lambdaUpdate()
                .eq(SysImportTask::getId, taskId)
                .set(SysImportTask::getStatus, "DONE")
                .set(SysImportTask::getPhase, "INSERT")
                .set(SysImportTask::getTotalRows, totalRows)
                .set(SysImportTask::getSuccessRows, successRows)
                .set(SysImportTask::getProgressMsg, progressMsg)
                .set(SysImportTask::getFinishTime, LocalDateTime.now())
                .set(SysImportTask::getModifyTime, LocalDateTime.now()));
        log.info("Import task done: id={} rows={} msg={}", taskId, successRows, progressMsg);
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String phase, String errorMsg, int totalRows, int failRows) {
        String truncated = errorMsg != null && errorMsg.length() > 1000
                ? errorMsg.substring(0, 1000) : errorMsg;
        mapper.update(null, Wrappers.<SysImportTask>lambdaUpdate()
                .eq(SysImportTask::getId, taskId)
                .set(SysImportTask::getStatus, "FAILED")
                .set(SysImportTask::getPhase, phase)
                .set(SysImportTask::getErrorMsg, truncated)
                .set(SysImportTask::getTotalRows, totalRows)
                .set(SysImportTask::getFailRows, failRows)
                .set(SysImportTask::getFinishTime, LocalDateTime.now())
                .set(SysImportTask::getModifyTime, LocalDateTime.now()));
        log.warn("Import task failed: id={} phase={} error={}", taskId, phase, truncated);
    }

    @Override
    public boolean hasPendingTask(String taskType) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<SysImportTask>()
                .eq(SysImportTask::getTaskType, taskType)
                .in(SysImportTask::getStatus, "PENDING", "PROCESSING"));
        return count != null && count > 0;
    }

    @Override
    public List<SysImportTask> listHistory(Long userId, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<SysImportTask>()
                .eq(userId != null, SysImportTask::getUserId, userId)
                .orderByDesc(SysImportTask::getCreateTime)
                .last("LIMIT " + Math.min(limit, 50)));
    }
}
