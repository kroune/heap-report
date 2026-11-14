import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, AppBar, Box, Button, Chip, Container, Tab, Tabs, Toolbar, Typography,
} from '@mui/material';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import { useQueryClient } from '@tanstack/react-query';
import { AppContext, type VisualizationKind } from './AppContext';
import { useCurrentDump } from './useCurrentDump';
import { DumpStateChip } from '../components/DumpStateChip';
import { LoadingState } from '../components/QueryState';
import { RepositoryProvider } from '../data/RepositoryContext';
import { makeInlineRepository, httpDataRepository } from '../data/repository';
import { queryKeys, useDumps, useJobs } from '../data/queries';
import type { DumpInfo } from '../data/types';
import { DumpPicker } from '../features/dumps/DumpPicker';
import { JobsPanel } from '../features/jobs/JobsPanel';
import { ClassesTab } from '../features/classes/ClassesTab';
import { TreemapTab } from '../features/treemap/TreemapTab';
import { CompareTab } from '../features/compare/CompareTab';
import { ShapesTab } from '../features/shapes/ShapesTab';
import { VisualizationDialog } from '../viz/VisualizationDialog';

interface VizRequest { kind: VisualizationKind; dumpId: string; className: string }
type TabName = 'classes' | 'treemap' | 'shapes' | 'compare';

export function App() {
  const snapshot = window.__INLINE__;
  const inline = Boolean(snapshot);
  const repository = useMemo(() => snapshot ? makeInlineRepository(snapshot) : httpDataRepository, [snapshot]);
  const { currentDump, selectDump } = useCurrentDump(snapshot?.name);
  const dumpsQuery = useDumps(!inline);
  const jobsQuery = useJobs(!inline);
  const queryClient = useQueryClient();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [tab, setTab] = useState<TabName>('classes');
  const [viz, setViz] = useState<VizRequest | null>(null);
  const terminalDownloads = useRef(new Set<number>());

  const dumps = useMemo(() => dumpsQuery.data || [], [dumpsQuery.data]);
  const jobs = useMemo(() => jobsQuery.data || [], [jobsQuery.data]);

  useEffect(() => {
    if (inline || !dumps.length) return;
    if (!currentDump || !dumps.some((dump) => dump.id === currentDump)) {
      const first = dumps.find((dump) => dump.state === 'ready')
        || dumps.find((dump) => dump.state !== 'remote' && dump.state !== 'failed') || dumps[0];
      selectDump(first.id);
    }
  }, [dumps, currentDump, inline, selectDump]);

  useEffect(() => {
    let downloadsFinished = false;
    for (const job of jobs) {
      if (job.kind !== 'download') continue;
      if ((job.state === 'done' || job.state === 'failed') && !terminalDownloads.current.has(job.id)) {
        terminalDownloads.current.add(job.id);
        downloadsFinished = true;
      }
    }
    if (downloadsFinished) queryClient.invalidateQueries({ queryKey: queryKeys.dumps });
  }, [jobs, queryClient]);

  const liveDownloads = useMemo(() => new Set(jobs.filter((job) =>
    job.kind === 'download' && (job.state === 'queued' || job.state === 'running') && job.dump,
  ).map((job) => job.dump!)), [jobs]);

  const selectedDump: DumpInfo | null = snapshot ? {
    id: snapshot.name, state: 'ready', meta: { title: snapshot.name },
  } : dumps.find((dump) => dump.id === currentDump) || null;

  const openViz = useCallback((kind: VisualizationKind, dumpId: string, className: string) => {
    setViz({ kind, dumpId, className });
  }, []);
  const context = useMemo(() => ({ inline, openViz }), [inline, openViz]);

  return <RepositoryProvider repository={repository}>
    <AppContext.Provider value={context}>
      <AppBar position="static" color="transparent" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 2, flexWrap: 'wrap', py: 1 }}>
          <Box sx={{ mr: 'auto' }}>
            <Typography variant="h5" component="h1">Heap report</Typography>
            <Typography variant="body2" color="text.secondary">Gradle sync-OOM benchmark · Eclipse MAT extracts</Typography>
          </Box>
          <Button variant="outlined" startIcon={<FolderOpenIcon />} onClick={() => setPickerOpen(true)}
            disabled={inline} sx={{ fontFamily: 'monospace' }}>
            {currentDump || 'Select dump'}
          </Button>
          {selectedDump && <DumpStateChip state={selectedDump.state} />}
          {inline && <Chip label="snapshot" color="success" variant="outlined" />}
        </Toolbar>
      </AppBar>

      {!inline && dumpsQuery.isError && <Alert severity="warning" sx={{ m: 2 }}>
        Cannot load the dump list: {dumpsQuery.error.message}. The last successful list remains available.
      </Alert>}

      <Tabs value={tab} onChange={(_event, value: TabName) => setTab(value)} sx={{ px: 3, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="classes" label="Classes" />
        <Tab value="treemap" label="Treemap" />
        <Tab value="shapes" label="Shapes" />
        {!inline && <Tab value="compare" label="Compare" />}
      </Tabs>

      <Container maxWidth={false} sx={{ px: { xs: 1, md: 3 }, py: 2 }}>
        {!inline && dumpsQuery.isLoading ? <LoadingState>Discovering heap dumps…</LoadingState> : <>
          {tab === 'classes' && <ClassesTab dump={selectedDump} inline={inline} />}
          {tab === 'treemap' && <TreemapTab dump={selectedDump} inline={inline} />}
          {tab === 'shapes' && <ShapesTab dump={selectedDump} inline={inline} />}
          {tab === 'compare' && !inline && <CompareTab dumps={dumps} inline={inline} />}
        </>}
      </Container>

      {!inline && <>
        <DumpPicker open={pickerOpen} onClose={() => setPickerOpen(false)} dumps={dumps}
          currentDump={currentDump} liveDownloads={liveDownloads}
          onSelect={(id) => { selectDump(id); setPickerOpen(false); }} />
        <JobsPanel jobs={jobsQuery.data} isLoading={jobsQuery.isLoading} error={jobsQuery.error} />
      </>}
      {viz && <VisualizationDialog request={viz} inline={inline} onClose={() => setViz(null)}
        onKindChange={(kind) => setViz((current) => current ? { ...current, kind } : current)} />}
    </AppContext.Provider>
  </RepositoryProvider>;
}
