import { useLayoutEffect, useRef, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Chip, Collapse, IconButton,
  LinearProgress, Paper, Stack, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import SyncIcon from '@mui/icons-material/Sync';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { EmptyState, ErrorState, LoadingState } from '../../components/QueryState';
import { formatBytes, formatDuration, formatSource } from '../../data/format';
import { errorMessage, unwrap } from '../../data/http';
import { queryKeys } from '../../data/queries';
import { dumpRepository } from '../../data/repository';
import type { Job, JobState, ProgressPart } from '../../data/types';

export interface JobsPanelProps {
  jobs: Job[] | undefined;
  isLoading?: boolean;
  error?: unknown;
  onClose?: () => void;
}

const LOG_TAIL = 10;
const stateColors: Record<JobState, 'default' | 'primary' | 'success' | 'error'> = {
  queued: 'default', running: 'primary', done: 'success', failed: 'error', cancelled: 'default',
};
const borderColors: Record<JobState, string> = {
  queued: 'info.main', running: 'primary.main', done: 'success.main', failed: 'error.main', cancelled: 'divider',
};

interface JobLogProps {
  jobId: number;
  lane: 'tail' | 'full';
  text: string;
  maxHeight: number;
  scrolls: { current: Map<string, number> };
}

function JobLog({ jobId, lane, text, maxHeight, scrolls }: JobLogProps) {
  const element = useRef<HTMLPreElement | null>(null);
  const key = `${jobId}:${lane}`;
  useLayoutEffect(() => {
    const top = scrolls.current.get(key);
    if (element.current && top !== undefined) element.current.scrollTop = top;
  }, [key, scrolls, text]);

  return <Box component="pre" ref={element} onScroll={(event) => scrolls.current.set(key, event.currentTarget.scrollTop)}
    sx={{
      maxHeight, overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere',
      fontSize: 11, color: 'text.secondary', bgcolor: 'action.hover', borderRadius: 1,
      p: 1, mt: 1, mb: 0, fontFamily: 'monospace',
    }}>{text}</Box>;
}

const partDone = (part: ProgressPart) => part.done || (part.size != null && part.have >= part.size);

export function JobsPanel({ jobs, isLoading = false, error, onClose }: JobsPanelProps) {
  const queryClient = useQueryClient();
  const initialTerminal = useRef<Set<number> | null>(null);
  const [dismissed, setDismissed] = useState<Set<number>>(() => new Set());
  const [expandedLogs, setExpandedLogs] = useState<Set<number>>(() => new Set());
  const [cancelErrors, setCancelErrors] = useState<Record<number, string>>({});
  const scrolls = useRef(new Map<string, number>());

  if (jobs !== undefined && initialTerminal.current === null) {
    initialTerminal.current = new Set(jobs.filter((job) => job.state !== 'queued' && job.state !== 'running').map((job) => job.id));
  }

  const cancelMutation = useMutation({
    mutationFn: async (id: number) => unwrap(await dumpRepository.cancelJob(id)),
    onMutate: (id) => setCancelErrors((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    }),
    onError: (cancelError, id) => setCancelErrors((current) => ({
      ...current, [id]: errorMessage(cancelError) || 'cancel failed',
    })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.jobs }),
  });

  const visibleJobs = (jobs || []).filter((job) =>
    !initialTerminal.current?.has(job.id) && !dismissed.has(job.id));
  const dismiss = (id: number) => setDismissed((current) => new Set(current).add(id));
  const toggleLog = (id: number) => setExpandedLogs((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return <Box component="aside" aria-label="Background jobs" className="jobs-tool-window">
    <Box className="jobs-window-header">
      <SyncIcon />
      <Typography>Background tasks</Typography>
      <Chip size="small" variant="outlined" label={visibleJobs.length} />
      <Box flex={1} />
      {onClose && <IconButton aria-label="Hide background tasks" title="Hide background tasks" onClick={onClose}>
        <CloseIcon fontSize="small" />
      </IconButton>}
    </Box>
    <Box className="jobs-window-content">
    {error != null && jobs !== undefined && <Alert severity="warning" sx={{ mb: 1 }}>
      Could not refresh jobs: {errorMessage(error)}
    </Alert>}

    {isLoading && jobs === undefined ? <Paper><LoadingState>Loading jobs…</LoadingState></Paper> :
      error && jobs === undefined ? <Paper><ErrorState error={error} title="Unable to load jobs" /></Paper> :
        !visibleJobs.length ? <EmptyState>No background tasks in this session.</EmptyState> : <Stack spacing={1}>
          {visibleJobs.map((job) => {
            const progress = job.progress;
            const log = Array.isArray(job.log) ? job.log : [];
            const expanded = expandedLogs.has(job.id);
            const title = [job.dump, job.detail].filter(Boolean).join(' — ') || `job #${job.id}`;
            const cancelPending = cancelMutation.isPending && cancelMutation.variables === job.id;
            let progressLabel = '';
            let percent = 0;
            if (progress && progress.total > 0) {
              percent = Math.max(0, Math.min(100, 100 * progress.done / progress.total));
              progressLabel = progress.stage === 'assemble'
                ? `assembling — ${formatBytes(progress.done)} / ${formatBytes(progress.total)} unpacked (${percent.toFixed(0)}%)`
                : `${formatBytes(progress.done)} / ${formatBytes(progress.total)} (${percent.toFixed(0)}%)`;
              if (progress.speed) progressLabel += ` · ${formatBytes(Math.round(progress.speed))}/s`;
              if (progress.eta != null) progressLabel += ` · eta ${formatDuration(progress.eta)}`;
              if (progress.stage === 'download' && progress.asm && progress.asm.total > 0) {
                progressLabel += ` · ${formatBytes(progress.asm.done)} unpacked`;
              }
            }
            const parts = progress?.parts || [];
            const completedParts = parts.filter(partDone).length;
            const activeParts = parts.filter((part) => !partDone(part) && part.have > 0).map((part) =>
              `${part.n} ${part.size ? `${Math.floor(100 * part.have / part.size)}%` : formatBytes(part.have)}`);

            return <Card key={job.id} variant="outlined" className="job-entry"
              sx={{ borderLeft: 2, borderLeftColor: borderColors[job.state] }}>
              <CardContent sx={{ p: 1.5, '&:last-child': { pb: 1.5 } }}>
                <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap">
                  <Chip size="small" variant="outlined" label={job.kind} />
                  <Typography variant="body2" color="primary" flex={1} minWidth={100} sx={{ overflowWrap: 'anywhere' }}>
                    {title}
                  </Typography>
                  <Chip size="small" variant="outlined" color={stateColors[job.state]} label={job.state} />
                  <IconButton size="small" aria-label={`Dismiss job ${job.id}`}
                    title="dismiss (the job keeps running)" onClick={() => dismiss(job.id)}><CloseIcon fontSize="small" /></IconButton>
                </Stack>

                {(job.state === 'queued' || job.state === 'running') && <Box textAlign="right">
                  <Button size="small" color="error" disabled={cancelPending}
                    title="cancel the job (partial downloads are purged)" onClick={() => cancelMutation.mutate(job.id)}>
                    {cancelPending ? 'Cancelling…' : 'Cancel'}
                  </Button>
                </Box>}
                {cancelErrors[job.id] && <Alert severity="error" sx={{ mt: 1 }}>{cancelErrors[job.id]}</Alert>}

                {progress && progress.total > 0 && <Box mt={1}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <LinearProgress variant="determinate" value={percent} aria-label={`Progress for job ${job.id}`}
                      sx={{ flex: 1, height: 8, borderRadius: 1 }} />
                    {progress.source && <Chip size="small" color="primary" variant="outlined" label={formatSource(progress.source)} />}
                  </Stack>
                  <Typography variant="caption" color="text.secondary" component="div">{progressLabel}</Typography>
                  {parts.length > 0 && <Typography variant="caption" color="text.secondary" component="div">
                    parts {completedParts}/{parts.length}{activeParts.length ? ` · active: ${activeParts.join(', ')}` : ''}
                  </Typography>}
                </Box>}

                {job.error && <Alert severity="error" sx={{ mt: 1 }}>{job.error}</Alert>}
                {log.length > 0 && <>
                  <JobLog jobId={job.id} lane="tail" text={log.slice(-LOG_TAIL).join('\n')} maxHeight={130} scrolls={scrolls} />
                  {log.length > LOG_TAIL && <>
                    <Button size="small" aria-expanded={expanded} onClick={() => toggleLog(job.id)} sx={{ mt: 0.5, px: 0 }}>
                      {expanded ? 'Hide' : 'Show'} full log ({log.length} lines)
                    </Button>
                    <Collapse in={expanded}>
                      <JobLog jobId={job.id} lane="full" text={log.join('\n')} maxHeight={320} scrolls={scrolls} />
                    </Collapse>
                  </>}
                </>}
              </CardContent>
            </Card>;
          })}
        </Stack>}
    </Box>
  </Box>;
}
