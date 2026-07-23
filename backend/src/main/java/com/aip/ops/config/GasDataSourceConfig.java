package com.aip.ops.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 外部燃气平台数据源（jinyuan_info.sb_gas_user）。
 * 独立连接池，延迟初始化，仅在实际导入时创建连接。
 */
@Configuration
public class GasDataSourceConfig {

    @Bean("gasDataSource")
    @Lazy
    @ConfigurationProperties("gas.datasource")
    public DataSource gasDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("gasJdbcTemplate")
    @Lazy
    public JdbcTemplate gasJdbcTemplate(@Qualifier("gasDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
