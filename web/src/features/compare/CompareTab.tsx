import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  LinearProgress,
  Paper,
  Select,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { formatBytes, formatNumber, formatSignedBytes, shortClassName } from '../../data/format';
import { errorMessage, unwrap } from '../../data/http';
import { queryKeys } from '../../data/queries';
import { useRepository } from '../../data/RepositoryContext';
import type { ComparePayload, CompositionPayload, DumpInfo } from '../../data/types';
import {
  COMPARE_SORTS,
  rsDiffRows,
  sortRows,
  type CompareSortMode,
} from './compareUtils';

interface CompareTabProps {
  dumps: DumpInfo[];
  inline: boolean;
}

interface SubmittedComparison {
  oldId: string;
  newId: string;
}

interface WaterfallData {
  freed?: [string, number][];
  absorbed?: [string, number][];
  freedRest?: number;
  freedRestN?: number;
  absorbedRest?: number;
  absorbedRestN?: number;
  freedSum?: number;
  absorbedSum?: number;
}

type RenderDrill = (key: string, className: string, colSpan: number) => ReactNode;

const COMPOSITION_POLL_INTERVAL = 4_000;
const COMPOSITION_TIMEOUT = 15 * 60 * 1_000;

const deltaColor = (value: number) => value < 0 ? 'success.main' : value > 0 ? 'warning.main' : 'text.primary';

function SignedBytes({ value }: { value: number }) {
  return <Box component="span" sx={{ color: deltaColor(value) }}>{formatSignedBytes(value)}</Box>;
}

function CountDelta({ value }: { value: number }) {
  return <Box component="span" sx={{ color: deltaColor(value) }}>{value > 0 ? '+' : ''}{formatNumber(value)}</Box>;
}

function SectionHeading({ title, subtitle, children }: { title: string; subtitle?: string; children?: ReactNode }) {
  return <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ md: 'baseline' }} sx={{ mt: 2, mb: 1 }}>
    <Typography variant="h6" component="h3">{title}</Typography>
    {subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography>}
    {children}
  </Stack>;
}

function CompareTable({ headers, children, label }: { headers: string[]; children: ReactNode; label: string }) {
  return <TableContainer component={Paper} variant="outlined">
    <Table size="small" aria-label={label} sx={{ minWidth: 760 }}>
      <TableHead><TableRow>
        {headers.map((header, index) => <TableCell key={`${index}:${header}`} align={index === 0 ? 'left' : 'right'}>{header}</TableCell>)}
      </TableRow></TableHead>
      <TableBody>{children}</TableBody>
    </Table>
  </TableContainer>;
}

function ComparisonStats({ comparison }: { comparison: ComparePayload }) {
  const oldShallow = comparison.old?.totalShallow || 0;
  const newShallow = comparison.new?.totalShallow || 0;
  const oldObjects = comparison.old?.totalObjects || 0;
  const newObjects = comparison.new?.totalObjects || 0;
  return <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ mb: 2 }}>
    <Paper variant="outlined" sx={{ p: 2, flex: 1 }}>
      <Typography variant="h6">{formatBytes(oldShallow)} → {formatBytes(newShallow)}</Typography>
      <Typography color="text.secondary">reachable heap (both OOM ⇒ both at ceiling)</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        Δ {formatSignedBytes(newShallow - oldShallow)} — meaningless at the ceiling, see freed → absorbed below
      </Typography>
    </Paper>
    <Paper variant="outlined" sx={{ p: 2, flex: 1 }}>
      <Typography variant="h6">{formatNumber(oldObjects)} → {formatNumber(newObjects)}</Typography>
      <Typography color="text.secondary">live objects</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        Δ {formatSignedBytes(newObjects - oldObjects)}
      </Typography>
    </Paper>
  </Stack>;
}

function WaterfallBar({ value, max, kind }: { value: number; max: number; kind: 'free' | 'absorb' }) {
  return <Box sx={{ height: 9, flex: 1, minWidth: 80, borderRadius: 1, backgroundColor: 'action.hover', overflow: 'hidden' }}>
    <Box sx={{
      width: `${(100 * Math.abs(value) / max).toFixed(1)}%`,
      height: '100%',
      backgroundColor: kind === 'free' ? 'success.main' : 'warning.main',
    }} />
  </Box>;
}

