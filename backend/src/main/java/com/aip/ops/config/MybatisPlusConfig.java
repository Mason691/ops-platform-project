package com.aip.ops.config;

import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置 — 为 ops 独立应用提供分页拦截器 + 审计字段自动填充。
 *
 * <p>BaseEntity 的 {@code @TableField(fill = ...)} 注解依赖 MetaObjectHandler
 * 才能生效；ops 是独立 Spring Boot 应用，不依赖平台 web 模块，需自行注册。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
                strictInsertFill(metaObject, "modifyTime", LocalDateTime.class, now);
                strictInsertFill(metaObject, "uniqueId", String.class, IdWorker.get32UUID());
                strictInsertFill(metaObject, "updateKey", String.class, "");

                try {
                    LoginUser u = SecurityUtils.getCurrentUser();
                    strictInsertFill(metaObject, "createUser", Long.class, u.getUserId());
                    strictInsertFill(metaObject, "modifyUser", Long.class, u.getUserId());
                    strictInsertFill(metaObject, "createUserName", String.class, u.getUsername());
                    strictInsertFill(metaObject, "modifyUserName", String.class, u.getUsername());
                } catch (Exception ignored) {
                    // 无安全上下文时（如启动任务、异步作业）留空
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName("modifyTime", LocalDateTime.now(), metaObject);
                try {
                    LoginUser u = SecurityUtils.getCurrentUser();
                    setFieldValByName("modifyUser", u.getUserId(), metaObject);
                    setFieldValByName("modifyUserName", u.getUsername(), metaObject);
                } catch (Exception ignored) {
                    // 无安全上下文时留空
                }
            }
        };
    }
}
