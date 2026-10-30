/**
 * Sparklines are drawn from sample series that legitimately contain gaps: the first sample of
 * a host has no CPU percentage, and an offline host produces no samples at all. A gap must
 * break the line rather than be interpolated into a value the host never reported.
 */

export type SparkPoint = { x: number; y: number };

export function sparklineRuns(values: readonly (number | null)[], width: number, height: number,
  min = 0, max = 100): SparkPoint[][] {
  if (values.length === 0) return [];
  const low = Math.min(min, max);
  const high = Math.max(min, max);
  const span = high - low;
  const step = values.length === 1 ? 0 : width / (values.length - 1);
  const runs: SparkPoint[][] = [];
  let current: SparkPoint[] = [];
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) {
      if (current.length > 0) runs.push(current);
      current = [];
      return;
    }
    const ratio = span === 0 ? 0.5 : Math.min(1, Math.max(0, (value - low) / span));
    current.push({ x: Math.round(index * step * 100) / 100, y: Math.round((height - ratio * height) * 100) / 100 });
  });
  if (current.length > 0) runs.push(current);
  return runs;
}

/** SVG `points` attribute for one run. */
export function pointsAttribute(points: readonly SparkPoint[]): string {
  return points.map(point => `${point.x},${point.y}`).join(' ');
}

/** Latest reported value, skipping trailing gaps (a host that stopped reporting). */
export function latestValue(values: readonly (number | null)[]): number | null {
  for (let index = values.length - 1; index >= 0; index--) {
    const value = values[index];
    if (value !== null && Number.isFinite(value)) return value as number;
  }
  return null;
}
