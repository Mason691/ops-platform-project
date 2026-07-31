package com.aip.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 操作日志写入服务 — 按月写入 JSON-Lines 文件，与主平台格式兼容。
 * 日志目录：/home/jason/data/logs/operation/operation_YYYYMM.log
 */
@Slf4j
@Service
public class OperationLogService {

    private static final String LOG_DIR = "/home/jason/data/logs/operation";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ReentrantLock> monthLocks = new ConcurrentHashMap<>();

    @Async
    public void asyncWrite(Object entry) {
        try {
            String month = LocalDate.now().format(MONTH_FMT);
            Path dir = Path.of(LOG_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve("operation_" + month + ".log");

            ReentrantLock lock = monthLocks.computeIfAbsent(month, k -> new ReentrantLock());
            lock.lock();
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(objectMapper.writeValueAsString(entry));
                w.newLine();
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            log.warn("Failed to write operation log: {}", e.getMessage());
        }
    }
}
