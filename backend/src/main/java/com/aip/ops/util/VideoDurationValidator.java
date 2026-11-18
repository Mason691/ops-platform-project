package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 短视频时长校验。
 *
 * <p>验收视频 ≤30 秒是硬约束，前端 {@code chooseMedia.maxDuration} 在部分机型会被绕过，
 * 因此服务端必须二次校验。优先用纯 Java 读取 ISO BMFF（MP4/MOV）的 moov/mvhd，
 * 不依赖部署镜像安装 ffmpeg；解析失败时再尝试 ffprobe，二者都失败则拒绝上传。</p>
 */
@Slf4j
@Component
public class VideoDurationValidator {

    private static final int MAX_BOXES = 10_000;
    private static final long MAX_BOX_SIZE = 2L * 1024 * 1024 * 1024;

    /** 返回视频时长（秒，向上取整）。无法识别时抛业务异常（fail-closed）。 */
    public int requireDurationSeconds(Path file) {
        Integer seconds = parseMp4(file);
        if (seconds == null) {
            seconds = probeWithFfprobe(file);
        }
        if (seconds == null || seconds <= 0) {
            throw BusinessException.of("ACCEPTANCE_FILE_INVALID：视频文件无法识别，请重新录制");
        }
        return seconds;
    }

    /** 解析 root → moov → mvhd；遇到 mdat 只跳过，不读取内容。 */
    private Integer parseMp4(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLength = raf.length();
            long position = 0;
            int boxes = 0;
            while (position + 8 <= fileLength && boxes++ < MAX_BOXES) {
                raf.seek(position);
                long size = raf.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                long header = 8;
                if (size == 1) {
                    if (position + 16 > fileLength) break;
                    size = raf.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = fileLength - position;
                }
                if (size < header || size > MAX_BOX_SIZE || position + size > fileLength) {
                    break;
                }
                if ("moov".equals(type)) {
                    Integer duration = parseMoov(raf, position + header, position + size);
                    if (duration != null) return duration;
                }
                position += size;
            }
        } catch (IOException e) {
            log.warn("解析视频时长失败 file={}: {}", file.getFileName(), e.getMessage());
        }
        return null;
    }

    private Integer parseMoov(RandomAccessFile raf, long start, long end) throws IOException {
        long position = start;
        int boxes = 0;
        while (position + 8 <= end && boxes++ < MAX_BOXES) {
            raf.seek(position);
            long size = raf.readInt() & 0xFFFFFFFFL;
            byte[] typeBytes = new byte[4];
            raf.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            long header = 8;
            if (size == 1) {
                if (position + 16 > end) break;
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < header || position + size > end) break;
            if ("mvhd".equals(type)) {
                return readMvhd(raf, position + header, position + size);
            }
            position += size;
        }
        return null;
    }

    private Integer readMvhd(RandomAccessFile raf, long start, long end) throws IOException {
        raf.seek(start);
        int version = raf.readUnsignedByte();
        raf.skipBytes(3); // flags
        long timescale;
        long duration;
        if (version == 1) {
            raf.skipBytes(16); // creation + modification (64-bit each)
            timescale = raf.readInt() & 0xFFFFFFFFL;
            duration = raf.readLong();
        } else {
            raf.skipBytes(8); // creation + modification (32-bit each)
            timescale = raf.readInt() & 0xFFFFFFFFL;
            duration = raf.readInt() & 0xFFFFFFFFL;
        }
        if (timescale == 0 || duration <= 0 || duration == 0xFFFFFFFFL) {
            return null;
        }
        double seconds = (double) duration / timescale;
        if (seconds <= 0 || seconds > 24 * 60 * 60) {
            return null;
        }
        return (int) Math.ceil(seconds);
    }

    private Integer probeWithFfprobe(Path file) {
        Process process = null;
        try {
            process = new ProcessBuilder("ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            double seconds = Double.parseDouble(output);
            return seconds > 0 ? (int) Math.ceil(seconds) : null;
        } catch (Exception e) {
            log.debug("ffprobe 不可用或解析失败: {}", e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
