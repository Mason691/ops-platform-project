package com.aip.ops.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部燃气平台数据源（jinyuan_info.sb_gas_user）。
 * 使用 HikariCP 手动配置，initializationFailTimeout=-1 避免连接失败阻塞启动。
 * 仅在 GasUserImportHandler 实际调用时尝试连接。
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

    /** 外部燃气公司筛选条件（默认德保），可通过配置覆盖 */
    @Value("${gas.import.company-filter:德保}")
    private String companyFilter;

    private HikariDataSource hikariDataSource;

    @Bean("gasJdbcTemplate")
    public JdbcTemplate gasJdbcTemplate() {
        this.hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl(jdbcUrl);
        hikariDataSource.setUsername(username);
        hikariDataSource.setPassword(password);
        hikariDataSource.setDriverClassName(driverClassName);
        hikariDataSource.setMaximumPoolSize(3);
        hikariDataSource.setMinimumIdle(1);
        hikariDataSource.setConnectionTimeout(10000);
        hikariDataSource.setInitializationFailTimeout(-1); // 启动时不验证连接
        return new JdbcTemplate(hikariDataSource);
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
