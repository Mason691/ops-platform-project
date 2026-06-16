package com.aip.ops.controller;

import com.aip.sdk.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 通用文件上传 — 供管理后台活动/商品等模块使用。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/files")
public class FileUploadController {

    private static final Set<String> IMAGE_EXTS = Set.of("jpg","jpeg","png","gif","webp","bmp");
    private static final Set<String> VIDEO_EXTS = Set.of("mp4","mov","avi","webm");
    private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;    // 2MB
    private static final long MAX_VIDEO_BYTES = 50 * 1024 * 1024;   // 50MB

    @PostMapping("/upload")
    public R<Map<String,Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return R.fail("文件为空");
        String orig = file.getOriginalFilename();
        String ext = orig != null && orig.contains(".") ? orig.substring(orig.lastIndexOf('.')+1).toLowerCase() : "";
        boolean isImage = IMAGE_EXTS.contains(ext);
        boolean isVideo = VIDEO_EXTS.contains(ext);
        if (!isImage && !isVideo) return R.fail("不支持的文件格式: " + ext);

        long max = isVideo ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
        if (file.getSize() > max) {
            String hint = isVideo ? "50MB" : "2MB";
            return R.fail("文件大小超过限制（最大" + hint + "）");
        }

        try {
            // Use ops/ prefix so Nginx can serve files directly from disk
            // without conflicting with mall backend's /uploads/ route.
            String subdir = isVideo ? "ops/video/" : "ops/img/";
            String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String baseDir = System.getProperty("user.dir");
            File dir = new File(baseDir, "uploads/" + subdir + datePart + "/");
            if (!dir.exists()) dir.mkdirs();

            String name = UUID.randomUUID().toString().substring(0,8) + "." + ext;
            File dest = new File(dir, name);
            file.transferTo(dest);
            String url = "/uploads/" + subdir + datePart + "/" + name;

            // Auto-transcode H.265/HEVC videos to H.264 for universal browser support
            long fileSize = file.getSize();
            if (isVideo) {
                try {
                    long newSize = transcodeToH264IfNeeded(dest);
                    if (newSize > 0) {
                        fileSize = newSize;
                        log.info("视频已自动转码为 H.264: {} → {} ({} bytes)", orig, url, newSize);
                    }
                } catch (Exception te) {
                    log.warn("视频转码失败（保留原始文件）: {} → {}, error={}", orig, url, te.getMessage());
                }
            }

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("url", url);
            r.put("type", isVideo ? "video" : "image");
            r.put("size", fileSize);
            log.info("文件上传成功: {} → {} ({} bytes)", orig, url, fileSize);
            return R.ok(r);
        } catch (Exception e) {
            log.error("文件上传失败: {}", orig, e);
            return R.fail("上传失败，请重试");
        }
    }

    /**
     * 检测视频编码，若为 H.265/HEVC 则自动转码为 H.264。
     * @return 转码后的文件大小（字节），若无需转码或失败则返回 0
     */
    private long transcodeToH264IfNeeded(File videoFile) {
        try {
            // ── 1. 用 ffprobe 检测编码 ──
            ProcessBuilder probePb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name",
                    "-of", "csv=p=0",
                    videoFile.getAbsolutePath());
            probePb.redirectErrorStream(true);
            Process probeProc = probePb.start();
            String codec = new String(probeProc.getInputStream().readAllBytes()).trim();
            probeProc.waitFor();

            if (!"hevc".equalsIgnoreCase(codec)) {
                return 0; // 已经是 H.264 或其他兼容格式
            }

            // ── 2. 转码 H.265 → H.264 ──
            log.info("检测到 H.265 视频，开始转码 H.264: {}", videoFile.getAbsolutePath());
            File tmpFile = new File(videoFile.getParentFile(), videoFile.getName() + ".tmp.mp4");

            ProcessBuilder ffmpegPb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", videoFile.getAbsolutePath(),
                    "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                    "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart",
                    tmpFile.getAbsolutePath());
            ffmpegPb.redirectErrorStream(true);
            Process ffmpegProc = ffmpegPb.start();
            ffmpegProc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exitCode = ffmpegProc.waitFor();

            if (exitCode != 0 || !tmpFile.exists() || tmpFile.length() == 0) {
                tmpFile.delete();
                return 0;
            }

            // ── 3. 用转码后的文件替换原文件 ──
            long origSize = videoFile.length();
            long newSize = tmpFile.length();
            if (!videoFile.delete() || !tmpFile.renameTo(videoFile)) {
                // fallback: keep original
                tmpFile.delete();
                log.warn("H.264 转码后文件替换失败，保留原始 H.265 文件");
                return 0;
            }
            log.info("H.265→H.264 转码完成: {} → {} bytes", origSize, newSize);
            return newSize;
        } catch (Exception e) {
            log.warn("视频转码异常: {}", e.getMessage());
            return 0;
        }
    }
}
