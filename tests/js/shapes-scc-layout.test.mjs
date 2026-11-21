import test from "node:test";
import assert from "node:assert/strict";
import { computeSccLayout } from "../../web/viz/shapes/scc-layout.js";

function member(id, internal = []) {
  return { label: `org.example.C${id}`, short: `C${id}`, cat: "other", internal };
}

function finiteGeometry(layout) {
  for (const m of layout.members) {
    assert.ok(Number.isFinite(m.x) && Number.isFinite(m.y));
    assert.ok(Math.abs(m.x) + m.w / 2 <= layout.width / 2 + 1e-9);
    assert.ok(Math.abs(m.y) + m.h / 2 <= layout.height / 2 + 1e-9);
  }
  for (const e of layout.edges) {
    for (const k of ["x1", "y1", "c1x", "c1y", "c2x", "c2y", "x2", "y2", "mx", "my"])
      assert.ok(Number.isFinite(e[k]), `${k} must be finite`);
  }
}

test("two-way SCC keeps both directions and parallel-ref multiplicity", () => {
  const input = [member(0, [[1, 2]]), member(1, [[0, 1]])];
  const snapshot = structuredClone(input);
  const layout = computeSccLayout(input);
  assert.deepEqual(input, snapshot);
  assert.equal(layout.members.length, 2);
  assert.equal(layout.edges.length, 2);
  assert.deepEqual(layout.edges.map(e => [e.s, e.t, e.mult]), [[0, 1, 2], [1, 0, 1]]);
  assert.equal(layout.edges.filter(e => e.back).length, 1);
  assert.ok(layout.width > 0 && layout.height > 0);
  finiteGeometry(layout);
});

test("quotient group boxes carry ×n labels and the count", () => {
  const layout = computeSccLayout([
    { ...member(0, [[0, 1], [1, 1]]), count: 1500 },
    member(1, [[0, 1]]),
  ]);
  assert.equal(layout.members[0].short, "C0 ×1,500");
  assert.equal(layout.members[0].count, 1500);
  assert.equal(layout.members[1].short, "C1");
  assert.equal(layout.members[1].count, 1);
  finiteGeometry(layout);
});

test("self-loop is rendered as an explicit cyclic connection", () => {
  const layout = computeSccLayout([member(0, [[0, 3]])]);
  assert.equal(layout.edges.length, 1);
  assert.equal(layout.edges[0].self, true);
  assert.equal(layout.edges[0].back, true);
  assert.equal(layout.edges[0].mult, 3);
  assert.notEqual(layout.edges[0].y1, layout.edges[0].y2);
  finiteGeometry(layout);
});

test("deep SCC layout is iterative and retains every connection", () => {
  const count = 10000;
  const input = Array.from({ length: count }, (_, i) =>
    member(i, [[(i + 1) % count, 1]]));
  const layout = computeSccLayout(input);
  assert.equal(layout.members.length, count);
  assert.equal(layout.edges.length, count);
  assert.equal(layout.edges.filter(e => e.back).length, 1);
  finiteGeometry(layout);
});

test("empty SCC layout is safe", () => {
  const layout = computeSccLayout([]);
  assert.deepEqual(layout.members, []);
  assert.deepEqual(layout.edges, []);
  assert.equal(layout.width, 0);
  assert.equal(layout.height, 0);
});
