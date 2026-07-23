package com.aip.ops.config;

import com.zaxxer.hikari.HikariDataSource;
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

    @Bean("gasJdbcTemplate")
    public JdbcTemplate gasJdbcTemplate() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(10000);
        ds.setInitializationFailTimeout(-1); // 启动时不验证连接
        return new JdbcTemplate(ds);
    }
}
