package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Dashboard numbers must be derived, never guessed. */
class MetricsMathTest {

  @Test
  void cpu_usage_is_the_busy_share_of_two_samples() {
    // total 1000→1100 (delta 100), idle 800→850 (delta 50) ⇒ 50% busy.
    assertEquals(50.0, MetricsMath.cpuBusyPercent(1000L, 800L, 1100L, 850L));
    // Fully idle and fully busy are both representable.
    assertEquals(0.0, MetricsMath.cpuBusyPercent(100L, 100L, 200L, 200L));
    assertEquals(100.0, MetricsMath.cpuBusyPercent(100L, 100L, 200L, 100L));
  }

  @Test
  void a_single_snapshot_is_not_a_percentage() {
    assertNull(MetricsMath.cpuBusyPercent(null, null, 500L, 400L));
    assertNull(MetricsMath.cpuBusyPercent(500L, null, 600L, 450L));
    assertNull(MetricsMath.cpuBusyPercent(null, 400L, 600L, 450L));
  }

  @Test
  void counters_that_do_not_advance_yield_unknown_not_zero() {
    assertNull(MetricsMath.cpuBusyPercent(500L, 400L, 500L, 400L));
    // A reboot send the counters backwards.
    assertNull(MetricsMath.cpuBusyPercent(900L, 800L, 100L, 90L));
    // idle advancing faster than the total is impossible and must not be clamped into a number.
    assertNull(MetricsMath.cpuBusyPercent(100L, 10L, 200L, 150L));
  }

  @Test
  void cpu_percentage_is_rounded_to_one_decimal_and_bounded() {
    assertEquals(33.3, MetricsMath.cpuBusyPercent(0L, 0L, 3L, 2L));
    assertEquals(66.7, MetricsMath.cpuBusyPercent(0L, 0L, 3L, 1L));
  }

  @Test
  void memory_usage_uses_available_memory_and_refuses_a_zero_total() {
    assertEquals(50.0, MetricsMath.memoryUsedPercent(1000, 500));
    assertEquals(0.0, MetricsMath.memoryUsedPercent(1000, 1000));
    assertEquals(100.0, MetricsMath.memoryUsedPercent(1000, 0));
    assertNull(MetricsMath.memoryUsedPercent(0, 0));
    assertNull(MetricsMath.memoryUsedPercent(-5, 0));
    // Available larger than total is clamped rather than producing a negative usage.
    assertEquals(0.0, MetricsMath.memoryUsedPercent(1000, 2000));
  }
}
