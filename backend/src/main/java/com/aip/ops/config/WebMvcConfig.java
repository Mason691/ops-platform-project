package com.aip.ops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射 — 将 /uploads/** 路径映射到磁盘文件。
 *
 * 生产环境 Docker 部署时，Nginx 将 /uploads/ 请求代理至后端，
 * 由 Spring Boot 直接从磁盘读取文件返回。
 * 测试环境：Nginx 直接通过 alias 读磁盘，此映射作为回退方案同样生效。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Map /uploads/** → file system upload base dir
        String location = uploadBaseDir.endsWith("/") ? uploadBaseDir : uploadBaseDir + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + location)
                .setCachePeriod(3600); // 1 hour cache
    }
}
