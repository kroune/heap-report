import type { ComparePayload, CompositionPayload } from '../../data/types';

export type ClassDeltaRow = NonNullable<ComparePayload['rows']>[number];
export type RetainedSetDiffRow = [string, number, number, number, number, number];
export type CompareSortMode = 'abs' | 'down' | 'up' | 'cdown' | 'cup';

export const COMPARE_SORTS: { key: CompareSortMode; label: string }[] = [
  { key: 'abs', label: '|Δ shallow|' },
  { key: 'down', label: 'biggest decrease' },
  { key: 'up', label: 'biggest increase' },
  { key: 'cdown', label: 'count decrease' },
  { key: 'cup', label: 'count increase' },
];

/** Sort without changing the compare payload cached by TanStack Query. */
export function sortRows(rows: readonly ClassDeltaRow[], mode: CompareSortMode | string): ClassDeltaRow[] {
  const comparators: Record<CompareSortMode, (a: ClassDeltaRow, b: ClassDeltaRow) => number> = {
    abs: (a, b) => Math.abs(b[6]) - Math.abs(a[6]),
    down: (a, b) => a[6] - b[6],
    up: (a, b) => b[6] - a[6],
    cdown: (a, b) => a[3] - b[3],
    cup: (a, b) => b[3] - a[3],
  };
  return [...rows].sort(comparators[mode as CompareSortMode] || comparators.abs);
}

// The per-run address is noise; merge specializations just like the histogram does.
const LAMBDA_SPECIALIZATION = /\$\$Lambda\+0x[0-9a-f]+$/;

/**
 * Merge two composition payloads into
 * [name, oldShallow, newShallow, deltaShallow, oldObjects, newObjects].
 */
export function rsDiffRows(
  oldComposition: CompositionPayload | null | undefined,
  newComposition: CompositionPayload | null | undefined,
): RetainedSetDiffRow[] {
  const merged = new Map<string, [number, number, number, number]>();
  const put = (row: [string, number, number], side: 0 | 1) => {
    // A function replacement matters: '$$' in a replacement string is a literal '$'.
    const name = String(row[0]).replace(LAMBDA_SPECIALIZATION, () => '$$Lambda*');
    const entry = merged.get(name) || [0, 0, 0, 0];
    entry[side] += row[1];
    entry[side + 2] += row[2];
    merged.set(name, entry);
  };
  for (const row of oldComposition?.rows || []) put(row, 0);
  for (const row of newComposition?.rows || []) put(row, 1);

  return [...merged.entries()]
    .map(([name, entry]) => [name, entry[0], entry[1], entry[1] - entry[0], entry[2], entry[3]] as RetainedSetDiffRow)
    .sort((a, b) => Math.abs(b[3]) - Math.abs(a[3]));
}
