package com.aip.ops.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部燃气平台数据源（jinyuan_info.sb_gas_user）。
 * 使用 HikariCP 手动配置，initializationFailTimeout=-1 避免连接失败阻塞启动。
 *
 * 关键：只暴露 DataSource Bean，不暴露 JdbcTemplate Bean。
 * 否则 Spring Boot JdbcTemplateAutoConfiguration 会因 @ConditionalOnMissingBean 退出，
 * 导致整个应用的 JdbcTemplate 都变成 gas 数据源。
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

    @Bean("gasDataSource")
    public HikariDataSource gasDataSource() {
        this.hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl(jdbcUrl);
        hikariDataSource.setUsername(username);
        hikariDataSource.setPassword(password);
        hikariDataSource.setDriverClassName(driverClassName);
        hikariDataSource.setMaximumPoolSize(3);
        hikariDataSource.setMinimumIdle(1);
        hikariDataSource.setConnectionTimeout(10000);
        hikariDataSource.setInitializationFailTimeout(-1);
        return hikariDataSource;
    }

    @Bean("gasCompanyFilter")
    public String gasCompanyFilter() {
        return companyFilter;
    }

    @PreDestroy
    public void close() {
        if (hikariDataSource != null && !hikariDataSource.isClosed()) {
            hikariDataSource.close();
        }
    }
}
