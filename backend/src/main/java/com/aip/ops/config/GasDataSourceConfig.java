package com.aip.ops.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部燃气平台数据源（jinyuan_info.sb_gas_user）。
 *
 * 关键：不注册任何 DataSource 或 JdbcTemplate 类型的 Bean！
 * 否则会触发 Spring Boot DataSourceAutoConfiguration / JdbcTemplateAutoConfiguration
 * 的 @ConditionalOnMissingBean 条件退出，导致主数据源消失，MyBatis 全部连到 Polardb。
 *
 * 使用方式：注入 GasDataSourceConfig，调用 getJdbcTemplate() 获取局部 JdbcTemplate。
 */
@Configuration
public class GasDataSourceConfig {

    @Value("${gas.datasource.jdbc-url}")
    private String jdbcUrl;

    @Value("${gas.datasource.username}")
    private String username;

    @Value("${gas.datasource.password}")
    private String password;

    @Value("${gas.datasource.driver-class-name}")
    private String driverClassName;

    @Value("${gas.import.company-filter:德保}")
    private String companyFilter;

    private HikariDataSource hikariDataSource;

    @Bean("gasCompanyFilter")
    public String gasCompanyFilter() {
        return companyFilter;
    }

    /** 获取外部燃气数据库的 JdbcTemplate（每次调用创建新实例，线程安全）。 */
    public JdbcTemplate getJdbcTemplate() {
        return new JdbcTemplate(getDataSource());
    }

    private HikariDataSource getDataSource() {
        if (this.hikariDataSource == null || this.hikariDataSource.isClosed()) {
            synchronized (this) {
                if (this.hikariDataSource == null || this.hikariDataSource.isClosed()) {
                    HikariDataSource ds = new HikariDataSource();
                    ds.setJdbcUrl(jdbcUrl);
                    ds.setUsername(username);
                    ds.setPassword(password);
                    ds.setDriverClassName(driverClassName);
                    ds.setMaximumPoolSize(3);
                    ds.setMinimumIdle(1);
                    ds.setConnectionTimeout(10000);
                    ds.setInitializationFailTimeout(-1);
                    this.hikariDataSource = ds;
                }
            }
        }
        return this.hikariDataSource;
    }

    @PreDestroy
    public void close() {
        if (hikariDataSource != null && !hikariDataSource.isClosed()) {
            hikariDataSource.close();
        }
    }
}
