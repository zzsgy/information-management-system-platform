package com.company.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 新平台独立入口；仅扫描本包，旧档案控制器、初始化器及数据库迁移不会被加载。 */
@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
    /** 启动平台；配置文件固定为 platform，禁止继承旧 application.yml 的业务库缺省值。 */
    public static void main(String[] args) {
        System.setProperty("spring.config.name", "platform");
        SpringApplication.run(PlatformApplication.class, args);
    }
}
