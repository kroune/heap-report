import { describe, expect, it, vi } from 'vitest';
import { makeInlineRepository } from './repository';
import type { SnapshotPayload } from './types';

const payload: SnapshotPayload = {
  name: 'snapshot-run',
  stats: { totalRetained: 30, totalShallow: 20, totalObjects: 2, modules: 1 },
  trees: { hist: { name: 'root', children: [] }, dom: { name: 'root', children: [] } },
  classes: [
    ['example.Beta', 2, 20, null, 0, [], null],
    ['example.Alpha', 1, 10, 100, 1, [8], null],
  ],
  comps: { 'example.Alpha': { rows: [], totalShallow: 100, totalObjects: 1 } },
  anats: { 'example.Alpha': { samples: 8 } },
  shapes: null,
};

describe('inline repository', () => {
  it('maps, filters and pages class rows without a server', async () => {
    const repo = makeInlineRepository(payload);
    const all = await repo.classes('snapshot-run', { sort: 'name', page: 0 });
    expect(all.ok && all.data.rows.map((row) => row.disp)).toEqual(['example.Alpha', 'example.Beta']);
    expect(all.ok && all.data.rows[0]).toMatchObject({ pkg: 'example', cat: 'other', comp: true, analyzable: true });

    const filtered = await repo.classes('snapshot-run', { filter: 'beta', sort: '-s', page: 0 });
    expect(filtered.ok && filtered.data).toMatchObject({ total: 1, pages: 1 });
    expect(filtered.ok && filtered.data.rows[0].disp).toBe('example.Beta');
  });

  it('serves trees and only the inlined anatomy extraction', async () => {
    const repo = makeInlineRepository(payload);
    expect((await repo.trees('snapshot-run')).ok).toBe(true);
    expect((await repo.anatomy('snapshot-run', 'example.Alpha')).ok).toBe(true);
    expect((await repo.anatomy('snapshot-run', 'example.Alpha', { samples: 8 })).ok).toBe(true);
    expect(await repo.anatomy('snapshot-run', 'example.Alpha', { samples: 16 })).toMatchObject({ ok: false, status: 404 });
    expect(await repo.shapes('snapshot-run')).toMatchObject({ ok: false, status: 404 });
  });

  it('never calls fetch for snapshot reads or server-only operations', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const repo = makeInlineRepository(payload);
    await repo.trees('snapshot-run');
    await repo.compare('a', 'b');
    await repo.analyze('snapshot-run', 'example.Alpha');
    await repo.shapesAnalyze('snapshot-run');
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
