import test from "node:test";
import assert from "node:assert/strict";
import { computeShapesLayout } from "../../web/viz/shapes/layout.js";

function node(id, extra = {}) {
  return { id, label: `C${id}`, kind: "node", count: 1, st: 10,
    roots: 0, sccSize: 1, edgesPerMember: true, ...extra };
}

test("empty layout is finite and invalid containers fail clearly", () => {
  assert.deepEqual(computeShapesLayout(), {
    nodes: [], edges: [], width: 0, height: 0, layerCount: 0, emax: 1,
  });
  assert.throws(() => computeShapesLayout(null, []), /node and edge arrays/);
  assert.throws(() => computeShapesLayout([], null), /node and edge arrays/);
});

test("single-node layout works without mutating input", () => {
  const input = [node(0, { st: 100, roots: 1 })];
  const snapshot = structuredClone(input);
  const result = computeShapesLayout(input, [], "shallow");
  assert.deepEqual(input, snapshot);
  assert.equal(result.layerCount, 1);
  assert.equal(result.edges.length, 0);
  assert.equal(result.nodes[0].layer, 0);
  assert.ok(result.nodes[0].rad >= 7);
  assert.ok(Number.isFinite(result.nodes[0].x));
  assert.ok(Number.isFinite(result.nodes[0].y));
  assert.ok(result.width > 0 && result.height > 0);
});

test("deep chains are layered iteratively without recursion", () => {
  const count = 20000;
  const nodes = Array.from({ length: count }, (_, i) => node(i));
  const edges = Array.from({ length: count - 1 }, (_, i) => [i, i + 1, 1]);
  const result = computeShapesLayout(nodes, edges, "count");
  assert.equal(result.layerCount, count);
  assert.equal(result.nodes[count - 1].layer, count - 1);
  assert.equal(result.edges.length, count - 1);
  assert.ok(Number.isFinite(result.width));
  assert.ok(Number.isFinite(result.height));
});

test("parallel edges retain exact totals for both multiplicity modes", () => {
  const nodes = [
    node(0, { count: 120, sccSize: 4, edgesPerMember: false }),
    node(1),
    node(2, { count: 5, edgesPerMember: true }),
    node(3),
  ];
  const result = computeShapesLayout(nodes, [
    [0, 1, 7],
    [0, 1, 3],
    [2, 3, 3],
    [1, 1, 99],
  ], "shallow");
  assert.equal(result.edges.length, 3, "condensation self-loops are ignored");
  assert.equal(result.edges[0].total, 7 * Math.floor(120 / 4));
  assert.equal(result.edges[1].total, 3 * Math.floor(120 / 4));
  assert.equal(result.edges[2].total, 15);
  assert.equal(result.edges.reduce((sum, e) => sum + e.total, 0), 315);
  assert.equal(result.emax, 210);
});

test("zero-valued metrics still produce finite radii and coordinates", () => {
  const result = computeShapesLayout([
    node(0, { roots: 0 }), node(1, { roots: 0 }),
  ], [[0, 1, 1]], "roots");
  for (const nd of result.nodes) {
    assert.ok(Number.isFinite(nd.rad));
    assert.ok(Number.isFinite(nd.x));
    assert.ok(Number.isFinite(nd.y));
  }
});