function WaterfallSide({ title, rows, rest, restN, sum, max, kind }: {
  title: string;
  rows: [string, number][];
  rest: number;
  restN: number;
  sum: number;
  max: number;
  kind: 'free' | 'absorb';
}) {
  const renderRow = (name: string, value: number, tail = false) => <Stack direction="row" spacing={1} alignItems="center">
    <Typography title={tail ? undefined : name} noWrap className={tail ? undefined : 'class-name'} sx={{ width: 220, flex: '0 0 220px' }}>
      {tail ? name : shortClassName(name)}
    </Typography>
    <WaterfallBar value={value} max={max} kind={kind} />
    <Typography className="numeric" sx={{ width: 80, color: deltaColor(value) }}>{formatSignedBytes(value)}</Typography>
  </Stack>;
  return <Paper variant="outlined" sx={{ p: 2, flex: 1, minWidth: 0 }}>
    <Typography variant="subtitle1" gutterBottom>{title} — {formatBytes(Math.abs(sum))}</Typography>
    <Stack spacing={0.75}>
      {rows.map(([name, value]) => <Fragment key={name}>{renderRow(name, value)}</Fragment>)}
      {restN > 0 && renderRow(`· ${formatNumber(restN)} more classes (long tail)`, rest, true)}
      <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 0.75 }}>{renderRow(`Σ ${title.toLowerCase()}`, sum)}</Box>
    </Stack>
  </Paper>;
}

function WaterfallSection({ waterfall }: { waterfall: WaterfallData }) {
  const freedSum = waterfall.freedSum || 0;
  const absorbedSum = waterfall.absorbedSum || 0;
  const max = Math.max(-freedSum, absorbedSum, 1);
  return <Box component="section">
    <SectionHeading
      title="Freed → absorbed"
      subtitle="(both heaps at the OOM ceiling: every freed byte is re-absorbed by something — the two sides must balance)"
    />
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={2}>
      <WaterfallSide title="Freed" rows={waterfall.freed || []} rest={waterfall.freedRest || 0}
        restN={waterfall.freedRestN || 0} sum={freedSum} max={max} kind="free" />
      <WaterfallSide title="Absorbed" rows={waterfall.absorbed || []} rest={waterfall.absorbedRest || 0}
        restN={waterfall.absorbedRestN || 0} sum={absorbedSum} max={max} kind="absorb" />
    </Stack>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
      Σ freed {formatBytes(-freedSum)} vs Σ absorbed {formatBytes(absorbedSum)} — net {formatSignedBytes(freedSum + absorbedSum)}{' '}
      (≈ how far the ceiling itself moved). Long-tail sums shown explicitly: many small movers can outweigh the top rows.
    </Typography>
  </Box>;
}

function BuildProxies({ comparison }: { comparison: ComparePayload }) {
  return <Box component="section">
    <SectionHeading title="Build progress proxies" subtitle="(did the new run get further before OOM?)" />
    <CompareTable label="Build progress proxies" headers={['class', 'old objects', 'new objects', 'Δ', 'old shallow', 'new shallow']}>
      {(comparison.proxies || []).map((proxy) => <TableRow key={proxy[0]}>
        <TableCell className="class-name" title={proxy[0]}>{shortClassName(proxy[0])}</TableCell>
        <TableCell align="right" className="numeric">{formatNumber(proxy[1])}</TableCell>
        <TableCell align="right" className="numeric">{formatNumber(proxy[2])}</TableCell>
        <TableCell align="right" className="numeric"><CountDelta value={proxy[2] - proxy[1]} /></TableCell>
        <TableCell align="right" className="numeric">{formatBytes(proxy[3])}</TableCell>
        <TableCell align="right" className="numeric">{formatBytes(proxy[4])}</TableCell>
      </TableRow>)}
    </CompareTable>
  </Box>;
}

