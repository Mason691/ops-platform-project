package com.aip.ops.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 图片 URL 工具 — 统一处理商品和活动封面图的资源路径。
 *
 * 背景：本平台图片资源统一由 Nginx 通过 /uploads/ops/ 路径直接读磁盘提供。
 * - 活动图片存储于 uploads/ops/img/ 和 uploads/ops/video/ 下（相对路径）
 * - 商品图片来自 mall_product 表，原始存储为完整 HTTPS URL，
 *   通过 symlink uploads/ops/mall → /home/jason/uploads/ 使 Nginx 可直接读取
 *
 * 前端 image.js assetUrl() 基于平台自动拼接域名：
 *   H5: BASE_URL='' → 根相对路径，Nginx 反代
 *   MP: BASE_URL='https://a.15891589.xyz' → 完整 HTTPS URL（微信要求）
 */
@Component
public class ImageUrlUtil {

    @Value("${app.resource.base-url:https://test.jdzn99.com}")
    private String resourceBaseUrl;

    /** 已知占位图/测试图片域名，匹配到的 URL 返回 null 避免前端加载失败 */
    private static final Set<String> PLACEHOLDER_DOMAINS = Set.of(
            "via.placeholder.com", "placeholder.com", "placehold.co", "placekitten.com"
    );

    /**
     * 将商品封面图 URL 转换为可通过 Nginx /uploads/ops/mall/ 直接访问的相对路径。
     *
     * 输入示例：
     *   https://test.jdzn99.com/uploads/068eac5be25d4a979a2a4eb45fb1dd4f.jpg
     *   /uploads/ops/mall/068eac5be25d4a979a2a4eb45fb1dd4f.jpg
     *
     * 输出：/uploads/ops/mall/{filename}
     */
    public String normalizeProductImage(String path) {
        if (path == null || path.isBlank()) return null;
        String str = path.trim();

        // 已经是 symlink 路径，直接返回
        if (str.startsWith("/uploads/ops/mall/")) return str;

        // 提取文件名（去掉路径和查询参数）
        String filename = str.substring(str.lastIndexOf('/') + 1);
        int qIdx = filename.indexOf('?');
        if (qIdx > 0) filename = filename.substring(0, qIdx);

        return "/uploads/ops/mall/" + filename;
    }

    /**
     * 规范化活动/通用图片 URL。
     * - 占位图域名（via.placeholder.com 等）→ null
     * - 本站完整 HTTPS URL → 提取相对路径（去掉域名）
     * - 外部完整 HTTPS URL → 原样保留（如微信视频号封面）
     * - 相对路径 → 原样返回
     * - null/空白 → null
     */
    public String normalizeImage(String path) {
        if (path == null || path.isBlank()) return null;
        String str = path.trim();

        // 完整 URL → 检查是否占位图域名
        if (str.startsWith("http://") || str.startsWith("https://")) {
            // 过滤已知占位图域名
            for (String domain : PLACEHOLDER_DOMAINS) {
                if (str.contains(domain)) return null;
            }
            // 本站 URL → 提取路径部分，转相对路径
            if (str.contains("test.jdzn99.com") || str.contains("a.15891589.xyz") || str.contains("localhost")) {
                int schemeEnd = str.indexOf("://");
                int pathStart = str.indexOf('/', schemeEnd + 3);
                if (pathStart > 0) {
                    str = str.substring(pathStart);
                } else {
                    return null;
                }
                int qIdx = str.indexOf('?');
                if (qIdx > 0) str = str.substring(0, qIdx);
                return str;
            }
            // 外部 URL → 原样保留（前端 assetUrl 会直接通过）
            return str;
        }

        // Data URI → 原样返回（base64 图片）
        if (str.startsWith("data:")) return str;

        // 相对路径 → 确保以 / 开头
        if (!str.startsWith("/")) str = "/" + str;
        return str;
    }

    /**
     * 获取完整的 HTTPS URL（供小程序等需要绝对地址的场景）。
     */
    public String getFullUrl(String path) {
        if (path == null || path.isBlank()) return null;
        String str = path.trim();
        if (str.startsWith("http://") || str.startsWith("https://")) {
            // 已经是完整 URL，但如果是旧的 mall 路径则转换
            if (str.contains("/uploads/") && !str.contains("/uploads/ops/")) {
                return resourceBaseUrl + normalizeProductImage(str);
            }
            return str;
        }
        if (str.startsWith("data:")) return str;
        if (!str.startsWith("/")) str = "/" + str;
        return resourceBaseUrl + str;
    }

    /**
     * 补全活动封面 URL：若 coverUrl 为空，回退到 coverImage。
     *
     * @param coverUrl    活动封面 URL（ops_activity.cover_url）
     * @param coverImage  活动封面图片（ops_activity.cover_image）
     * @return 有效的图片相对路径
     */
    public String resolveActivityCover(String coverUrl, String coverImage) {
        String src = (coverUrl != null && !coverUrl.isBlank()) ? coverUrl : coverImage;
        return normalizeImage(src);
    }
}
