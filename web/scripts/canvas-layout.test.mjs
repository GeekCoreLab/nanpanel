import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findFreePosition, isFree, overlaps } from '../src/lib/canvas-layout.ts';

const rect = (x, y, width = 240, height = 172) => ({ x, y, width, height });

test('overlap is strict: touching edges do not collide', () => {
  assert.equal(overlaps(rect(0, 0), rect(0, 0)), true);
  assert.equal(overlaps(rect(0, 0), rect(100, 100)), true);
  assert.equal(overlaps(rect(0, 0), rect(240, 0)), false);
  assert.equal(overlaps(rect(0, 0), rect(0, 172)), false);
  assert.equal(overlaps(rect(0, 0), rect(-240, 0)), false);
});

test('an empty canvas places the node exactly at the origin', () => {
  assert.deepEqual(findFreePosition([], { width: 240, height: 172 }, { x: 40, y: 60 }),
    { x: 40, y: 60 });
});

test('an occupied origin shifts the node right by its own width plus the gap', () => {
  const occupied = [rect(40, 60)];
  assert.deepEqual(findFreePosition(occupied, { width: 240, height: 172 }, { x: 40, y: 60 }),
    { x: 40 + 240 + 24, y: 60 });
  assert.equal(isFree(occupied, rect(40 + 240 + 24, 60)), true);
});

test('a full row wraps to the next row instead of overlapping', () => {
  const occupied = [0, 1, 2].map(index => rect(0 + index * 264, 0));
  const placed = findFreePosition(occupied, { width: 240, height: 172 }, { x: 0, y: 0 }, 3);
  assert.deepEqual(placed, { x: 0, y: 172 + 24 });
  assert.equal(isFree(occupied, rect(placed.x, placed.y)), true);
});

test('placement never moves existing nodes and is deterministic', () => {
  const occupied = [rect(0, 0), rect(264, 0), rect(0, 196)];
  const first = findFreePosition(occupied, { width: 240, height: 172 }, { x: 0, y: 0 });
  const second = findFreePosition(occupied, { width: 240, height: 172 }, { x: 0, y: 0 });
  assert.deepEqual(first, second);
  assert.equal(isFree(occupied, rect(first.x, first.y)), true);
  assert.deepEqual(occupied[0], rect(0, 0));
});
