package com.company.drools.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("MemoryController")
class MemoryControllerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    MemoryController controller = new MemoryController();
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Nested
  @DisplayName("GET /admin/memory/info")
  class GetMemoryInfo {

    @Test
    @DisplayName("returns memory info with heap statistics")
    void testReturnsMemoryInfo() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.heap").exists())
          .andExpect(jsonPath("$.heap.usedMB").isNumber())
          .andExpect(jsonPath("$.heap.committedMB").isNumber())
          .andExpect(jsonPath("$.heap.maxMB").isNumber())
          .andExpect(jsonPath("$.heap.usagePercent").exists());
    }

    @Test
    @DisplayName("returns non-heap statistics")
    void testReturnsNonHeapStats() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.nonHeap").exists())
          .andExpect(jsonPath("$.nonHeap.usedMB").isNumber())
          .andExpect(jsonPath("$.nonHeap.committedMB").isNumber());
    }

    @Test
    @DisplayName("returns runtime statistics")
    void testReturnsRuntimeStats() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.runtime").exists())
          .andExpect(jsonPath("$.runtime.maxMemoryMB").isNumber())
          .andExpect(jsonPath("$.runtime.totalMemoryMB").isNumber())
          .andExpect(jsonPath("$.runtime.usedMemoryMB").isNumber())
          .andExpect(jsonPath("$.runtime.freeMemoryMB").isNumber());
    }

    @Test
    @DisplayName("returns memory pools")
    void testReturnsMemoryPools() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.memoryPools").isArray());
    }

    @Test
    @DisplayName("returns garbage collector stats")
    void testReturnsGcStats() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.garbageCollectors").isArray());
    }

    @Test
    @DisplayName("returns warnings list")
    void testReturnsWarnings() throws Exception {
      mockMvc
          .perform(get("/admin/memory/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.warnings").isArray());
    }
  }

  @Nested
  @DisplayName("POST /admin/memory/gc")
  class TriggerGC {

    @Test
    @DisplayName("triggers garbage collection and returns stats")
    void testTriggerGC() throws Exception {
      mockMvc
          .perform(post("/admin/memory/gc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.message").value("Garbage collection triggered"))
          .andExpect(jsonPath("$.usedBeforeMB").isNumber())
          .andExpect(jsonPath("$.usedAfterMB").isNumber())
          .andExpect(jsonPath("$.freedMemoryMB").isNumber())
          .andExpect(jsonPath("$.note").exists());
    }
  }

  @Nested
  @DisplayName("GET /admin/memory/snapshot")
  class GetMemorySnapshot {

    @Test
    @DisplayName("returns memory snapshot with timestamp")
    void testReturnsSnapshot() throws Exception {
      mockMvc
          .perform(get("/admin/memory/snapshot"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.timestamp").isNumber())
          .andExpect(jsonPath("$.heapUsedMB").isNumber())
          .andExpect(jsonPath("$.heapMaxMB").isNumber())
          .andExpect(jsonPath("$.heapUsagePercent").isNumber());
    }
  }

  @Nested
  @DisplayName("Memory Warnings (getMemoryWarnings branch coverage)")
  class MemoryWarnings {

    private Method getMemoryWarningsMethod;
    private MemoryController controller;

    @BeforeEach
    void setUp() throws Exception {
      controller = new MemoryController();
      getMemoryWarningsMethod =
          MemoryController.class.getDeclaredMethod(
              "getMemoryWarnings", double.class, long.class, long.class);
      getMemoryWarningsMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeGetMemoryWarnings(
        double heapUsagePercent, long heapUsed, long heapMax) throws Exception {
      return (List<String>)
          getMemoryWarningsMethod.invoke(controller, heapUsagePercent, heapUsed, heapMax);
    }

    static Stream<Arguments> heapUsageWarningCases() {
      return Stream.of(
          Arguments.of(95.0, "CRITICAL"),
          Arguments.of(85.0, "WARNING"),
          Arguments.of(75.0, "CAUTION"),
          Arguments.of(50.0, null));
    }

    @ParameterizedTest(name = "{0}% heap usage → {1}")
    @MethodSource("heapUsageWarningCases")
    @DisplayName("returns correct warning level for heap usage")
    void testHeapUsageWarningLevel(double usagePercent, String expectedLabel) throws Exception {
      long heapMax = 1024L * 1024 * 1024 * 2; // 2 GB
      long heapUsed = (long) (heapMax * (usagePercent / 100.0));

      List<String> warnings = invokeGetMemoryWarnings(usagePercent, heapUsed, heapMax);

      if (expectedLabel != null) {
        assertThat(warnings).anyMatch(w -> w.contains(expectedLabel));
      } else {
        assertThat(warnings)
            .noneMatch(w -> w.contains("CRITICAL") || w.startsWith("WARNING") || w.contains("CAUTION"));
      }
    }

    @Test
    @DisplayName("returns info warning when heap max is less than 1GB")
    void testInfoWarning_SmallHeapMax() throws Exception {
      long heapMax = 512L * 1024 * 1024; // 512 MB
      long heapUsed = (long) (heapMax * 0.50);

      List<String> warnings = invokeGetMemoryWarnings(50.0, heapUsed, heapMax);

      assertThat(warnings).anyMatch(w -> w.contains("INFO"));
      assertThat(warnings).anyMatch(w -> w.contains("less than 1GB"));
    }

    @Test
    @DisplayName("returns no info warning when heap max is 1GB or more")
    void testNoInfoWarning_LargeHeapMax() throws Exception {
      long heapMax = 1024L * 1024 * 1024 * 2; // 2 GB
      long heapUsed = (long) (heapMax * 0.50);

      List<String> warnings = invokeGetMemoryWarnings(50.0, heapUsed, heapMax);

      assertThat(warnings).noneMatch(w -> w.contains("INFO"));
    }

    @Test
    @DisplayName("returns both critical and info warnings when heap is small and above 90%")
    void testMultipleWarnings_SmallHeapAndHighUsage() throws Exception {
      long heapMax = 256L * 1024 * 1024; // 256 MB
      long heapUsed = (long) (heapMax * 0.95);

      List<String> warnings = invokeGetMemoryWarnings(95.0, heapUsed, heapMax);

      assertThat(warnings).hasSize(2);
      assertThat(warnings).anyMatch(w -> w.contains("CRITICAL"));
      assertThat(warnings).anyMatch(w -> w.contains("INFO"));
    }
  }
}
