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

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("url", url);
            r.put("type", isVideo ? "video" : "image");
            r.put("size", file.getSize());
            log.info("文件上传成功: {} → {} ({} bytes)", orig, url, file.getSize());
            return R.ok(r);
        } catch (Exception e) {
            log.error("文件上传失败: {}", orig, e);
            return R.fail("上传失败，请重试");
        }
    }
}
