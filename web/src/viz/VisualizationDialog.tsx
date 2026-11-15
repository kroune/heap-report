import { useCallback, useEffect, useRef, useState } from 'react';
import { Box, Dialog, DialogContent, DialogTitle, IconButton, Stack, Tab, Tabs, Typography } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { VisualizationKind } from '../app/AppContext';
import { ErrorState, LoadingState } from '../components/QueryState';
import { errorMessage } from '../data/http';
import { useJobs } from '../data/queries';
import { useRepository } from '../data/RepositoryContext';
import { AnatomyView } from './AnatomyView';
import { HierarchyView } from './HierarchyView';
import { ReferenceGraphView } from './ReferenceGraphView';
import { prepareVisualization } from './prepare';
import type { VizParams } from './types';

const kinds: VisualizationKind[] = ['anatomy', 'hierarchy', 'graph', 'flow'];

export function VisualizationDialog({ request, inline, onClose, onKindChange }: {
  request: { kind: VisualizationKind; dumpId: string; className: string };
  inline: boolean;
  onClose: () => void;
  onKindChange: (kind: VisualizationKind) => void;
}) {
  const repository = useRepository();
  const queryClient = useQueryClient();
  const [params, setParamsState] = useState<VizParams>({});
  const [analysisStatus, setAnalysisStatus] = useState('');
  const [starting, setStarting] = useState(false);
  const [pendingJob, setPendingJob] = useState<number | null>(null);
  const requestedSamples = useRef<number | undefined>(undefined);
  const jobsQuery = useJobs(!inline);

  useEffect(() => {
    setParamsState({});
    setPendingJob(null);
    setAnalysisStatus('');
  }, [request.kind, request.dumpId, request.className]);

  const query = useQuery({
    queryKey: ['visualization', request.kind, request.dumpId, request.className, params],
    queryFn: () => prepareVisualization(request.kind, repository, request.dumpId, request.className, params),
    retry: false,
  });

  useEffect(() => {
    if (pendingJob == null) return;
    const job = jobsQuery.data?.find((item) => item.id === pendingJob);
    if (!job) return;
    if (job.state === 'done') {
      setPendingJob(null);
      setAnalysisStatus('Analysis finished — loading the new extraction.');
      const samples = requestedSamples.current;
      queryClient.invalidateQueries();
      setParamsState((current) => samples == null ? current : { ...current, samples });
    } else if (job.state === 'failed' || job.state === 'cancelled') {
      setPendingJob(null);
      setAnalysisStatus(`Analysis ${job.state}: ${job.error || 'see the jobs panel'}`);
    } else {
      setAnalysisStatus(`Analysis ${job.state} — waiting for the job to finish…`);
    }
  }, [jobsQuery.data, pendingJob, queryClient]);

  const setParams = useCallback((patch: VizParams) => {
    setAnalysisStatus('');
    setParamsState((current) => ({ ...current, ...patch }));
  }, []);

  const analyze = useCallback(async (samples?: number) => {
    if (inline) return;
    setStarting(true);
    setAnalysisStatus(`Queueing analysis${samples ? ` with ${samples} samples` : ''}…`);
    requestedSamples.current = samples;
    try {
      const result = await repository.analyze(request.dumpId, request.className, samples == null ? {} : { samples });
      if (!result.ok) { setAnalysisStatus(`Analysis failed: ${result.error}`); return; }
      const job = result.data;
      if (job.id == null || job.state === 'done') {
        queryClient.invalidateQueries();
        setParamsState((current) => samples == null ? current : { ...current, samples });
        setAnalysisStatus('Analysis finished.');
      } else if (job.state === 'failed' || job.state === 'cancelled') {
        setAnalysisStatus(`Analysis ${job.state}: ${job.error || 'see the jobs panel'}`);
      } else {
        setPendingJob(job.id);
        setAnalysisStatus(`Analysis ${job.state} — waiting for the job to finish…`);
      }
    } catch (error) {
      setAnalysisStatus(`Analysis failed: ${errorMessage(error)}`);
    } finally {
      setStarting(false);
    }
  }, [inline, repository, request.dumpId, request.className, queryClient]);

  const viewProps = {
    params, setParams, inline, analyze, analysisStatus,
    analysisPending: starting || pendingJob != null,
  };

  return <Dialog open onClose={onClose} maxWidth={false} fullWidth
    PaperProps={{ className: 'visualization-dialog', sx: { width: '96vw', maxWidth: 1560, height: '94vh', m: 0 } }}>
    <DialogTitle sx={{ pb: 0 }}>
      <Stack direction="row" alignItems="flex-start" gap={2}>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h6" color="secondary" className="class-name">{request.className}</Typography>
          <Typography variant="body2" color="text.secondary">{request.kind} · {request.dumpId}</Typography>
        </Box>
        <IconButton aria-label="Close visualization" onClick={onClose}><CloseIcon /></IconButton>
      </Stack>
      <Tabs value={request.kind} onChange={(_event, kind: VisualizationKind) => onKindChange(kind)} sx={{ mt: 1 }}>
        {kinds.map((kind) => <Tab key={kind} value={kind} label={kind} />)}
      </Tabs>
    </DialogTitle>
    <DialogContent sx={{ display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
      {query.isLoading ? <LoadingState>Loading {request.kind}…</LoadingState>
        : query.isError ? <ErrorState error={query.error} title={`Unable to load ${request.kind}`} />
          : request.kind === 'anatomy' ? <AnatomyView vm={query.data} {...viewProps} />
            : request.kind === 'hierarchy' ? <HierarchyView vm={query.data} {...viewProps} />
              : <ReferenceGraphView vm={query.data} flow={request.kind === 'flow'} {...viewProps} />}
    </DialogContent>
  </Dialog>;
}
