export type DumpState = 'remote' | 'downloading' | 'assembling' | 'indexing' | 'ready' | 'failed';
export type JobState = 'queued' | 'running' | 'done' | 'failed' | 'cancelled';

export interface ProgressPart {
  n: number;
  have: number;
  size?: number;
  done?: boolean;
}

export interface JobProgress {
  done: number;
  total: number;
  stage?: 'download' | 'assemble';
  speed?: number;
  eta?: number;
  source?: 's3' | 'github';
  asm?: { done: number; total: number };
  parts?: ProgressPart[];
}

export interface DumpInfo {
  id: string;
  state: DumpState;
  source?: string;
  size?: number;
  error?: string;
  progress?: JobProgress | null;
  meta?: {
    tags?: string[];
    title?: string;
    created_at?: string;
    [key: string]: unknown;
  };
}

export interface Job {
  id: number;
  kind: string;
  dump?: string;
  detail?: string;
  state: JobState;
  progress?: JobProgress | null;
  log?: string[];
  error?: string;
}

export type Category = 'gradle' | 'agp' | 'kotlin' | 'jdk' | 'other';

export interface ClassRow {
  disp: string;
  name: string;
  pkg: string;
  cat: Category;
  c: number;
  s: number;
  pi: number;
  r: number | null;
  comp: boolean;
  anat: number[];
  lams: [string, number, number][] | null;
  analyzable: boolean;
}

export interface ClassPage {
  rows: ClassRow[];
  total: number;
  page: number;
  pages: number;
}

export interface ClassQuery {
  filter?: string;
  sort?: string;
  page?: number;
}

export interface HeapStats {
  totalRetained: number;
  totalShallow: number;
  totalObjects: number;
  classes?: number;
  analyzed?: number;
  modules?: number;
  buildFileBytes?: number;
  dump?: string;
}

export interface TreeNode {
  name: string;
  disp?: string;
  pkg?: string;
  cat?: Category;
  c?: number;
  s?: number;
  r?: number;
  leaf?: boolean;
  children?: TreeNode[];
}

export interface TreesPayload {
  stats: HeapStats;
  trees: { dom?: TreeNode; hist?: TreeNode };
}

export interface CompositionPayload {
  rows?: [string, number, number][];
  totalShallow?: number;
  totalObjects?: number;
  [key: string]: unknown;
}

export interface AnatomyPayload {
  samples?: number;
  [key: string]: unknown;
}

export interface ShapesPayload {
  shapes: unknown[];
  edges: unknown[];
  rootsHistogram?: unknown[];
  reachableCount?: number;
  analysis?: Record<string, unknown> | null;
  [key: string]: unknown;
}

export interface ComparePayload {
  old?: HeapStats;
  new?: HeapStats;
  waterfall?: Record<string, unknown>;
  proxies?: [string, number, number, number, number][];
  dom?: [string, number, number, number, number, number, number, number][];
  retained?: [string, number, number, number][];
  anats?: Record<string, {
    rows?: [string, number, number, number, number, number, number][];
    total: number;
    samples: [number, number];
  }>;
  rows?: [string, number, number, number, number, number, number][];
  analyzed?: { old: string[]; new: string[] };
  oldId?: string;
  newId?: string;
}

export interface SnapshotPayload {
  name: string;
  stats: HeapStats;
  trees: { dom?: TreeNode; hist?: TreeNode };
  classes: [string, number, number, number | null, number, number[] | null, [string, number, number][] | null][];
  comps: Record<string, CompositionPayload>;
  anats: Record<string, AnatomyPayload>;
  shapes: ShapesPayload | null;
}

export interface ApiSuccess<T> { ok: true; data: T }
export interface ApiFailure {
  ok: false;
  status?: number;
  code: string;
  error: string;
  data?: unknown;
}
export type Result<T> = ApiSuccess<T> | ApiFailure;

export interface DumpDataRepository {
  trees(id: string): Promise<Result<TreesPayload>>;
  classes(id: string, query?: ClassQuery): Promise<Result<ClassPage>>;
  composition(id: string, className: string): Promise<Result<CompositionPayload>>;
  anatomy(id: string, className: string, options?: { samples?: number | null }): Promise<Result<AnatomyPayload>>;
  compare(aId: string, bId: string): Promise<Result<ComparePayload>>;
  analyze(id: string, className: string, options?: { samples?: number; anatomy?: boolean }): Promise<Result<Job>>;
  shapes(id: string): Promise<Result<ShapesPayload>>;
  shapesAnalyze(id: string): Promise<Result<Job>>;
}
