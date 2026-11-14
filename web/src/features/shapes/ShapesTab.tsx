import { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, Card, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { EmptyState, ErrorState, LoadingState } from '../../components/QueryState';
import { ApiError, errorMessage, unwrap } from '../../data/http';
import { queryKeys, useJobs } from '../../data/queries';
import { useRepository } from '../../data/RepositoryContext';
import type { DumpInfo } from '../../data/types';
import { shapesViewModel } from '../../viz/prepare';
import { ShapesCanvas } from './ShapesCanvas';

export function ShapesTab({ dump, inline }: { dump: DumpInfo | null; inline: boolean }) {
  const repository = useRepository();
  const queryClient = useQueryClient();
  const [metric, setMetric] = useState<'shallow' | 'count'>('shallow');
  const [layoutMode, setLayoutMode] = useState<'auto' | 'dag' | 'compact'>('auto');
  const [pendingJob, setPendingJob] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [starting, setStarting] = useState(false);
  const blocked = !inline && (!dump || dump.state === 'remote' || dump.state === 'failed');
  const jobsQuery = useJobs(!inline);
  const query = useQuery({
    queryKey: queryKeys.shapes(dump?.id || ''),
    queryFn: async () => {
      try { return unwrap(await repository.shapes(dump!.id)); }
      catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
    },
    enabled: !blocked && Boolean(dump), retry: false,
  });

  useEffect(() => { setPendingJob(null); setStatus(''); }, [dump?.id]);
  useEffect(() => {
    if (pendingJob == null) return;
    const job = jobsQuery.data?.find((item) => item.id === pendingJob);
    if (!job) return;
    if (job.state === 'done') {
      setPendingJob(null); setStatus('Exact analysis finished.');
      queryClient.invalidateQueries({ queryKey: queryKeys.shapes(dump!.id) });
    } else if (job.state === 'failed' || job.state === 'cancelled') {
      setPendingJob(null); setStatus(`Analysis ${job.state}: ${job.error || 'see jobs panel'}`);
    } else setStatus(`Analysis ${job.state}…`);
  }, [jobsQuery.data, pendingJob, dump, queryClient]);

  const analyze = async () => {
    if (!dump || inline) return;
    setStarting(true); setStatus('Queueing exact heap-wide analysis…');
    try {
      const result = await repository.shapesAnalyze(dump.id);
      if (!result.ok) { setStatus(`Analysis failed: ${result.error}`); return; }
      if (result.data.state === 'done') queryClient.invalidateQueries({ queryKey: queryKeys.shapes(dump.id) });
      else if (result.data.state === 'failed' || result.data.state === 'cancelled') setStatus(`Analysis ${result.data.state}: ${result.data.error || ''}`);
      else setPendingJob(result.data.id);
    } catch (error) { setStatus(`Analysis failed: ${errorMessage(error)}`); }
    finally { setStarting(false); }
  };

  const vm = useMemo(() => dump ? shapesViewModel(query.data, dump.id, { metric, layoutMode }) : null,
    [dump, query.data, metric, layoutMode]);
  if (!dump) return <EmptyState>Select a heap dump to explore exact structural shapes.</EmptyState>;
  if (blocked) return <EmptyState>Dump “{dump.id}” is {dump.state}.{dump.error ? ` ${dump.error}` : ''}</EmptyState>;

  const controls = <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" mb={1}>
    <Typography variant="caption" color="text.secondary">plain node size</Typography>
    <ToggleButtonGroup exclusive size="small" value={metric} onChange={(_event, value) => value && setMetric(value)}>
      <ToggleButton value="shallow">Shallow</ToggleButton><ToggleButton value="count">Objects</ToggleButton>
    </ToggleButtonGroup>
    <Typography variant="caption" color="text.secondary">layout</Typography>
    <ToggleButtonGroup exclusive size="small" value={layoutMode} onChange={(_event, value) => value && setLayoutMode(value)}>
      <ToggleButton value="auto">Auto</ToggleButton><ToggleButton value="dag">DAG</ToggleButton><ToggleButton value="compact">Compact</ToggleButton>
    </ToggleButtonGroup>
    {!inline && <Button size="small" variant="outlined" onClick={analyze}
      disabled={starting || pendingJob != null || dump.state !== 'ready'}>{query.data ? 'Re-analyze exact' : 'Analyze exact'}</Button>}
    {query.data && <Typography variant="caption" color="text.secondary">{query.data.shapes.length.toLocaleString()} shapes · exact, no sampling</Typography>}
  </Stack>;

  return <Card variant="outlined" sx={{ p: 1.5, height: 'max(560px, calc(100vh - 155px))', display: 'flex', flexDirection: 'column' }}>
    {controls}
    {status && <Alert severity={status.includes('failed') ? 'error' : 'info'} sx={{ mb: 1 }}>{status}</Alert>}
    {query.isLoading ? <LoadingState>Loading heap-wide shapes…</LoadingState>
      : query.isError ? <ErrorState error={query.error} title="Unable to load shapes" />
        : !query.data ? <Box p={3}>
          <Typography gutterBottom>No heap-wide shapes analysis yet. The exact structural collapse covers every non-class object, not a sample.</Typography>
          {inline && <Typography color="text.secondary">This snapshot does not contain a shapes artifact.</Typography>}
        </Box> : <ShapesCanvas vm={vm} />}
  </Card>;
}
