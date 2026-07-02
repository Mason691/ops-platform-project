package com.aip.ops.service.impl;

import com.aip.ops.entity.AiChatTask;
import com.aip.ops.mapper.AiChatTaskMapper;
import com.aip.ops.service.AiChatTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AiChatTaskServiceImpl implements AiChatTaskService {

    private final AiChatTaskMapper mapper;

    public AiChatTaskServiceImpl(AiChatTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AiChatTask createTask(String taskId, Long corpId, Long userId, Long memberId,
                                  Long conversationId, String message, String subsystem,
                                  String employeeContext) {
        AiChatTask task = new AiChatTask();
        task.setTaskId(taskId);
        task.setCorpId(corpId);
        task.setUserId(userId);
        task.setMemberId(memberId);
        task.setConversationId(conversationId);
        task.setMessage(message);
        task.setSubsystem(subsystem);
        task.setEmployeeContext(employeeContext);
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setModifyTime(LocalDateTime.now());

        mapper.insert(task);
        log.info("AI chat task created: taskId={} corpId={} userId={}", taskId, corpId, userId);
        return task;
    }

    @Override
    public AiChatTask getByTaskId(String taskId) {
        return mapper.selectOne(new LambdaQueryWrapper<AiChatTask>()
                .eq(AiChatTask::getTaskId, taskId));
    }

    @Override
    @Transactional
    public void updateStatus(String taskId, String status, String phase, String progressMsg) {
        mapper.update(null, Wrappers.<AiChatTask>lambdaUpdate()
                .eq(AiChatTask::getTaskId, taskId)
                .set(AiChatTask::getStatus, status)
                .set(phase != null, AiChatTask::getPhase, phase)
                .set(progressMsg != null, AiChatTask::getProgressMsg, progressMsg)
                .set(AiChatTask::getModifyTime, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void markDone(String taskId, String reply, Long conversationId, boolean partial) {
        mapper.update(null, Wrappers.<AiChatTask>lambdaUpdate()
                .eq(AiChatTask::getTaskId, taskId)
                .set(AiChatTask::getStatus, "COMPLETED")
                .set(AiChatTask::getReply, reply)
                .set(conversationId != null, AiChatTask::getConversationId, conversationId)
                .set(AiChatTask::getPartial, partial ? 1 : 0)
                .set(AiChatTask::getFinishTime, LocalDateTime.now())
                .set(AiChatTask::getModifyTime, LocalDateTime.now()));
        log.info("AI chat task done: taskId={}", taskId);
    }

    @Override
    @Transactional
    public void markFailed(String taskId, String errorMsg) {
        mapper.update(null, Wrappers.<AiChatTask>lambdaUpdate()
                .eq(AiChatTask::getTaskId, taskId)
                .set(AiChatTask::getStatus, "FAILED")
                .set(AiChatTask::getErrorMsg, errorMsg)
                .set(AiChatTask::getFinishTime, LocalDateTime.now())
                .set(AiChatTask::getModifyTime, LocalDateTime.now()));
        log.warn("AI chat task failed: taskId={} error={}", taskId, errorMsg);
    }

    @Override
    @Transactional
    public void updateProgress(String taskId, String phase, String progressMsg) {
        mapper.update(null, Wrappers.<AiChatTask>lambdaUpdate()
                .eq(AiChatTask::getTaskId, taskId)
                .set(phase != null, AiChatTask::getPhase, phase)
                .set(progressMsg != null, AiChatTask::getProgressMsg, progressMsg)
                .set(AiChatTask::getModifyTime, LocalDateTime.now()));
    }
}
