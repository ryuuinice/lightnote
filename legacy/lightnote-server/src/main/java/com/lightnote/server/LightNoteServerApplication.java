package com.lightnote.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
/**
 * 服务端应用入口，用于启动 Spring Boot 服务。
 */
public class LightNoteServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LightNoteServerApplication.class, args);
    }
}

