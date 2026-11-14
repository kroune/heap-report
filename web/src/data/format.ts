import type { Category } from './types';

export const categoryOf = (name: string): Category =>
  name.startsWith('org.gradle') ? 'gradle'
    : name.startsWith('com.android') ? 'agp'
      : name.startsWith('org.jetbrains.kotlin') ? 'kotlin'
        : (name.startsWith('java.') || name.startsWith('jdk.') || name.startsWith('sun.')
          || name.startsWith('com.sun') || !name.includes('.')) ? 'jdk' : 'other';

export const categoryColors: Record<Category, string> = {
  gradle: '#e8743b', agp: '#3ba272', kotlin: '#9b7ede', jdk: '#4a90d9', other: '#7d8590',
};

export function formatBytes(value: number): string {
  const [unit, suffix] = value >= 1e9 ? [1e9, 'G'] : value >= 1e6 ? [1e6, 'M'] : value >= 1e3 ? [1e3, 'K'] : [1, 'B'];
  const scaled = value / unit;
  return `${scaled >= 100 ? Math.round(scaled) : Math.round(scaled * 10) / 10}${suffix}`;
}

export const formatNumber = (value: number) => Number(value).toLocaleString('en-US');

export function formatDuration(value: number): string {
  const seconds = Math.max(0, Math.round(value));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

export const formatSource = (source?: string) => source === 's3' ? 'S3' : source === 'github' ? 'GitHub' : source || '';
export const shortClassName = (name: string) => String(name).split('.').pop() || String(name);
export const formatSignedBytes = (value: number) => `${value > 0 ? '+' : value < 0 ? '−' : ''}${formatBytes(Math.abs(value))}`;