function DominatorDeltas({ comparison }: { comparison: ComparePayload }) {
  const dominators = comparison.dom || [];
  return <Box component="section">
    <SectionHeading title="Dominator deltas"
      subtitle="(top-level dominators only — owned memory, closest to the cause; MAT rolls dominated objects into their owner)" />
    <CompareTable label="Dominator deltas"
      headers={['class', 'old objs', 'new objs', 'old retained', 'new retained', 'Δ retained', 'Δ shallow']}>
      {dominators.slice(0, 200).map((row) => {
        const shallowDelta = row[4] - row[3];
        const retainedDelta = row[7];
        return <TableRow key={row[0]}>
          <TableCell className="class-name" title={row[0]}>{shortClassName(row[0])}</TableCell>
          <TableCell align="right" className="numeric">{formatNumber(row[1])}</TableCell>
          <TableCell align="right" className="numeric">{formatNumber(row[2])}</TableCell>
          <TableCell align="right" className="numeric">{formatBytes(row[5])}</TableCell>
          <TableCell align="right" className="numeric">{formatBytes(row[6])}</TableCell>
          <TableCell align="right" className="numeric"><SignedBytes value={retainedDelta} /></TableCell>
          <TableCell align="right" className="numeric"><SignedBytes value={shallowDelta} /></TableCell>
        </TableRow>;
      })}
    </CompareTable>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
      top 200 by |Δ retained| · {formatNumber(dominators.length)} dominator classes total
    </Typography>
  </Box>;
}

function RsDiffButton({ className, onClick }: { className: string; onClick: () => void }) {
  if (String(className).endsWith('$$Lambda*')) return null;
  return <Button size="small" variant="outlined" sx={{ ml: 1 }} onClick={onClick}
    title="diff the retained-set composition of this class between the two dumps (runs analysis where missing)">rs diff</Button>;
}

function RetainedDeltas({ comparison, onDrill, renderDrill }: {
  comparison: ComparePayload;
  onDrill: (key: string, className: string) => void;
  renderDrill: RenderDrill;
}) {
  const retained = comparison.retained || [];
  if (retained.length === 0) return null;
  return <Box component="section">
    <SectionHeading title="Retained-set deltas" subtitle="(classes analyzed in both dumps — the trustworthy comparison)" />
    <CompareTable label="Retained-set deltas" headers={['class', 'old retained set', 'new retained set', 'Δ']}>
      {retained.map((row) => {
        const drillKey = `retained:${row[0]}`;
        return <Fragment key={row[0]}>
          <TableRow>
            <TableCell className="class-name" title={row[0]}>{shortClassName(row[0])}
              <RsDiffButton className={row[0]} onClick={() => onDrill(drillKey, row[0])} />
            </TableCell>
            <TableCell align="right" className="numeric">{formatBytes(row[1])}</TableCell>
            <TableCell align="right" className="numeric">{formatBytes(row[2])}</TableCell>
            <TableCell align="right" className="numeric"><SignedBytes value={row[3]} /></TableCell>
          </TableRow>
          {renderDrill(drillKey, row[0], 4)}
        </Fragment>;
      })}
    </CompareTable>
  </Box>;
}

