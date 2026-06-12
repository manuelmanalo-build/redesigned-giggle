package com.realtimetradeprocessing.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RealtimeTradeProcessingSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeTradeProcessingSimulatorApplication.class, args);
    }
}
