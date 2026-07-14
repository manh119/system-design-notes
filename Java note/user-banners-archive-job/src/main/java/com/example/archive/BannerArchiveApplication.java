package com.example.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BannerArchiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(BannerArchiveApplication.class, args);
    }
}
