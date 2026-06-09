package com.aip.ops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aip.ops.mapper")
public class OpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
