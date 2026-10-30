package io.nanpanel.core;

/**
 * Metrics arithmetic, kept pure so the rules a dashboard depends on are testable without a
 * host: CPU utilisation only exists as a difference between two counter samples.
 */
public final class MetricsMath {

  private MetricsMath() {}

  /** Smallest defensible interval: two samples closer than this share no meaningful delta. */
  static final long MIN_DELTA_TOTAL = 1;

  /**
   * Busy CPU percentage between two {@code /proc/stat} aggregates.
   *
   * <p>Returns {@code null} when it cannot be known: no previous sample (a single snapshot is
   * not a percentage), a non-advancing total, or a counter that went backwards (kernel reboot
   * or wrap). Callers must render {@code null} as "unknown", never as 0%.
   */
  public static Double cpuBusyPercent(Long previousTotal, Long previousIdle, long total, long idle) {
    if (previousTotal == null || previousIdle == null) return null;
    long totalDelta = total - previousTotal;
    long idleDelta = idle - previousIdle;
    if (totalDelta < MIN_DELTA_TOTAL || idleDelta < 0 || idleDelta > totalDelta) return null;
    double busy = (double) (totalDelta - idleDelta) / totalDelta * 100.0;
    return clamp(busy);
  }

  /** Used memory percentage; {@code null} when the total is unknown or zero. */
  public static Double memoryUsedPercent(long totalKib, long availableKib) {
    if (totalKib <= 0) return null;
    long available = Math.max(0, Math.min(availableKib, totalKib));
    return clamp((double) (totalKib - available) / totalKib * 100.0);
  }

  private static Double clamp(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) return null;
    return Math.max(0.0, Math.min(100.0, Math.round(value * 10.0) / 10.0));
  }
}
