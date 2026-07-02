package com.aip.ops.mapper;

import com.aip.ops.entity.AiChatTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 对话异步任务 Mapper
 */
@Mapper
public interface AiChatTaskMapper extends BaseMapper<AiChatTask> {
}
