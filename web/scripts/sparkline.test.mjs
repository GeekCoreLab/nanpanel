import { test } from 'node:test';
import assert from 'node:assert/strict';
import { latestValue, pointsAttribute, sparklineRuns } from '../src/lib/sparkline.ts';

test('an empty series draws nothing', () => {
  assert.deepEqual(sparklineRuns([], 100, 40), []);
});

test('a single sample is one point at the left edge', () => {
  const runs = sparklineRuns([50], 100, 40);
  assert.equal(runs.length, 1);
  assert.deepEqual(runs[0], [{ x: 0, y: 20 }]);
});

test('values map onto the box with y inverted', () => {
  const [run] = sparklineRuns([0, 100], 100, 40);
  assert.deepEqual(run, [{ x: 0, y: 40 }, { x: 100, y: 0 }]);
});

test('a gap splits the line instead of being interpolated', () => {
  const runs = sparklineRuns([10, 20, null, 30], 90, 40);
  assert.equal(runs.length, 2);
  assert.equal(runs[0].length, 2);
  assert.equal(runs[1].length, 1);
  // The point after the gap keeps its own x position, not a stitched-on one.
  assert.equal(runs[1][0].x, 90);
});

test('consecutive gaps collapse and never produce empty runs', () => {
  const runs = sparklineRuns([null, null, 5, null, null], 100, 40);
  assert.equal(runs.length, 1);
  assert.equal(runs[0].length, 1);
});

test('out-of-range values are clamped into the box', () => {
  const [run] = sparklineRuns([-50, 250], 100, 100, 0, 100);
  assert.deepEqual(run, [{ x: 0, y: 100 }, { x: 100, y: 0 }]);
});

test('a flat series sits in the middle when the range collapses', () => {
  const [run] = sparklineRuns([7, 7, 7], 10, 40, 7, 7);
  assert.deepEqual(run.map(point => point.y), [20, 20, 20]);
});

test('points attribute is a plain svg string', () => {
  assert.equal(pointsAttribute([{ x: 0, y: 1.5 }, { x: 10, y: 2 }]), '0,1.5 10,2');
  assert.equal(pointsAttribute([]), '');
});

test('the latest reported value skips trailing gaps', () => {
  assert.equal(latestValue([1, 2, null]), 2);
  assert.equal(latestValue([null, null]), null);
  assert.equal(latestValue([]), null);
  assert.equal(latestValue([3, 4]), 4);
});
