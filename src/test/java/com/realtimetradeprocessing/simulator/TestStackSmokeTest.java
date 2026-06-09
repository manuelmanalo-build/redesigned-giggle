package com.realtimetradeprocessing.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class TestStackSmokeTest {

    @Test
    void junitAssertjAndMockitoAreAvailable() {
        Runnable runnable = mock(Runnable.class);

        runnable.run();

        verify(runnable).run();
        assertThat(RealtimeTradeProcessingSimulatorApplication.class.getPackageName())
            .isEqualTo("com.realtimetradeprocessing.simulator");
    }
}

