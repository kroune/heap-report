import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Box, Button, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import GridViewIcon from '@mui/icons-material/GridView';
import MemoryIcon from '@mui/icons-material/Memory';
import RefreshIcon from '@mui/icons-material/Refresh';
import SyncIcon from '@mui/icons-material/Sync';
import TableRowsIcon from '@mui/icons-material/TableRows';
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

const tools = [
  { id: 'classes' as const, label: 'Classes', title: 'Class histogram', icon: TableRowsIcon },
  { id: 'treemap' as const, label: 'Treemap', title: 'Heap treemap', icon: GridViewIcon },
  { id: 'shapes' as const, label: 'Shapes', title: 'Object shapes', icon: AccountTreeIcon },
  { id: 'compare' as const, label: 'Compare', title: 'Dump comparison', icon: CompareArrowsIcon },
];

export function App() {
  const snapshot = window.__INLINE__;
  const inline = Boolean(snapshot);
  const repository = useMemo(() => snapshot ? makeInlineRepository(snapshot) : httpDataRepository, [snapshot]);
  const { currentDump, selectDump } = useCurrentDump(snapshot?.name);
  const dumpsQuery = useDumps(!inline);
  const jobsQuery = useJobs(!inline);
  const queryClient = useQueryClient();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [jobsOpen, setJobsOpen] = useState(false);
  const [tab, setTab] = useState<TabName>('classes');
  const [visitedTools, setVisitedTools] = useState<ReadonlySet<TabName>>(() => new Set(['classes']));
  const [viz, setViz] = useState<VizRequest | null>(null);
  const terminalDownloads = useRef(new Set<number>());

  const dumps = useMemo(() => dumpsQuery.data || [], [dumpsQuery.data]);
  const jobs = useMemo(() => jobsQuery.data || [], [jobsQuery.data]);
  const activeJobs = useMemo(() => jobs.filter((job) => job.state === 'queued' || job.state === 'running'), [jobs]);
  const availableTools = inline ? tools.filter((tool) => tool.id !== 'compare') : tools;
  const activeTool = tools.find((tool) => tool.id === tab)!;
  const activateTool = useCallback((next: TabName) => {
    setVisitedTools((current) => {
      if (current.has(next)) return current;
      const visited = new Set(current);
      visited.add(next);
      return visited;
    });
    setTab(next);
  }, []);

  useEffect(() => {
    if (inline || !dumps.length) return;
    if (!currentDump || !dumps.some((dump) => dump.id === currentDump)) {
      const first = dumps.find((dump) => dump.state === 'ready')
        || dumps.find((dump) => dump.state !== 'remote' && dump.state !== 'failed') || dumps[0];
      selectDump(first.id);
    }
  }, [dumps, currentDump, inline, selectDump]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (pickerOpen || viz) return;
      if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'o' && !inline) {
        event.preventDefault();
        setPickerOpen(true);
        return;
      }
      if (event.altKey && /^Digit[1-4]$/.test(event.code)) {
        const tool = availableTools[Number(event.code.slice(-1)) - 1];
        if (tool) {
          event.preventDefault();
          activateTool(tool.id);
        }
        return;
      }
      const target = event.target as HTMLElement | null;
      const editing = target?.matches('input, textarea, select') || target?.isContentEditable;
      if (event.key === '/' && !editing && tab === 'classes') {
        event.preventDefault();
        document.getElementById('class-filter')?.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activateTool, availableTools, inline, pickerOpen, tab, viz]);

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
      <Box className="workbench">
        <Box component="header" className="command-bar">
          <Stack direction="row" alignItems="center" className="product-identity">
            <Box className="product-mark"><MemoryIcon /></Box>
            <Typography component="h1">Heap Report</Typography>
          </Stack>

          <Box className="command-breadcrumb">
            <Typography className="project-name">Gradle sync-OOM</Typography>
            <ChevronRightIcon className="breadcrumb-separator" />
            <Button className="dump-switcher" onClick={() => setPickerOpen(true)} disabled={inline}
              startIcon={<FolderOpenIcon />} endIcon={!inline ? <ExpandMoreIcon /> : undefined}
              title="Open heap dump (Ctrl+Shift+O)">
              <span>{currentDump || 'Select heap dump'}</span>
            </Button>
            {selectedDump && <DumpStateChip state={selectedDump.state} />}
          </Box>

          <Stack direction="row" alignItems="center" className="command-actions">
            {inline && <Chip label="Offline snapshot" color="success" variant="outlined" size="small" />}
            {!inline && <Tooltip title="Refresh dump list">
              <span>
                <IconButton aria-label="Refresh dump list" disabled={dumpsQuery.isFetching}
                  onClick={() => queryClient.invalidateQueries({ queryKey: queryKeys.dumps })}>
                  <RefreshIcon className={dumpsQuery.isFetching ? 'is-spinning' : undefined} />
                </IconButton>
              </span>
            </Tooltip>}
          </Stack>
        </Box>

        <Box className="workbench-body">
          <Box component="nav" className="tool-strip" aria-label="Analysis tools">
            {availableTools.map((tool, index) => {
              const Icon = tool.icon;
              const selected = tab === tool.id;
              return <Tooltip key={tool.id} title={`${tool.label}  Alt+${index + 1}`} placement="right">
                <button type="button" className={`tool-strip-button${selected ? ' selected' : ''}`}
                  aria-label={`${tool.label} tool`} aria-pressed={selected} onClick={() => activateTool(tool.id)}>
                  <Icon />
                  <span>{tool.label}</span>
                </button>
              </Tooltip>;
            })}
            <Box className="tool-strip-spacer" />
            {!inline && <Tooltip title="Background tasks" placement="right">
              <button type="button" className={`tool-strip-button${jobsOpen ? ' selected' : ''}`}
                aria-label="Toggle background tasks" aria-pressed={jobsOpen} onClick={() => setJobsOpen((open) => !open)}>
                <SyncIcon className={activeJobs.length ? 'is-spinning' : undefined} />
                {activeJobs.length > 0 && <span className="tool-badge">{activeJobs.length}</span>}
              </button>
            </Tooltip>}
          </Box>

          <Box component="section" className="workspace" aria-label={`${activeTool.title} workspace`}>
            <Box className="editor-tabbar">
              <Box className="editor-tab selected">
                <activeTool.icon />
                <Typography>{activeTool.title}</Typography>
              </Box>
              <Box className="editor-tabbar-spacer" />
              <Typography className="editor-context">Eclipse MAT extracts</Typography>
            </Box>

            <Box component="main" className={`editor-content editor-${tab}`}>
              {!inline && dumpsQuery.isError && <Alert severity="warning" className="workspace-alert">
                Cannot load the dump list: {dumpsQuery.error.message}. The last successful list remains available.
              </Alert>}
              {!inline && dumpsQuery.isLoading ? <LoadingState>Discovering heap dumps…</LoadingState> : <>
                {visitedTools.has('classes') && <Box className="tool-view-host" sx={{ display: tab === 'classes' ? 'flex' : 'none' }}>
                  <ClassesTab dump={selectedDump} inline={inline} />
                </Box>}
                {visitedTools.has('treemap') && <Box className="tool-view-host" sx={{ display: tab === 'treemap' ? 'flex' : 'none' }}>
                  <TreemapTab dump={selectedDump} inline={inline} />
                </Box>}
                {visitedTools.has('shapes') && <Box className="tool-view-host" sx={{ display: tab === 'shapes' ? 'flex' : 'none' }}>
                  <ShapesTab dump={selectedDump} inline={inline} />
                </Box>}
                {visitedTools.has('compare') && !inline && <Box className="tool-view-host" sx={{ display: tab === 'compare' ? 'flex' : 'none' }}>
                  <CompareTab dumps={dumps} inline={inline} />
                </Box>}
              </>}
            </Box>

            {!inline && <Box sx={{ display: jobsOpen ? 'contents' : 'none' }}>
              <JobsPanel jobs={jobsQuery.data} isLoading={jobsQuery.isLoading}
                error={jobsQuery.error} onClose={() => setJobsOpen(false)} />
            </Box>}
          </Box>
        </Box>

        <Box component="footer" className="status-bar">
          <Stack direction="row" alignItems="center" className="status-section">
            <span className={`status-dot${selectedDump?.state === 'ready' ? ' ready' : ''}`} />
            <Typography>{selectedDump ? selectedDump.state : 'No dump selected'}</Typography>
            <span className="status-separator" />
            <Typography>{inline ? 'Offline snapshot' : 'Local repository'}</Typography>
          </Stack>
          <Stack direction="row" alignItems="center" className="status-section status-right">
            <Typography className="keyboard-hint"><kbd>/</kbd> filter <span>·</span> <kbd>Alt</kbd>+<kbd>1–4</kbd> tools</Typography>
            {!inline && <button type="button" className="status-action" onClick={() => setJobsOpen((open) => !open)}
              aria-expanded={jobsOpen}>
              <SyncIcon className={activeJobs.length ? 'is-spinning' : undefined} />
              {activeJobs.length ? `${activeJobs.length} active` : 'Background tasks'}
            </button>}
          </Stack>
        </Box>
      </Box>

      {!inline && <DumpPicker open={pickerOpen} onClose={() => setPickerOpen(false)} dumps={dumps}
        currentDump={currentDump} liveDownloads={liveDownloads}
        onSelect={(id) => { selectDump(id); setPickerOpen(false); }} />}
      {viz && <VisualizationDialog request={viz} inline={inline} onClose={() => setViz(null)}
        onKindChange={(kind) => setViz((current) => current ? { ...current, kind } : current)} />}
    </AppContext.Provider>
  </RepositoryProvider>;
}
