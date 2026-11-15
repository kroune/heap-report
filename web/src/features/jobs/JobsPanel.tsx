import { useLayoutEffect, useRef, useState } from 'react';
import {
  Alert, Box, Button, Chip, Collapse, IconButton, LinearProgress, Paper, Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CloseIcon from '@mui/icons-material/Close';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ScheduleIcon from '@mui/icons-material/Schedule';
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

const stateColors: Record<JobState, 'default' | 'primary' | 'success' | 'error'> = {
  queued: 'default', running: 'primary', done: 'success', failed: 'error', cancelled: 'default',
};

function JobStateIcon({ state }: { state: JobState }) {
  const className = `job-status-icon ${state}${state === 'running' ? ' is-spinning' : ''}`;
  if (state === 'done') return <CheckCircleOutlineIcon className={className} />;
  if (state === 'failed') return <ErrorOutlineIcon className={className} />;
  if (state === 'queued') return <ScheduleIcon className={className} />;
  if (state === 'cancelled') return <CloseIcon className={className} />;
  return <SyncIcon className={className} />;
}

interface JobLogProps {
  jobId: number;
  text: string;
  scrolls: { current: Map<string, number> };
}

function JobLog({ jobId, text, scrolls }: JobLogProps) {
  const element = useRef<HTMLPreElement | null>(null);
  const key = `${jobId}:full`;
  useLayoutEffect(() => {
    const top = scrolls.current.get(key);
    if (element.current && top !== undefined) element.current.scrollTop = top;
  }, [key, scrolls, text]);

  return <Box component="pre" ref={element} className="job-log"
    onScroll={(event) => scrolls.current.set(key, event.currentTarget.scrollTop)}>{text}</Box>;
}

const partDone = (part: ProgressPart) => part.done || (part.size != null && part.have >= part.size);
const isActive = (job: Job) => job.state === 'queued' || job.state === 'running';

export function JobsPanel({ jobs, isLoading = false, error, onClose }: JobsPanelProps) {
  const queryClient = useQueryClient();
  const initialTerminal = useRef<Set<number> | null>(null);
  const [dismissed, setDismissed] = useState<Set<number>>(() => new Set());
  const [expandedLogs, setExpandedLogs] = useState<Set<number>>(() => new Set());
  const [cancelErrors, setCancelErrors] = useState<Record<number, string>>({});
  const scrolls = useRef(new Map<string, number>());

  if (jobs !== undefined && initialTerminal.current === null) {
    initialTerminal.current = new Set(jobs.filter((job) => !isActive(job)).map((job) => job.id));
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
  const activeCount = visibleJobs.filter(isActive).length;
  const finishedCount = visibleJobs.length - activeCount;
  const dismiss = (id: number) => setDismissed((current) => new Set(current).add(id));
  const clearFinished = () => setDismissed((current) => {
    const next = new Set(current);
    visibleJobs.filter((job) => !isActive(job)).forEach((job) => next.add(job.id));
    return next;
  });
  const toggleLog = (id: number) => setExpandedLogs((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return <Box component="aside" aria-label="Background jobs"
    className={`jobs-tool-window${visibleJobs.length ? '' : ' empty'}`}>
    <Box className="jobs-window-header">
      <SyncIcon className={activeCount ? 'is-spinning' : undefined} />
      <Typography>Background tasks</Typography>
      <Chip size="small" variant="outlined" label={activeCount ? `${activeCount} active` : `${visibleJobs.length} recent`} />
      <Box flex={1} />
      {finishedCount > 0 && <Button size="small" onClick={clearFinished}>Clear finished</Button>}
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
          !visibleJobs.length ? <EmptyState>No background tasks in this session.</EmptyState> : <Box className="jobs-list" role="list">
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
                  ? `${formatBytes(progress.done)} / ${formatBytes(progress.total)} unpacked`
                  : `${formatBytes(progress.done)} / ${formatBytes(progress.total)}`;
                if (progress.speed) progressLabel += ` · ${formatBytes(Math.round(progress.speed))}/s`;
                if (progress.eta != null) progressLabel += ` · ETA ${formatDuration(progress.eta)}`;
                if (progress.stage === 'download' && progress.asm && progress.asm.total > 0) {
                  progressLabel += ` · ${formatBytes(progress.asm.done)} unpacked`;
                }
              }
              const parts = progress?.parts || [];
              const completedParts = parts.filter(partDone).length;
              const activeParts = parts.filter((part) => !partDone(part) && part.have > 0).map((part) =>
                `${part.n} ${part.size ? `${Math.floor(100 * part.have / part.size)}%` : formatBytes(part.have)}`);
              if (parts.length) progressLabel += ` · parts ${completedParts}/${parts.length}`;
              if (activeParts.length) progressLabel += ` · active ${activeParts.join(', ')}`;

              return <Box component="section" key={job.id} className={`job-row ${job.state}`} role="listitem">
                <Box className="job-main-line">
                  <JobStateIcon state={job.state} />
                  <Typography className="job-kind">{job.kind}</Typography>
                  <Typography className="job-title" title={title}>{title}</Typography>
                  {progress?.source && <Typography className="job-source">{formatSource(progress.source)}</Typography>}
                  <Chip size="small" variant="outlined" className="job-state-chip" color={stateColors[job.state]} label={job.state} />
                  <Box className="job-actions">
                    {isActive(job) && <Button size="small" color="error" disabled={cancelPending}
                      title="Cancel the job (partial downloads are purged)" onClick={() => cancelMutation.mutate(job.id)}>
                      {cancelPending ? 'Cancelling…' : 'Cancel'}
                    </Button>}
                    {log.length > 0 && <Button size="small" className="job-log-toggle" aria-expanded={expanded}
                      startIcon={<ExpandMoreIcon className={expanded ? 'expanded' : undefined} />} onClick={() => toggleLog(job.id)}>
                      {expanded ? 'Hide full log' : 'Show full log'} ({log.length} lines)
                    </Button>}
                    <IconButton size="small" aria-label={`Dismiss job ${job.id}`}
                      title="Dismiss from this list (the job keeps running)" onClick={() => dismiss(job.id)}>
                      <CloseIcon fontSize="small" />
                    </IconButton>
                  </Box>
                </Box>

                {progress && progress.total > 0 && <Box className="job-progress-line">
                  <Typography className="job-progress-percent numeric">{percent.toFixed(0)}%</Typography>
                  <LinearProgress variant="determinate" value={percent} aria-label={`Progress for job ${job.id}`} />
                  <Typography className="job-progress-label" title={progressLabel}>{progressLabel}</Typography>
                </Box>}
                {cancelErrors[job.id] && <Alert severity="error" className="job-inline-alert">{cancelErrors[job.id]}</Alert>}
                {job.error && <Alert severity="error" className="job-inline-alert">{job.error}</Alert>}
                {log.length > 0 && <Collapse in={expanded} unmountOnExit>
                  <JobLog jobId={job.id} text={log.join('\n')} scrolls={scrolls} />
                </Collapse>}
              </Box>;
            })}
          </Box>}
    </Box>
  </Box>;
}
