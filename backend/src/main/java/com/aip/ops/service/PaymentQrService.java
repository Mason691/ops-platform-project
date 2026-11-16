package com.aip.ops.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 收款码配置与防篡改读取。
 */
public interface PaymentQrService {

    /** 配置元数据（不含服务器路径）。 */
    Map<String, Object> metadata(Long corpId);

    /** 后台上传/替换收款码，登记 SHA-256。 */
    Map<String, Object> upload(MultipartFile file, Long corpId);

    /** 读取并校验收款码；文件缺失/哈希不一致/非图片时抛业务异常。 */
    VerifiedQr loadVerified(Long corpId);

    record VerifiedQr(byte[] bytes, String contentType) {
    }
}
