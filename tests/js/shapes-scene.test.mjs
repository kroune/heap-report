import test from "node:test";
import assert from "node:assert/strict";
import { adjSlice, buildScene, fitTransform, hitTest, makeSpatialGrid,
  zoomTransform } from "../../web/viz/shapes/scene.js";

function sceneLayout() {
  return {
    nodes: [
      { x: -120, y: 50, rad: 7, kind: "node" },
      { x: 20, y: 180, rad: 9, kind: "leaf" },
    ],
    edges: [{ s: 0, t: 1, total: 4 }],
    emax: 4,
    width: 300,
    height: 300,
  };
}

test("grid indexes reject out-of-world cells and clamp viewport ranges", () => {
  const scene = buildScene(sceneLayout(), ["#fff", "#000"]);
  assert.equal(scene.grid.index(-100000, -100000), -1);
  assert.equal(scene.grid.range(-1e9, -1e9, -1e8, -1e8), null);
  assert.equal(scene.grid.range(1e8, 1e8, 1e9, 1e9), null);
  const clamped = scene.grid.range(-1e9, -1e9, 1e9, 1e9);
  assert.deepEqual(clamped, {
    cx0: scene.grid.cbx, cy0: scene.grid.cby,
    cx1: scene.grid.cbx + scene.grid.ncx - 1,
    cy1: scene.grid.cby + scene.grid.ncy - 1,
  });
  assert.equal(scene.grid.range(NaN, 0, 1, 1), null);
});

test("hit testing works at negative coordinates and outside the world", () => {
  const scene = buildScene(sceneLayout(), ["#fff", "#000"]);
  assert.equal(hitTest(scene, -120, 50), 0);
  assert.equal(hitTest(scene, 20, 180), 1);
  assert.equal(hitTest(scene, -100000, 50), -1);
  assert.equal(hitTest(scene, 100000, 100000), -1);
});

test("scene adjacency and flat edge indexes include every edge", () => {
  const scene = buildScene(sceneLayout(), ["#fff", "#000"]);
  assert.deepEqual(adjSlice(scene.outAdj, 0), [0]);
  assert.deepEqual(adjSlice(scene.inAdj, 1), [0]);
  assert.deepEqual(adjSlice(scene.outAdj, 1), []);
  assert.equal(scene.roamers.length + scene.eIds.length >= 1, true);
  assert.equal(scene.ew.length, 1);
  assert.equal(scene.ex0.length, 1);
});

test("empty spatial grids are safe", () => {
  const grid = makeSpatialGrid([]);
  assert.ok(grid.cellCount > 0);
  assert.equal(grid.index(1000, 1000), -1);
  assert.equal(grid.range(-1000, -1000, -900, -900), null);
  const scene = buildScene({ nodes: [], edges: [], emax: 1 }, []);
  assert.equal(hitTest(scene, 0, 0), -1);
});

test("fit and zoom stay finite for empty worlds and tiny viewports", () => {
  for (const [vw, vh, ww, wh] of [[760, 620, 0, 0], [20, 20, 100, 100], [760, 620, 300, 300]]) {
    const fitted = fitTransform(vw, vh, ww, wh);
    assert.ok(fitted.k > 0 && Number.isFinite(fitted.k));
    assert.ok(Number.isFinite(fitted.x) && Number.isFinite(fitted.y));
    const zoomed = zoomTransform(fitted, 10, 10, 1.25, vw, vh, ww, wh);
    assert.ok(zoomed.k > 0 && Number.isFinite(zoomed.k));
    assert.ok(Number.isFinite(zoomed.x) && Number.isFinite(zoomed.y));
  }
});

test("zoom preserves the world point under the pointer", () => {
  const initial = fitTransform(800, 600, 400, 300);
  const mx = 333, my = 222;
  const wx = (mx - initial.x) / initial.k, wy = (my - initial.y) / initial.k;
  const zoomed = zoomTransform(initial, mx, my, 1.18, 800, 600, 400, 300);
  assert.ok(Math.abs((mx - zoomed.x) / zoomed.k - wx) < 1e-9);
  assert.ok(Math.abs((my - zoomed.y) / zoomed.k - wy) < 1e-9);
  assert.deepEqual(zoomTransform(initial, mx, my, 0, 800, 600, 400, 300), initial);
});
