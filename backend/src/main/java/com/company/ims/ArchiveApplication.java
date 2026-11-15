package com.company.ims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 档案业务独立启动入口；服务端口被占用时正常失败，不结束其他程序。 */
@SpringBootApplication
public class ArchiveApplication {
    /** 启动 Java 后端，args 为外部配置覆盖参数。 */
    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
