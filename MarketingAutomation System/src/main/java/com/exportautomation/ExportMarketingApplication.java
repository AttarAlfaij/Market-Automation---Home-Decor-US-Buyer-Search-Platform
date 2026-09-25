package com.exportautomation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class   ExportMarketingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExportMarketingApplication.class, args);
    }
}
