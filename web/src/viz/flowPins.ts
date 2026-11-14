export const DEFAULT_PINS = [
  'java.lang.String', 'java.lang.Object[]', 'byte[]', 'char[]', 'short[]', 'int[]', 'long[]',
  'float[]', 'double[]', 'boolean[]', 'java.util.HashMap', 'java.util.LinkedHashMap',
  'java.util.concurrent.ConcurrentHashMap',
];
const STORAGE_KEY = 'heap-report.flow.pins.v1';

export function loadPins(): string[] {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
    if (Array.isArray(value) && value.every((pin) => typeof pin === 'string')) return value;
  } catch { /* Storage can be unavailable in sandboxed documents. */ }
  return [...DEFAULT_PINS];
}

export function savePins(pins: string[]) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(pins)); } catch { /* Storage is optional. */ }
}

export function parseBytes(value: string): number | null {
  const match = /^\s*(\d+(?:\.\d+)?)\s*([kmg])?b?\s*$/i.exec(value);
  if (!match) return null;
  const multiplier = { '': 1, k: 2 ** 10, m: 2 ** 20, g: 2 ** 30 }[(match[2] || '').toLowerCase()]!;
  return Math.round(parseFloat(match[1]) * multiplier);
}
