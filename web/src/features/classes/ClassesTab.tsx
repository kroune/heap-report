import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TableSortLabel,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useApp } from '../../app/AppContext';
import { categoryColors, categoryOf, formatBytes, formatNumber, shortClassName } from '../../data/format';
import { errorMessage, unwrap } from '../../data/http';
import { queryKeys, useClasses, useJobs } from '../../data/queries';
import { useRepository } from '../../data/RepositoryContext';
import type { ClassRow, DumpInfo } from '../../data/types';

interface ClassesTabProps {
  dump: DumpInfo | null;
  inline: boolean;
}

interface AnalyzeRequest {
  dumpId: string;
  className: string;
}

interface PendingAnalysis extends AnalyzeRequest {
  jobId: number;
}

const SORTABLE_COLUMNS = [
  { key: 'name', label: 'class', numeric: false },
  { key: 'c', label: 'objects', numeric: true },
  { key: 's', label: 'shallow', numeric: true },
  { key: 'pi', label: 'per inst', numeric: true },
  { key: 'r', label: 'retained', numeric: true },
] as const;

const TERMINAL_JOB_STATES = new Set(['done', 'failed', 'cancelled']);

function classDisplayParts(row: ClassRow) {
  const display = String(row.disp || '');
  return {
    display,
    name: row.name || shortClassName(display),
    pkg: row.pkg || (display.includes('.') ? display.slice(0, display.lastIndexOf('.')) : '(no package)'),
    category: row.cat || categoryOf(display),
  };
}

