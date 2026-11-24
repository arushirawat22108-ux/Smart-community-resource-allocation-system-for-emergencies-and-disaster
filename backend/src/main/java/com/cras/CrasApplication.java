package com.cras;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrasApplication.class, args);
    }
}