function AnatomyDiffs({ comparison }: { comparison: ComparePayload }) {
  const anatomies = Object.entries(comparison.anats || {});
  if (anatomies.length === 0) return null;
  return <Box component="section">
    <SectionHeading title="Anatomy diffs"
      subtitle="(per sampled instance, field-path matched — what changed inside a typical instance; instance-count changes are in the class deltas)" />
    {anatomies.map(([className, diff]) => <Accordion key={className} variant="outlined" disableGutters>
      <AccordionSummary expandIcon={<span aria-hidden="true">▾</span>}>
        <Typography>{shortClassName(className)} — {formatNumber(diff.total)} changed paths · samples s{diff.samples[0]} → s{diff.samples[1]}</Typography>
      </AccordionSummary>
      <AccordionDetails>
        <CompareTable label={`Anatomy diff for ${className}`}
          headers={['field path (per-instance values)', 'old shallow', 'new shallow', 'Δ shallow', 'old retained', 'new retained', 'Δ retained']}>
          {(diff.rows || []).map((row) => {
            const parts = String(row[0]).split('/');
            const depth = Math.max(0, Math.min(parts.length - 2, 10));
            return <TableRow key={row[0]}>
              <TableCell className="class-name" title={row[0]} sx={{ pl: 2 + depth }}>{parts.pop()}</TableCell>
              <TableCell align="right" className="numeric">{formatBytes(row[1])}</TableCell>
              <TableCell align="right" className="numeric">{formatBytes(row[2])}</TableCell>
              <TableCell align="right" className="numeric"><SignedBytes value={row[3]} /></TableCell>
              <TableCell align="right" className="numeric">{formatBytes(row[4])}</TableCell>
              <TableCell align="right" className="numeric">{formatBytes(row[5])}</TableCell>
              <TableCell align="right" className="numeric"><SignedBytes value={row[6]} /></TableCell>
            </TableRow>;
          })}
        </CompareTable>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {diff.total > (diff.rows || []).length ? `top ${diff.rows?.length || 0} of ${formatNumber(diff.total)} changed paths by |Δ| · ` : ''}
          string-value labels differ per run → same-content strings show as remove+add pairs; &quot;(held via untracked/shared refs)&quot; is memory owned by others that leaked into the sample
        </Typography>
      </AccordionDetails>
    </Accordion>)}
  </Box>;
}

function ClassDeltas({ comparison, sortMode, onSort, onDrill, renderDrill }: {
  comparison: ComparePayload;
  sortMode: CompareSortMode;
  onSort: (mode: CompareSortMode) => void;
  onDrill: (key: string, className: string) => void;
  renderDrill: RenderDrill;
}) {
  const rows = comparison.rows || [];
  const sorted = sortRows(rows, sortMode);
  return <Box component="section">
    <SectionHeading title="Class deltas — raw histogram (lambda-normalized)">
      <Stack direction="row" useFlexGap flexWrap="wrap" spacing={0.5} alignItems="center" sx={{ ml: { md: 'auto' } }}>
        <Typography variant="body2">sort:</Typography>
        {COMPARE_SORTS.map((sort, index) => <Fragment key={sort.key}>
          {index > 0 && <Typography component="span" color="text.secondary">·</Typography>}
          <Button size="small" variant="text" aria-pressed={sortMode === sort.key} onClick={() => onSort(sort.key)}
            sx={{ minWidth: 0, p: 0.25, textDecoration: sortMode === sort.key ? 'underline' : 'none', fontWeight: sortMode === sort.key ? 700 : 400 }}>
            {sort.label}
          </Button>
        </Fragment>)}
      </Stack>
    </SectionHeading>
    <CompareTable label="Class deltas"
      headers={['class', 'old objs', 'new objs', 'Δ objs', 'old shallow', 'new shallow', 'Δ shallow']}>
      {sorted.slice(0, 400).map((row) => {
        const drillKey = `class:${row[0]}`;
        return <Fragment key={row[0]}>
          <TableRow>
            <TableCell className="class-name" title={row[0]}>{shortClassName(row[0])}
              <RsDiffButton className={row[0]} onClick={() => onDrill(drillKey, row[0])} />
            </TableCell>
            <TableCell align="right" className="numeric">{formatNumber(row[1])}</TableCell>
            <TableCell align="right" className="numeric">{formatNumber(row[2])}</TableCell>
            <TableCell align="right" className="numeric"><CountDelta value={row[3]} /></TableCell>
            <TableCell align="right" className="numeric">{formatBytes(row[4])}</TableCell>
            <TableCell align="right" className="numeric">{formatBytes(row[5])}</TableCell>
            <TableCell align="right" className="numeric"><SignedBytes value={row[6]} /></TableCell>
          </TableRow>
          {renderDrill(drillKey, row[0], 7)}
        </Fragment>;
      })}
    </CompareTable>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
      top 400 by current sort · {formatNumber(rows.length)} classes total · this table is zero-sum noise at the ceiling — the sections above explain it
    </Typography>
  </Box>;
}

