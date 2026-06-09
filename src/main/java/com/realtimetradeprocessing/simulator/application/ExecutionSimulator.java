package com.realtimetradeprocessing.simulator.application;

import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

public interface ExecutionSimulator {

    ExecutionSimulation simulate(OrderEntity order);
}
