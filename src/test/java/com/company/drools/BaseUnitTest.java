package com.company.drools;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for unit tests providing common mock setup and configuration. All unit test classes
 * should extend this class to get standard mocking infrastructure.
 */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public abstract class BaseUnitTest {

  @Mock protected MeterRegistry meterRegistry;

  /**
   * Setup common mocks before each test. Provides default behavior for MeterRegistry to avoid
   * NullPointerExceptions.
   */
  @BeforeEach
  void setupBaseMocks() {
    // Mock Counter creation
    when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));

    // Mock Timer creation
    when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(mock(Timer.class));
  }
}
