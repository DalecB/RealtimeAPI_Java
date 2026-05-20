package com.jake.realtimeapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RealtimeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApiApplication.class, args);
    }

}