export function ClassesTab({ dump, inline }: ClassesTabProps) {
  const repository = useRepository();
  const queryClient = useQueryClient();
  const { openViz } = useApp();
  const dumpId = dump?.id ?? null;

  const [controlsDump, setControlsDump] = useState(dumpId);
  const [filterInput, setFilterInput] = useState('');
  const [filter, setFilter] = useState('');
  const [sort, setSort] = useState('-s');
  const [page, setPage] = useState(0);
  const [expandedLambdas, setExpandedLambdas] = useState<Set<string>>(() => new Set());
  const [note, setNote] = useState<string | null>(null);
  const [pendingJobs, setPendingJobs] = useState<PendingAnalysis[]>([]);
  const [actualDataDump, setActualDataDump] = useState<string | null>(null);

  const controlsAreCurrent = controlsDump === dumpId;
  const visibleFilterInput = controlsAreCurrent ? filterInput : '';
  const visibleFilter = controlsAreCurrent ? filter : '';
  const visibleSort = controlsAreCurrent ? sort : '-s';
  const visiblePage = controlsAreCurrent ? page : 0;

  useEffect(() => {
    if (controlsDump === dumpId) return;
    setControlsDump(dumpId);
    setFilterInput('');
    setFilter('');
    setSort('-s');
    setPage(0);
    setExpandedLambdas(new Set());
    setNote(null);
  }, [controlsDump, dumpId]);

  useEffect(() => {
    if (!controlsAreCurrent) return;
    const timer = window.setTimeout(() => {
      setFilter(filterInput.trim().toLowerCase());
      setPage(0);
    }, 180);
    return () => window.clearTimeout(timer);
  }, [controlsAreCurrent, filterInput]);

  const classQuery = useMemo(() => ({
    filter: visibleFilter,
    sort: visibleSort,
    page: visiblePage,
  }), [visibleFilter, visiblePage, visibleSort]);

  const unavailable = dump?.state === 'failed' || dump?.state === 'remote';
  const classesQuery = useClasses(dumpId, classQuery, Boolean(dumpId) && !unavailable);
  const jobsQuery = useJobs(pendingJobs.length > 0);

  useEffect(() => {
    if (dumpId && classesQuery.data && !classesQuery.isPlaceholderData) setActualDataDump(dumpId);
  }, [classesQuery.data, classesQuery.isPlaceholderData, dumpId]);

  const analyzeMutation = useMutation({
    mutationFn: async ({ dumpId: id, className }: AnalyzeRequest) =>
      unwrap(await repository.analyze(id, className)),
    onMutate: ({ className }) => setNote(`queuing analysis of ${className}…`),
    onSuccess: (job, { dumpId: id, className }) => {
      if (job.state === 'failed') {
        setNote(`analysis of ${className} failed: ${job.error || 'see jobs panel'}`);
        return;
      }
      if (job.state === 'cancelled') {
        setNote(`analysis of ${className} was cancelled.`);
        return;
      }
      if (job.id != null && job.state !== 'done') {
        setPendingJobs((current) => [
          ...current.filter((pending) => pending.jobId !== job.id),
          { jobId: job.id, dumpId: id, className },
        ]);
        setNote(`analysis of ${className} queued — progress in the jobs panel.`);
        return;
      }
      void queryClient.invalidateQueries({ queryKey: ['classes', id] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.composition(id, className) });
      setNote('analysis finished — table refreshed.');
    },
    onError: (error) => setNote(`analyze failed: ${errorMessage(error)}`),
  });

  useEffect(() => {
    const jobs = jobsQuery.data;
    if (!jobs || pendingJobs.length === 0) return;
    const jobsById = new Map(jobs.map((job) => [job.id, job]));
    const terminal = pendingJobs.filter((pending) => {
      const job = jobsById.get(pending.jobId);
      return job != null && TERMINAL_JOB_STATES.has(job.state);
    });
    if (terminal.length === 0) return;

    setPendingJobs((current) => current.filter((pending) => {
      const job = jobsById.get(pending.jobId);
      return job == null || !TERMINAL_JOB_STATES.has(job.state);
    }));

    let currentDumpFinished = false;
    for (const pending of terminal) {
      const job = jobsById.get(pending.jobId)!;
      if (job.state === 'done') {
        void queryClient.invalidateQueries({ queryKey: ['classes', pending.dumpId] });
        void queryClient.invalidateQueries({ queryKey: queryKeys.composition(pending.dumpId, pending.className) });
        currentDumpFinished ||= pending.dumpId === dumpId;
      } else if (pending.dumpId === dumpId) {
        setNote(job.state === 'failed'
          ? `analysis of ${pending.className} failed: ${job.error || 'see jobs panel'}`
          : `analysis of ${pending.className} was cancelled.`);
      }
    }
    if (currentDumpFinished && !terminal.some((pending) => {
      const job = jobsById.get(pending.jobId);
      return pending.dumpId === dumpId && job?.state === 'failed';
    })) setNote('analysis finished — table refreshed.');
  }, [dumpId, jobsQuery.data, pendingJobs, queryClient]);

  if (!dump) {
    return <Alert severity="info">no dump selected — pick a dump in the selector above.</Alert>;
  }
  if (dump.state === 'failed' || dump.state === 'remote') {
    return <Alert severity={dump.state === 'failed' ? 'error' : 'warning'}>
      dump &quot;{dump.id}&quot; is {dump.state}{dump.error ? ` — ${dump.error}` : ''}.
    </Alert>;
  }

  // A placeholder from another dump must not masquerade as the newly selected dump.
  const pageData = classesQuery.isPlaceholderData && actualDataDump !== dumpId ? undefined : classesQuery.data;
  const rows = pageData?.rows || [];
  const total = pageData?.total || 0;
  const pages = Math.max(1, pageData?.pages || 1);
  const loadingFirstPage = !pageData && !classesQuery.isError;

  const changeSort = (key: string) => {
    const active = visibleSort === key || visibleSort === `-${key}`;
    setSort(active ? (visibleSort.startsWith('-') ? key : `-${key}`) : key === 'name' ? key : `-${key}`);
    setPage(0);
  };

  const toggleLambdas = (className: string) => {
    setExpandedLambdas((current) => {
      const next = new Set(current);
      if (next.has(className)) next.delete(className);
      else next.add(className);
      return next;
    });
  };

  const startAnalysis = (className: string) => {
    if (!inline) analyzeMutation.mutate({ dumpId: dump.id, className });
  };

  return <Stack spacing={1.5}>
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}>
        <TextField
          size="small"
          label="Filter classes"
          placeholder="filter classes, e.g. CachePolicy or com.android…"
          value={visibleFilterInput}
          onChange={(event) => setFilterInput(event.target.value)}
          sx={{ minWidth: { xs: '100%', sm: 340 } }}
        />
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>
          retained/per-instance appear once a class is analyzed (click Analyze or open a viz).{' '}
          Lambda specializations are merged into $$Lambda* families.
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ whiteSpace: 'nowrap' }}>
          <Typography variant="body2">{loadingFirstPage ? '…' : `${formatNumber(total)} classes`}</Typography>
          <IconButton
            aria-label="previous class page"
            disabled={visiblePage <= 0 || !pageData}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >‹</IconButton>
          <Typography variant="body2" className="numeric">{pageData ? `${visiblePage + 1}/${pages}` : ''}</Typography>
          <IconButton
            aria-label="next class page"
            disabled={!pageData || visiblePage >= pages - 1}
            onClick={() => setPage((current) => Math.min(pages - 1, current + 1))}
          >›</IconButton>
        </Stack>
      </Stack>
      {note && <Typography variant="body2" color="text.secondary" role="status" sx={{ mt: 1 }}>{note}</Typography>}
    </Paper>

    <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 'calc(100vh - 260px)', minHeight: 240 }}>
      <Table stickyHeader size="small" aria-label="Classes" sx={{ opacity: classesQuery.isFetching && pageData ? 0.55 : 1 }}>
        <TableHead>
          <TableRow>
            {SORTABLE_COLUMNS.map((column) => <TableCell key={column.key} align={column.numeric ? 'right' : 'left'}>
              <TableSortLabel
                active={visibleSort === column.key || visibleSort === `-${column.key}`}
                direction={visibleSort.startsWith('-') ? 'desc' : 'asc'}
                onClick={() => changeSort(column.key)}
              >{column.label}</TableSortLabel>
            </TableCell>)}
            <TableCell align="right">per inst</TableCell>
            <TableCell>analysis</TableCell>
            <TableCell align="right">actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {loadingFirstPage && Array.from({ length: 12 }, (_, index) => <TableRow key={index}>
            {Array.from({ length: 8 }, (_, cell) => <TableCell key={cell}><Skeleton /></TableCell>)}
          </TableRow>)}
          {classesQuery.isError && <TableRow><TableCell colSpan={8}>
            <Alert severity="error">{dump.id}: {errorMessage(classesQuery.error)}</Alert>
          </TableCell></TableRow>}
          {!loadingFirstPage && !classesQuery.isError && total === 0 && <TableRow>
            <TableCell colSpan={8} align="center" sx={{ py: 5, color: 'text.secondary' }}>
              no classes match the current filter.
            </TableCell>
          </TableRow>}
          {!classesQuery.isError && rows.map((row) => {
            const { display, name, pkg, category } = classDisplayParts(row);
            const expanded = expandedLambdas.has(display);
            const hasAnalysis = row.comp || (row.anat || []).length > 0;
            const queued = pendingJobs.some((pending) => pending.dumpId === dump.id && pending.className === display);
            return [
              <TableRow key={display} hover>
                <TableCell className="class-name">
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Box component="span" title={category} sx={{
                      width: 9, height: 9, borderRadius: '50%', flex: '0 0 auto', backgroundColor: categoryColors[category],
                    }} />
                    <Tooltip title={pkg}><Button
                      variant="text"
                      size="small"
                      onClick={() => openViz('anatomy', dump.id, display)}
                      sx={{ p: 0, minWidth: 0, color: 'text.primary', textTransform: 'none', fontWeight: 400 }}
                    >{name}</Button></Tooltip>
                    {row.lams && <Button size="small" variant="text" aria-expanded={expanded} onClick={() => toggleLambdas(display)}>
                      {expanded ? '▾' : '▸'} {row.lams.length}
                    </Button>}
                  </Stack>
                </TableCell>
                <TableCell align="right" className="numeric">{formatNumber(row.c || 0)}</TableCell>
                <TableCell align="right" className="numeric">{formatBytes(row.s || 0)}</TableCell>
                <TableCell align="right" className="numeric">{formatBytes(row.pi || 0)}</TableCell>
                <TableCell align="right" className="numeric">{row.r != null ? formatBytes(row.r) : '—'}</TableCell>
                <TableCell align="right" className="numeric">
                  {row.r != null ? formatBytes(row.r / Math.max(1, row.c || 1)) : '—'}
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    {row.comp && <Chip size="small" color="primary" variant="outlined" label="rs" title="retained-set composition available" />}
                    {(row.anat || []).map((samples) => <Chip
                      key={samples} size="small" color="primary" variant="outlined"
                      label={`a${samples}`} title={`anatomy extracted with ${samples} samples`}
                    />)}
                  </Stack>
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Button size="small" onClick={() => openViz('anatomy', dump.id, display)}
                    title="open visualizations for this class (switch kinds inside the popup)">viz ▸</Button>
                  {!inline && row.analyzable && !hasAnalysis && <Button
                    size="small"
                    variant="outlined"
                    disabled={analyzeMutation.isPending || queued}
                    onClick={() => startAnalysis(display)}
                  >{queued ? 'Queued' : 'Analyze'}</Button>}
                </TableCell>
              </TableRow>,
              expanded && row.lams ? <TableRow key={`${display}:lambdas`} sx={{ backgroundColor: 'action.hover' }}>
                <TableCell colSpan={8}>
                  <Stack spacing={0.5} sx={{ pl: 4 }}>
                    {row.lams.map((lambda) => <Typography key={lambda[0]} variant="body2" className="class-name">
                      {lambda[0]} — {formatNumber(lambda[1])} objs · {formatBytes(lambda[2])}
                    </Typography>)}
                  </Stack>
                </TableCell>
              </TableRow> : null,
            ];
          })}
        </TableBody>
      </Table>
    </TableContainer>
  </Stack>;
}

export default ClassesTab;
