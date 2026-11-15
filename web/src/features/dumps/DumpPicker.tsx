import { useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, InputAdornment, MenuItem, Paper, Select, Stack, TextField, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import SearchIcon from '@mui/icons-material/Search';
import { DumpStateChip } from '../../components/DumpStateChip';
import { EmptyState } from '../../components/QueryState';
import { formatBytes, formatSource } from '../../data/format';
import { errorMessage } from '../../data/http';
import { useDumpMutation } from '../../data/queries';
import type { DumpInfo } from '../../data/types';

export interface DumpPickerProps {
  open: boolean;
  onClose: () => void;
  dumps: DumpInfo[];
  currentDump: string | null;
  liveDownloads: Set<string>;
  onSelect: (id: string) => void;
}

type Sort = 'date' | 'name' | 'size';
type DumpAction = 'download' | 'cancel' | 'delete' | 'tags';

const derivedTags = (id: string) => [...new Set(id.split('-').filter((tag) => tag && tag !== 'run'))];
const manualTags = (dump: DumpInfo) => Array.isArray(dump.meta?.tags) ? dump.meta.tags : [];
const allTags = (dump: DumpInfo) => [...new Set([...derivedTags(dump.id), ...manualTags(dump)])];
const dateOf = (dump: DumpInfo) => dump.meta?.created_at || '';

function actionLabel(dump: DumpInfo, live: boolean) {
  if (dump.state === 'remote') return 'Download';
  if (dump.state === 'failed') return 'Retry download';
  if (live) return null;
  if (dump.state === 'downloading' || dump.state === 'assembling') return 'Resume download';
  if (dump.state === 'indexing') return 'Fetch or build data';
  return null;
}

export function DumpPicker({ open, onClose, dumps, currentDump, liveDownloads, onSelect }: DumpPickerProps) {
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<Sort>('date');
  const [activeTags, setActiveTags] = useState<Set<string>>(() => new Set());
  const [addingFor, setAddingFor] = useState<string | null>(null);
  const [tagDraft, setTagDraft] = useState('');
  const [deleteCandidate, setDeleteCandidate] = useState<DumpInfo | null>(null);
  const [operationError, setOperationError] = useState<string | null>(null);
  const mutation = useDumpMutation();

  const tagCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const dump of dumps) {
      for (const tag of allTags(dump)) counts.set(tag, (counts.get(tag) || 0) + 1);
    }
    return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  }, [dumps]);

  const visibleDumps = useMemo(() => {
    const query = search.trim().toLowerCase();
    const matching = dumps.filter((dump) => {
      const tags = allTags(dump);
      if ([...activeTags].some((tag) => !tags.includes(tag))) return false;
      if (!query) return true;
      return `${dump.id} ${dump.meta?.title || ''} ${tags.join(' ')}`.toLowerCase().includes(query);
    });
    return matching.sort((a, b) => {
      if (sort === 'name') return a.id.localeCompare(b.id);
      if (sort === 'size') return (b.size || 0) - (a.size || 0);
      return dateOf(b).localeCompare(dateOf(a));
    });
  }, [activeTags, dumps, search, sort]);

  const closePicker = () => {
    setAddingFor(null);
    setDeleteCandidate(null);
    onClose();
  };

  const selectDump = (id: string) => {
    onSelect(id);
    closePicker();
  };

  const runOperation = async (action: DumpAction, id: string, tags?: string[]) => {
    setOperationError(null);
    try {
      await mutation.mutateAsync({ action, id, tags });
      return true;
    } catch (error) {
      setOperationError(`${action === 'tags' ? 'tags for ' : ''}${id}: ${errorMessage(error)}`);
      return false;
    }
  };

  const commitTags = async (dump: DumpInfo, tags: string[]) => {
    const normalized = [...new Set(tags.map((tag) => tag.trim()).filter(Boolean))];
    setAddingFor(null);
    setTagDraft('');
    await runOperation('tags', dump.id, normalized);
  };

  const confirmDelete = async () => {
    if (!deleteCandidate) return;
    if (await runOperation('delete', deleteCandidate.id)) setDeleteCandidate(null);
  };

  const rowKeyDown = (event: KeyboardEvent<HTMLElement>, id: string) => {
    if (event.target !== event.currentTarget || (event.key !== 'Enter' && event.key !== ' ')) return;
    event.preventDefault();
    selectDump(id);
  };

  const stopRowClick = (event: MouseEvent) => event.stopPropagation();
  const isBusy = (id: string) => mutation.isPending && mutation.variables?.id === id;

  return <>
    <Dialog open={open} onClose={closePicker} fullWidth maxWidth="lg" aria-labelledby="dump-picker-title"
      PaperProps={{ className: 'dump-picker-dialog', sx: { height: { xs: '100%', sm: 'min(820px, 90vh)' }, maxHeight: { xs: '100%', sm: '90vh' } } }}>
      <DialogTitle id="dump-picker-title" sx={{ pb: 1 }}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Box flex={1}>Open Heap Dump</Box>
          <IconButton onClick={closePicker} aria-label="Close dump picker" title="Close (Esc)"><CloseIcon /></IconButton>
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} mt={1}>
          <TextField autoFocus fullWidth size="small" value={search} onChange={(event) => setSearch(event.target.value)}
            placeholder="Search by name or tag…" inputProps={{ 'aria-label': 'Search dumps' }}
            InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon sx={{ fontSize: 16 }} /></InputAdornment> }} />
          <Select size="small" value={sort} onChange={(event) => setSort(event.target.value as Sort)}
            inputProps={{ 'aria-label': 'Sort dumps' }} sx={{ minWidth: 160 }}>
            <MenuItem value="date">Sort: newest</MenuItem>
            <MenuItem value="name">Sort: name</MenuItem>
            <MenuItem value="size">Sort: size</MenuItem>
          </Select>
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        {tagCounts.length > 0 && <Stack direction="row" flexWrap="wrap" gap={0.75} mb={2}>
          {tagCounts.map(([tag, count]) => <Chip key={tag} size="small" clickable
            color={activeTags.has(tag) ? 'primary' : 'default'} variant={activeTags.has(tag) ? 'filled' : 'outlined'}
            label={`${tag} ${count}`} aria-pressed={activeTags.has(tag)} onClick={() => setActiveTags((current) => {
              const next = new Set(current);
              if (next.has(tag)) next.delete(tag); else next.add(tag);
              return next;
            })} />)}
          {activeTags.size > 0 && <Button size="small" onClick={() => setActiveTags(new Set())}>Clear filters</Button>}
        </Stack>}

        {operationError && <Alert severity="error" onClose={() => setOperationError(null)} sx={{ mb: 2 }}>
          {operationError}
        </Alert>}

        {!visibleDumps.length ? <EmptyState>{dumps.length ? 'No dumps match.' : 'No dumps yet.'}</EmptyState> :
          <Stack spacing={1.25}>
            {visibleDumps.map((dump) => {
              const live = liveDownloads.has(dump.id);
              const label = actionLabel(dump, live);
              const busy = isBusy(dump.id);
              const manual = manualTags(dump);
              const date = dateOf(dump);
              return <Paper key={dump.id} variant="outlined" role="button" tabIndex={0}
                aria-label={`Select dump ${dump.id}`} aria-current={dump.id === currentDump ? 'true' : undefined}
                onClick={() => selectDump(dump.id)} onKeyDown={(event) => rowKeyDown(event, dump.id)}
                sx={{
                  p: 1.5, cursor: 'pointer', borderColor: dump.id === currentDump ? 'primary.main' : 'divider',
                  bgcolor: dump.id === currentDump ? 'action.selected' : 'background.paper',
                  '&:hover': { borderColor: 'primary.main' },
                }}>
                <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}>
                  <Box flex={1} minWidth={0}>
                    <Typography fontFamily="monospace" fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{dump.id}</Typography>
                    {dump.meta?.title && dump.meta.title !== dump.id &&
                      <Typography variant="body2" color="text.secondary">{dump.meta.title}</Typography>}
                    <Stack direction="row" flexWrap="wrap" gap={0.5} mt={0.75} alignItems="center">
                      {allTags(dump).map((tag) => <Chip key={tag} size="small" label={tag}
                        variant={manual.includes(tag) ? 'filled' : 'outlined'}
                        color={manual.includes(tag) ? 'secondary' : 'default'}
                        onDelete={manual.includes(tag) && !busy ? (event) => {
                          event.stopPropagation();
                          void commitTags(dump, manual.filter((item) => item !== tag));
                        } : undefined} />)}
                      {addingFor === dump.id ? <TextField size="small" variant="standard" autoFocus value={tagDraft}
                        disabled={busy} placeholder="tag, another tag…" inputProps={{ 'aria-label': `Add tags to ${dump.id}` }}
                        onClick={stopRowClick} onChange={(event) => setTagDraft(event.target.value)}
                        onBlur={() => { if (addingFor === dump.id) setAddingFor(null); }}
                        onKeyDown={(event) => {
                          event.stopPropagation();
                          if (event.key === 'Enter') void commitTags(dump, [...manual, ...tagDraft.split(',')]);
                          if (event.key === 'Escape') setAddingFor(null);
                        }} sx={{ minWidth: 180 }} /> :
                        <Button size="small" disabled={busy} onClick={(event) => {
                          event.stopPropagation();
                          setAddingFor(dump.id);
                          setTagDraft('');
                        }}>+ tag</Button>}
                    </Stack>
                  </Box>

                  <Stack direction="row" flexWrap="wrap" gap={0.75} alignItems="center" onClick={stopRowClick}>
                    <DumpStateChip state={dump.state} />
                    {dump.progress?.total ? <Typography variant="caption" color="text.secondary">
                      {formatBytes(dump.progress.done)} / {formatBytes(dump.progress.total)}
                    </Typography> : dump.size ? <Typography variant="caption" color="text.secondary">
                      {formatBytes(dump.size)}
                    </Typography> : null}
                    {dump.progress?.source && <Typography variant="caption" color="text.secondary">
                      {formatSource(dump.progress.source)}
                    </Typography>}
                    {date && <Typography variant="caption" color="text.secondary">{date.slice(0, 10)}</Typography>}
                    {dump.source && <Typography variant="caption" color="text.secondary">{dump.source}</Typography>}
                  </Stack>

                  <Stack direction="row" flexWrap="wrap" gap={0.75} onClick={stopRowClick}>
                    {label && <Button size="small" variant="contained" disabled={busy}
                      onClick={() => void runOperation('download', dump.id)}>{label}</Button>}
                    {live && <Button size="small" color="error" variant="outlined" disabled={busy}
                      onClick={() => void runOperation('cancel', dump.id)}>Cancel</Button>}
                    {(dump.state === 'ready' || dump.state === 'failed') && <Button size="small" color="error"
                      variant="outlined" disabled={busy} onClick={() => setDeleteCandidate(dump)}>Delete</Button>}
                  </Stack>
                </Stack>
                {dump.error && <Typography variant="body2" color="error" mt={1}>{dump.error}</Typography>}
              </Paper>;
            })}
          </Stack>}
      </DialogContent>
    </Dialog>

    <Dialog open={open && Boolean(deleteCandidate)} onClose={() => setDeleteCandidate(null)} maxWidth="xs" fullWidth>
      <DialogTitle>Delete {deleteCandidate?.id}?</DialogTitle>
      <DialogContent>The local dump and its indexes are removed.</DialogContent>
      <DialogActions>
        <Button onClick={() => setDeleteCandidate(null)}>Keep dump</Button>
        <Button color="error" variant="contained" disabled={!deleteCandidate || isBusy(deleteCandidate.id)}
          onClick={() => void confirmDelete()}>Delete</Button>
      </DialogActions>
    </Dialog>
  </>;
}
