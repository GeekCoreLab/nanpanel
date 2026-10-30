/**
 * Placement helpers for the application canvas. Nodes are absolutely positioned, so
 * anything added — a manually dropped device or a generated route draft — used to land on
 * top of what was already there. These are pure functions so the search behaviour can be
 * tested without React Flow.
 */

export type PlacedRect = { x: number; y: number; width: number; height: number };

const GAP = 24;

export function overlaps(a: PlacedRect, b: PlacedRect): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width
    && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** True when the candidate touches nothing already on the canvas. */
export function isFree(occupied: readonly PlacedRect[], candidate: PlacedRect): boolean {
  return !occupied.some(rect => overlaps(rect, candidate));
}

/**
 * Finds the first free slot starting at {@code origin} and scanning left-to-right, top-to-bottom
 * in steps of the new node's own size plus a gap. Existing nodes are never moved: the result
 * only depends on what is occupied, so the same input always yields the same position.
 */
export function findFreePosition(occupied: readonly PlacedRect[], size: { width: number; height: number },
  origin: { x: number; y: number }, columns = 6, gap = GAP): { x: number; y: number } {
  const width = Math.max(1, size.width);
  const height = Math.max(1, size.height);
  for (let row = 0; row < 50; row++) {
    for (let column = 0; column < columns; column++) {
      const candidate = {
        x: origin.x + column * (width + gap),
        y: origin.y + row * (height + gap),
        width, height,
      };
      if (isFree(occupied, candidate)) return { x: candidate.x, y: candidate.y };
    }
  }
  // Every scanned slot was taken: fall back to the origin rather than placing off-canvas.
  return { x: origin.x, y: origin.y };
}
