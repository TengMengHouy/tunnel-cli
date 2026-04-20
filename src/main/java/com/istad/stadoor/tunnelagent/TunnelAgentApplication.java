package com.istad.stadoor.tunnelagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TunnelAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TunnelAgentApplication.class, args);
    }
}