function useCompositionForDiff(dumpId: string, className: string, enabled: boolean, timedOut: boolean) {
  const repository = useRepository();
  return useQuery({
    queryKey: queryKeys.composition(dumpId, className),
    queryFn: async (): Promise<CompositionPayload | null> => {
      const result = await repository.composition(dumpId, className);
      if (!result.ok && result.status === 404) return null;
      return unwrap(result);
    },
    enabled: enabled && !timedOut,
    refetchInterval: (query) => timedOut || query.state.data != null || query.state.error != null
      ? false : COMPOSITION_POLL_INTERVAL,
    retry: false,
    staleTime: 0,
  });
}

function RetainedSetDiffView({ className, oldComposition, newComposition }: {
  className: string;
  oldComposition: CompositionPayload;
  newComposition: CompositionPayload;
}) {
  const rows = rsDiffRows(oldComposition, newComposition);
  const oldShallow = oldComposition.totalShallow || 0;
  const newShallow = newComposition.totalShallow || 0;
  return <Box sx={{ py: 1 }}>
    <Typography gutterBottom>
      retained set of <strong title={className}>{shortClassName(className)}</strong>: {formatBytes(oldShallow)} → {formatBytes(newShallow)}{' '}
      <SignedBytes value={newShallow - oldShallow} /> · {formatNumber(oldComposition.totalObjects || 0)} → {formatNumber(newComposition.totalObjects || 0)} objects
    </Typography>
    <CompareTable label={`Retained-set diff for ${className}`}
      headers={['inside the retained set', 'old shallow', 'new shallow', 'Δ shallow', 'old objs', 'new objs']}>
      {rows.slice(0, 30).map((row) => <TableRow key={row[0]}>
        <TableCell className="class-name" title={row[0]}>{shortClassName(row[0])}</TableCell>
        <TableCell align="right" className="numeric">{formatBytes(row[1])}</TableCell>
        <TableCell align="right" className="numeric">{formatBytes(row[2])}</TableCell>
        <TableCell align="right" className="numeric"><SignedBytes value={row[3]} /></TableCell>
        <TableCell align="right" className="numeric">{formatNumber(row[4])}</TableCell>
        <TableCell align="right" className="numeric">{formatNumber(row[5])}</TableCell>
      </TableRow>)}
    </CompareTable>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
      top 30 inner movers of {formatNumber(rows.length)} classes seen in either retained set (each side lists its top 100 by shallow; the totals above are complete)
    </Typography>
  </Box>;
}

