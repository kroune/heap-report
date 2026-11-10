import test from "node:test";
import assert from "node:assert/strict";
import { prepare, shapesViewModel } from "../../web/viz/shapes/prepare.js";

function payload() {
  return {
    shapes: [{ id: 0, label: "org.example.Widget", kind: "leaf", count: 2,
      shallowTotal: 64, shallowMin: 32, shallowMax: 32, roots: 0,
      sccSize: 1, internalEdges: 0, edgesPerMember: true }],
    edges: [],
    rootsHistogram: [],
    reachableCount: 2,
    analysis: null,
  };
}

test("prepare passes through request errors", async () => {
  const vm = await prepare({ shapes: async () => ({ ok: false, status: 500, error: "broken" }) },
    "dump", { metric: "count" });
  assert.deepEqual(vm, { kind: "shapes", dumpId: "dump", error: "broken" });
});

test("404 and empty payloads preserve view params", async () => {
  const params = { layoutMode: "compact" };
  const missing = await prepare({ shapes: async () => ({ ok: false, status: 404 }) },
    "d", params);
  assert.equal(missing.notAnalyzed, true);
  assert.equal(missing.layoutMode, "compact");

  const empty = await prepare({ shapes: async () => ({ ok: true, data: { shapes: [] } }) },
    "d", params);
  assert.equal(empty.notAnalyzed, true);
  assert.equal(empty.layoutMode, "compact");
});

test("single-node payload tolerates omitted optional collections", async () => {
  const data = payload();
  delete data.edges;
  delete data.rootsHistogram;
  let call;
  const vm = await prepare({ shapes: async (...args) => {
    call = args;
    return { ok: true, data };
  } }, "dump");
  assert.deepEqual(call, ["dump"]);
  assert.equal(vm.notAnalyzed, undefined);
  assert.equal(vm.shapeCount, 1);
  assert.equal(vm.metric, "shallow");
  assert.equal(vm.layoutMode, "auto");
  assert.equal(vm.layout.layoutMode, "dag");
  assert.equal(vm.nodes[0].short, "Widget");
  assert.equal(vm.nodes[0].plain, true);
  assert.deepEqual(vm.layout.edges, []);
});

test("artifact analysis metadata is preserved", () => {
  const data = payload();
  data.analysis = { extractionMode: { type: "heap" } };
  const vm = shapesViewModel(data, "d", { metric: "count" });
  assert.equal(vm.metric, "count");
  assert.deepEqual(vm.analysis, data.analysis);
});

test("explicit layout mode reaches the pure layout", () => {
  const vm = shapesViewModel(payload(), "d", { layoutMode: "compact" });
  assert.equal(vm.layoutMode, "compact");
  assert.equal(vm.layout.layoutMode, "compact");
});

test("view-model mapping preserves SCC full labels and compact canvas labels", () => {
  const data = payload();
  data.shapes[0] = { ...data.shapes[0], kind: "scc",
    label: "scc[org.example.A,org.example.B]", sccSize: 2, edgesPerMember: false };
  const before = structuredClone(data);
  const vm = shapesViewModel(data, "d");
  assert.deepEqual(data, before);
  assert.equal(vm.nodes[0].label, "scc[org.example.A,org.example.B]");
  assert.equal(vm.nodes[0].short, "scc[A +1]");
  assert.equal(vm.nodes[0].plain, false);
  assert.equal(vm.nodes[0].sccComplete, false, "legacy SCCs have no member graph");
});

test("SCC member graphs retain labels, colors and exact internal connections", () => {
  const data = payload();
  data.shapes[0] = { ...data.shapes[0], kind: "scc",
    label: "scc[org.example.A,org.example.B]", sccSize: 2, internalEdges: 3,
    membersComplete: true,
    members: [
      { label: "org.example.A", internal: [[1, 2]] },
      { label: "org.example.B", internal: [[0, 1]] },
    ] };
  const before = structuredClone(data);
  const vm = shapesViewModel(data, "d");
  assert.deepEqual(data, before);
  assert.equal(vm.nodes[0].sccComplete, true);
  assert.deepEqual(vm.nodes[0].members.map(m => m.short), ["A", "B"]);
  assert.deepEqual(vm.nodes[0].members[0].internal, [[1, 2]]);
  assert.equal(vm.layout.nodes[0].scc.edges.length, 2);
  assert.equal(vm.layout.nodes[0].scc.edges[0].mult, 2);
});
