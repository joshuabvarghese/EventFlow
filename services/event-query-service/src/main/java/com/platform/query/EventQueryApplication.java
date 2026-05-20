package com.platform.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EventQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventQueryApplication.class, args);
    }
}
