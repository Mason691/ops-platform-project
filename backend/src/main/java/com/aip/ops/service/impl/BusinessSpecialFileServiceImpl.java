package com.aip.ops.service.impl;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.entity.OpsBusinessSpecialFile;
import com.aip.ops.mapper.OpsBusinessSpecialFileMapper;
import com.aip.ops.service.BusinessSpecialFileService;
import com.aip.ops.service.EmployeeService;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 特批文件上传：PDF/JPG/PNG ≤10MB，存储到上传根目录 special-approval/{corpId}/。
 *
 * <p>文件内容会做基础校验（PDF 魔数 / 图片可解码），原始文件名与上传人
 * 写入 ops_business_special_file，供订单提交引用与审计追溯。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessSpecialFileServiceImpl implements BusinessSpecialFileService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTS = Set.of("pdf", "jpg", "jpeg", "png");

    private final OpsBusinessSpecialFileMapper fileMapper;
    private final EmployeeService employeeService;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @Override
    public OpsBusinessSpecialFile upload(MultipartFile file) {
        assertEmployee();
        if (file == null || file.isEmpty()) throw BusinessException.of("请选择特批文件");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw BusinessException.of("特批文件不能超过10MB");
        }
        String original = sanitizeFileName(file.getOriginalFilename());
        String ext = extension(original);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw BusinessException.of("特批文件仅支持 PDF、JPG、PNG");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.of("读取特批文件失败，请重试");
        }
        validateContent(bytes, ext);
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织后再上传特批文件");
        }
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        // 必须落在 /uploads/ops/ 前缀下：生产 host nginx 只把 /uploads/ops/ 反代到 ops 后端，
        // 其余 /uploads/ 走 mall，使用 /uploads/special-approval/ 会在生产 404/串到 mall。
        String relative = "ops/special-approval/" + corpId + "/" + datePart + "/" + storedName;
        Path dest = Paths.get(uploadBaseDir, relative);
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        } catch (IOException e) {
            log.error("特批文件保存失败 corpId={} name={}", corpId, original, e);
            throw BusinessException.of("特批文件保存失败，请重试");
        }
        LoginUser user = SecurityUtils.getCurrentUser();
        OpsBusinessSpecialFile record = new OpsBusinessSpecialFile();
        record.setCorpId(corpId);
        record.setFileName(original);
        record.setFileUrl("/uploads/" + relative);
        record.setFileSize((long) bytes.length);
        record.setContentType(contentType(ext));
        record.setUploaderUserId(user != null ? user.getUserId() : null);
        record.setUploaderName(user != null ? user.getUsername() : employeeService.getCurrentAgentPhone());
        record.setUploadTime(LocalDateTime.now());
        record.setDeleteFlag(0);
        fileMapper.insert(record);
        log.info("特批文件上传成功 corpId={} fileId={} name={} size={}", corpId,
                record.getId(), original, bytes.length);
        return record;
    }

    @Override
    public OpsBusinessSpecialFile requireOwned(Long id, Long corpId) {
        if (id == null || id <= 0) throw BusinessException.of("请上传特批文件");
        OpsBusinessSpecialFile file = fileMapper.selectById(id);
        if (file == null || !corpId.equals(file.getCorpId())) {
            throw BusinessException.of("特批文件不存在或不属于当前组织，请重新上传");
        }
        return file;
    }

    private void assertEmployee() {
        // 会员 SSO 令牌（token_type=sso）由 Controller 层统一拒绝；
        // 此处兜底确认能解析员工手机号，避免无员工身份写入附件。
        employeeService.getCurrentAgentPhone();
    }

    private void validateContent(byte[] bytes, String ext) {
        if (bytes.length == 0) throw BusinessException.of("特批文件为空");
        if ("pdf".equals(ext)) {
            String header = new String(bytes, 0, Math.min(bytes.length, 5),
                    java.nio.charset.StandardCharsets.US_ASCII);
            if (!"%PDF-".equals(header)) {
                throw BusinessException.of("PDF 文件格式不合法");
            }
            return;
        }
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw BusinessException.of("图片文件格式不合法");
            }
        } catch (IOException e) {
            throw BusinessException.of("图片文件读取失败");
        }
    }

    private static String sanitizeFileName(String name) {
        String value = name == null ? "特批文件" : name.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.isEmpty()) value = "特批文件";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String contentType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
