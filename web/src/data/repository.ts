import { api, apiDelete, apiPost } from './http';
import type {
  AnatomyPayload, ClassPage, ClassQuery, ComparePayload, CompositionPayload,
  DumpDataRepository, DumpInfo, Job, Result, ShapesPayload, SnapshotPayload, TreesPayload,
} from './types';
import { categoryOf } from './format';

const dumpPath = (id: string) => `/api/dumps/${encodeURIComponent(id)}`;

export const dumpRepository = {
  list: () => api<DumpInfo[]>('/api/dumps'),
  startDownload: (id: string) => apiPost<Job>(`${dumpPath(id)}/download`),
  cancelDownload: (id: string) => apiPost<{ id: string; cancelled: boolean }>(`${dumpPath(id)}/cancel`),
  delete: (id: string) => apiDelete<null>(dumpPath(id)),
  setTags: (id: string, tags: string[]) => apiPost<{ id: string; tags: string[] }>(`${dumpPath(id)}/tags`, { tags }),
  jobs: () => api<Job[]>('/api/jobs'),
  cancelJob: (id: number) => apiPost<{ id: number; cancelled: boolean }>(`/api/jobs/${id}/cancel`),
};

export const httpDataRepository: DumpDataRepository = {
  trees: (id) => api<TreesPayload>(`${dumpPath(id)}/trees`),
  classes: (id, { filter = '', sort = '-s', page = 0 }: ClassQuery = {}) => api<ClassPage>(
    `${dumpPath(id)}/classes?filter=${encodeURIComponent(filter)}&sort=${encodeURIComponent(sort)}&page=${page}`),
  composition: (id, className) => api<CompositionPayload>(`${dumpPath(id)}/composition?class=${encodeURIComponent(className)}`),
  anatomy: (id, className, { samples = null } = {}) => api<AnatomyPayload>(
    `${dumpPath(id)}/anatomy?class=${encodeURIComponent(className)}${samples ? `&samples=${samples}` : ''}`),
  compare: (a, b) => api<ComparePayload>(`/api/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`),
  analyze: (id, className, { samples = 8, anatomy = true } = {}) => apiPost<Job>(`${dumpPath(id)}/analyze`, {
    class: className, samples, anatomy,
  }),
  shapes: (id) => api<ShapesPayload>(`${dumpPath(id)}/shapes`),
  shapesAnalyze: (id) => apiPost<Job>(`${dumpPath(id)}/shapes-analyze`),
};

const snapshotError = <T>(): Result<T> => ({ ok: false, code: 'snapshot', error: 'static snapshot' });
const notAnalyzed = <T>(): Result<T> => ({ ok: false, status: 404, code: 'not_analyzed', error: 'not analyzed', data: { analyzed: false } });

export function makeInlineRepository(payload: SnapshotPayload): DumpDataRepository {
  const classes = payload.classes.map((row) => ({
    disp: row[0], c: row[1], s: row[2], pi: row[1] ? Math.round(row[2] / row[1] * 10) / 10 : 0,
    r: row[3], comp: Boolean(row[4]), anat: row[5] || [], lams: row[6] || null,
    analyzable: !row[0].endsWith('$$Lambda*'), name: row[0].split('.').pop() || row[0],
    pkg: row[0].includes('.') ? row[0].slice(0, row[0].lastIndexOf('.')) : '(no package)', cat: categoryOf(row[0]),
  }));

  return {
    trees: async () => ({ ok: true, data: { stats: payload.stats, trees: payload.trees } }),
    classes: async (_id, { filter = '', sort = '-s', page = 0 } = {}) => {
      const normalized = filter.trim().toLowerCase();
      const matching = normalized ? classes.filter((row) => row.disp.toLowerCase().includes(normalized)) : classes;
      const key = sort.replace('-', '');
      const sorted = [...matching].sort((a, b) => {
        if (key === 'name') return (sort.startsWith('-') ? -1 : 1) * a.disp.localeCompare(b.disp);
        if (key === 'r') return (a.r == null ? 1 : 0) - (b.r == null ? 1 : 0) || ((b.r || 0) - (a.r || 0));
        const av = a[key as keyof typeof a] as number;
        const bv = b[key as keyof typeof b] as number;
        return sort.startsWith('-') ? bv - av : av - bv;
      });
      const total = sorted.length;
      return { ok: true, data: { rows: sorted.slice(page * 200, page * 200 + 200), total, page, pages: Math.max(1, Math.ceil(total / 200)) } };
    },
    composition: async (_id, className) => payload.comps[className]
      ? { ok: true, data: payload.comps[className] } : notAnalyzed(),
    anatomy: async (_id, className, { samples = null } = {}) => {
      const anatomy = payload.anats[className];
      return anatomy && (samples == null || samples === anatomy.samples)
        ? { ok: true, data: anatomy } : notAnalyzed();
    },
    compare: async () => snapshotError(),
    analyze: async () => snapshotError(),
    shapes: async () => payload.shapes ? { ok: true, data: payload.shapes } : notAnalyzed(),
    shapesAnalyze: async () => snapshotError(),
  };
}