function RetainedSetDrill({ oldId, newId, className, initiallyAnalyzedOld, initiallyAnalyzedNew, onComplete }: {
  oldId: string;
  newId: string;
  className: string;
  initiallyAnalyzedOld: boolean;
  initiallyAnalyzedNew: boolean;
  onComplete: (className: string) => void;
}) {
  const repository = useRepository();
  const analyzedAtMount = useRef({ old: initiallyAnalyzedOld, new: initiallyAnalyzedNew }).current;
  const needsAnalysis = !analyzedAtMount.old || !analyzedAtMount.new;
  const [timedOut, setTimedOut] = useState(false);
  const [deadline, setDeadline] = useState<number | null>(null);

  // The preparation query also deduplicates requests when React StrictMode replays effects.
  const preparation = useQuery({
    queryKey: ['compare', 'composition-analysis', oldId, newId, className],
    queryFn: async () => {
      for (const side of ['old', 'new'] as const) {
        if (analyzedAtMount[side]) continue;
        await unwrap(await repository.analyze(side === 'old' ? oldId : newId, className, { samples: 8, anatomy: false }));
      }
      return true;
    },
    enabled: needsAnalysis,
    retry: false,
    staleTime: Infinity,
  });

  const compositionsEnabled = !needsAnalysis || preparation.isSuccess;
  useEffect(() => {
    if (compositionsEnabled && deadline == null) setDeadline(Date.now() + COMPOSITION_TIMEOUT);
  }, [compositionsEnabled, deadline]);

  const oldComposition = useCompositionForDiff(oldId, className, compositionsEnabled, timedOut);
  const newComposition = useCompositionForDiff(newId, className, compositionsEnabled, timedOut);
  const bothAvailable = oldComposition.data != null && newComposition.data != null;
  const hasTerminalError = (needsAnalysis && preparation.isError) || oldComposition.isError || newComposition.isError;

  useEffect(() => {
    if (deadline == null || bothAvailable || hasTerminalError || timedOut) return;
    const timer = window.setTimeout(() => setTimedOut(true), Math.max(0, deadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [bothAvailable, deadline, hasTerminalError, timedOut]);

  useEffect(() => {
    if (bothAvailable) onComplete(className);
  }, [bothAvailable, className, onComplete]);

  if (needsAnalysis && preparation.isError) return <Alert severity="error">analyze failed: {errorMessage(preparation.error)}</Alert>;
  if (oldComposition.isError) return <Alert severity="error">composition query failed (old): {errorMessage(oldComposition.error)}</Alert>;
  if (newComposition.isError) return <Alert severity="error">composition query failed (new): {errorMessage(newComposition.error)}</Alert>;
  if (timedOut && !bothAvailable) return <Alert severity="warning">timed out waiting for analysis — check the jobs panel.</Alert>;
  if (oldComposition.data && newComposition.data) {
    return <RetainedSetDiffView className={className} oldComposition={oldComposition.data} newComposition={newComposition.data} />;
  }

  return <Box sx={{ py: 1 }} role="status">
    <LinearProgress sx={{ mb: 1 }} />
    <Typography variant="body2" color="text.secondary">
      {preparation.isFetching
        ? `queuing retained-set analysis of ${shortClassName(className)} in ${!analyzedAtMount.old ? oldId : newId} (composition-only; progress in the jobs panel)…`
        : `waiting for retained-set analysis (${oldComposition.data ? '✓' : '…'} old / ${newComposition.data ? '✓' : '…'} new)…`}
    </Typography>
  </Box>;
}

export function CompareTab({ dumps, inline }: CompareTabProps) {
  const repository = useRepository();
  const available = useMemo(() => dumps.filter((dump) => dump.state !== 'remote' && dump.state !== 'failed'), [dumps]);
  const availableSignature = available.map((dump) => dump.id).join('\u0000');
  const [oldId, setOldId] = useState(available[0]?.id || '');
  const [newId, setNewId] = useState((available[1] || available[0])?.id || '');
  const [submitted, setSubmitted] = useState<SubmittedComparison | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [sortMode, setSortMode] = useState<CompareSortMode>('abs');
  const [openDrills, setOpenDrills] = useState<Set<string>>(() => new Set());
  const [completedAnalyses, setCompletedAnalyses] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    const ids = new Set(availableSignature ? availableSignature.split('\u0000') : []);
    setOldId((current) => ids.has(current) ? current : available[0]?.id || '');
    setNewId((current) => ids.has(current) ? current : (available[1] || available[0])?.id || '');
  }, [available, availableSignature]);

  const compareQuery = useQuery({
    queryKey: submitted ? queryKeys.compare(submitted.oldId, submitted.newId) : ['compare', 'idle'],
    queryFn: async () => {
      if (!submitted) throw new Error('no comparison selected');
      return unwrap(await repository.compare(submitted.oldId, submitted.newId));
    },
    enabled: !inline && submitted != null && submitted.oldId !== submitted.newId,
    retry: (count, error) => count < 20 && typeof error === 'object' && error != null && 'status' in error && error.status === 409,
    retryDelay: COMPOSITION_POLL_INTERVAL,
    refetchOnWindowFocus: false,
  });

  const beginCompare = () => {
    if (!oldId || !newId) return;
    if (oldId === newId) {
      setSubmitted(null);
      setValidationError('pick two different dumps to compare.');
      return;
    }
    setValidationError(null);
    setSortMode('abs');
    setOpenDrills(new Set());
    setCompletedAnalyses(new Set());
    if (submitted?.oldId === oldId && submitted.newId === newId) void compareQuery.refetch();
    else setSubmitted({ oldId, newId });
  };

  const toggleDrill = (key: string) => {
    setOpenDrills((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const markAnalysisComplete = useCallback((className: string) => {
    setCompletedAnalyses((current) => {
      if (current.has(className)) return current;
      const next = new Set(current);
      next.add(className);
      return next;
    });
  }, []);

  if (inline) return <Alert severity="info">compare is not available in a static snapshot.</Alert>;
  if (available.length < 2) {
    return <Alert severity="info">compare needs two local dumps with data — only {available.length} available right now.</Alert>;
  }

  const comparison = compareQuery.data;
  const renderDrill: RenderDrill = (key, className, colSpan) => {
    if (!openDrills.has(key) || !submitted || !comparison) return null;
    return <TableRow sx={{ backgroundColor: 'action.hover' }}>
      <TableCell colSpan={colSpan}>
        <RetainedSetDrill
          key={`${submitted.oldId}:${submitted.newId}:${key}`}
          oldId={submitted.oldId}
          newId={submitted.newId}
          className={className}
          initiallyAnalyzedOld={completedAnalyses.has(className) || Boolean(comparison.analyzed?.old.includes(className))}
          initiallyAnalyzedNew={completedAnalyses.has(className) || Boolean(comparison.analyzed?.new.includes(className))}
          onComplete={markAnalysisComplete}
        />
      </TableCell>
    </TableRow>;
  };

  return <Paper variant="outlined" sx={{ p: 2 }}>
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={1.5} alignItems={{ xl: 'center' }}>
      <Select size="small" value={oldId} onChange={(event) => setOldId(event.target.value)}
        inputProps={{ 'aria-label': 'old dump' }} sx={{ minWidth: 220 }}>
        {available.map((dump) => <MenuItem key={dump.id} value={dump.id}>
          {dump.state === 'ready' ? dump.id : `${dump.id} (${dump.state})`}
        </MenuItem>)}
      </Select>
      <Typography sx={{ alignSelf: 'center' }}>→</Typography>
      <Select size="small" value={newId} onChange={(event) => setNewId(event.target.value)}
        inputProps={{ 'aria-label': 'new dump' }} sx={{ minWidth: 220 }}>
        {available.map((dump) => <MenuItem key={dump.id} value={dump.id}>
          {dump.state === 'ready' ? dump.id : `${dump.id} (${dump.state})`}
        </MenuItem>)}
      </Select>
      <Button variant="contained" onClick={beginCompare} disabled={!oldId || !newId}>Compare</Button>
      <Typography variant="body2" color="text.secondary">
        freed → absorbed = zero-sum at the OOM ceiling · dominator = owned memory · &quot;rs diff&quot; on a row drills into its retained-set diff
      </Typography>
    </Stack>

    <Box sx={{ mt: 2 }}>
      {validationError && <Alert severity="error">{validationError}</Alert>}
      {submitted && compareQuery.isFetching && <Box p={3} role="status">
        <LinearProgress sx={{ mb: 2 }} />
        <Typography>comparing {submitted.oldId} → {submitted.newId} …</Typography>
      </Box>}
      {submitted && compareQuery.isError && !compareQuery.isFetching && <Alert severity="error">
        compare failed: {errorMessage(compareQuery.error)}
      </Alert>}
      {submitted && comparison && !compareQuery.isFetching && !compareQuery.isError && <Box>
        <ComparisonStats comparison={comparison} />
        <WaterfallSection waterfall={(comparison.waterfall || {}) as WaterfallData} />
        <BuildProxies comparison={comparison} />
        <DominatorDeltas comparison={comparison} />
        <RetainedDeltas comparison={comparison} onDrill={toggleDrill} renderDrill={renderDrill} />
        <AnatomyDiffs comparison={comparison} />
        <ClassDeltas comparison={comparison} sortMode={sortMode} onSort={setSortMode}
          onDrill={toggleDrill} renderDrill={renderDrill} />
      </Box>}
    </Box>
  </Paper>;
}

export default CompareTab;
