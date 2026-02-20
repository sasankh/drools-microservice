package com.company.drools.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
}
