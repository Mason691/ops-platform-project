package com.aip.ops.service.impl;

import com.aip.ops.entity.SysExportTask;
import com.aip.ops.mapper.SysExportTaskMapper;
import com.aip.ops.service.ExportTaskService;
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
public class ExportTaskServiceImpl implements ExportTaskService {

    private final SysExportTaskMapper mapper;

    public ExportTaskServiceImpl(SysExportTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public SysExportTask createTask(String taskType, String filters, String fileName) {
        SysExportTask task = new SysExportTask();
        task.setTaskType(taskType);
        task.setFilters(filters);
        task.setFileName(fileName);
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setModifyTime(LocalDateTime.now());

        // 自动填充审计信息（当前用户 + corp）
        try {
            task.setCorpId(SecurityUtils.getCurrentCorpId());
            task.setUserId(SecurityUtils.getCurrentUserId());
            task.setUserName(SecurityUtils.getCurrentUser() != null
                    ? SecurityUtils.getCurrentUser().getUsername() : "");
        } catch (Exception ignored) {
            task.setCorpId(0L);
        }

        mapper.insert(task);
        log.info("Export task created: id={} type={} fileName={}", task.getId(), taskType, fileName);
        return task;
    }

    @Override
    public SysExportTask getById(Long taskId) {
        return mapper.selectById(taskId);
    }

    @Override
    @Transactional
    public void updateStatus(Long taskId, String status, String progressMsg) {
        mapper.update(null, Wrappers.<SysExportTask>lambdaUpdate()
                .eq(SysExportTask::getId, taskId)
                .set(SysExportTask::getStatus, status)
                .set(SysExportTask::getProgressMsg, progressMsg)
                .set(SysExportTask::getModifyTime, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void markDone(Long taskId, String filePath, Long fileSize) {
        mapper.update(null, Wrappers.<SysExportTask>lambdaUpdate()
                .eq(SysExportTask::getId, taskId)
                .set(SysExportTask::getStatus, "DONE")
                .set(SysExportTask::getFilePath, filePath)
                .set(SysExportTask::getFileSize, fileSize)
                .set(SysExportTask::getFinishTime, LocalDateTime.now())
                .set(SysExportTask::getModifyTime, LocalDateTime.now()));
        log.info("Export task done: id={} path={} size={}", taskId, filePath, fileSize);
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String errorMsg) {
        mapper.update(null, Wrappers.<SysExportTask>lambdaUpdate()
                .eq(SysExportTask::getId, taskId)
                .set(SysExportTask::getStatus, "FAILED")
                .set(SysExportTask::getErrorMsg, errorMsg)
                .set(SysExportTask::getFinishTime, LocalDateTime.now())
                .set(SysExportTask::getModifyTime, LocalDateTime.now()));
        log.warn("Export task failed: id={} error={}", taskId, errorMsg);
    }

    @Override
    public List<SysExportTask> listHistory(Long userId, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<SysExportTask>()
                .eq(userId != null, SysExportTask::getUserId, userId)
                .orderByDesc(SysExportTask::getCreateTime)
                .last("LIMIT " + Math.min(limit, 50)));
    }
}
