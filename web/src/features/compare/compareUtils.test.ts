import { describe, expect, it } from 'vitest';
import type { CompositionPayload } from '../../data/types';
import { rsDiffRows, sortRows, type ClassDeltaRow } from './compareUtils';

const row = (name: string, countDelta: number, shallowDelta: number): ClassDeltaRow =>
  [name, 0, countDelta, countDelta, 0, shallowDelta, shallowDelta];

describe('sortRows', () => {
  const rows = [
    row('small-increase', 1, 10),
    row('large-decrease', -8, -100),
    row('large-increase', 5, 80),
  ];

  it('sorts by absolute shallow delta by default without mutating the input', () => {
    const original = [...rows];
    expect(sortRows(rows, 'abs').map((entry) => entry[0])).toEqual(['large-decrease', 'large-increase', 'small-increase']);
    expect(sortRows(rows, 'unknown').map((entry) => entry[0])).toEqual(['large-decrease', 'large-increase', 'small-increase']);
    expect(rows).toEqual(original);
  });

  it('supports directional shallow and object-count sorts', () => {
    expect(sortRows(rows, 'down')[0][0]).toBe('large-decrease');
    expect(sortRows(rows, 'up')[0][0]).toBe('large-increase');
    expect(sortRows(rows, 'cdown')[0][0]).toBe('large-decrease');
    expect(sortRows(rows, 'cup')[0][0]).toBe('large-increase');
  });
});

describe('rsDiffRows', () => {
  it('merges classes present on either side and computes deltas', () => {
    const oldComposition: CompositionPayload = { rows: [['old.Only', 20, 2], ['both.Sides', 30, 3]] };
    const newComposition: CompositionPayload = { rows: [['new.Only', 50, 5], ['both.Sides', 10, 1]] };
    expect(rsDiffRows(oldComposition, newComposition)).toEqual([
      ['new.Only', 0, 50, 50, 0, 5],
      ['old.Only', 20, 0, -20, 2, 0],
      ['both.Sides', 30, 10, -20, 3, 1],
    ]);
  });

  it('normalizes and aggregates lambda specializations without replacement-string corruption', () => {
    const oldComposition: CompositionPayload = {
      rows: [
        ['example.Run$$Lambda+0x0000000000000001', 10, 1],
        ['example.Run$$Lambda+0x0000000000000002', 20, 2],
      ],
    };
    const newComposition: CompositionPayload = {
      rows: [['example.Run$$Lambda+0xabcdef', 40, 4]],
    };
    expect(rsDiffRows(oldComposition, newComposition)).toEqual([
      ['example.Run$$Lambda*', 30, 40, 10, 3, 4],
    ]);
  });

  it('handles missing compositions and sorts by absolute shallow delta', () => {
    expect(rsDiffRows(undefined, { rows: [['a.Small', 1, 1], ['a.Large', 10, 1]] })).toEqual([
      ['a.Large', 0, 10, 10, 0, 1],
      ['a.Small', 0, 1, 1, 0, 1],
    ]);
    expect(rsDiffRows(null, null)).toEqual([]);
  });
});
