import { useState } from 'react';
import { Box, Button, IconButton, Paper, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { buildRowModel, untrackedSummary } from '../../viz/anatomy.js';
import { useApp } from '../app/AppContext';
import { categoryColors, categoryOf, formatBytes, formatNumber } from '../data/format';
import { AnalyzePrompt, SamplePicker } from './AnalysisControls';
import type { ViewModel, VisualizationViewProps } from './types';

interface RowOptions {
  G: boolean; Mf: number; K: number; objCount: number; root: ViewModel; sampleR: number; rootExact: number | null;
}

function AnatomyRow({ node, options }: { node: ViewModel; options: RowOptions }) {
  const model = buildRowModel(node, options);
  return <Box className={`anatomy-row ${model.cls}`}>
    <Box className="anatomy-name">
      <span className="anatomy-toggle-spacer" />
      <Box component="span" sx={{ display: 'inline-block', width: 9, height: 9, borderRadius: '50%', mr: 1,
        bgcolor: categoryColors[categoryOf(node.full || '')] }} />
      <span title={node.full || ''}>{node.name}</span>
      {model.pres && <span className="presence" title={`Field non-null in ${model.pres.in} of ${model.pres.of} samples`}>in {model.pres.in}/{model.pres.of}</span>}
      {model.refs != null && <span className="references" title="Inbound references from inside the retained set">⇆{formatNumber(model.refs)}</span>}
      {' '}<span className="muted">×{model.count}</span>
    </Box>
    <span className="numeric" title={options.G ? 'Estimated from the per-instance average' : ''}>{model.objects}</span>
    <span className="numeric">{model.shallow}</span>
    <span className="numeric muted">{model.perInstance}</span>
    <span className={`numeric ${model.dimRet ? 'muted' : ''}`} title={model.retTitle}>{model.retained} <small className="muted">{model.retPct}</small></span>
  </Box>;
}

function AnatomyNode({ node, options, depth }: { node: ViewModel; options: RowOptions; depth: number }) {
  const [open, setOpen] = useState(depth < 1);
  const children = node.kids || [];
  return <Box>
    <Box sx={{ position: 'relative' }}>
      {children.length > 0 && <IconButton size="small" aria-label={open ? 'Collapse node' : 'Expand node'}
        onClick={() => setOpen((value) => !value)} sx={{ position: 'absolute', left: 0, top: -3, zIndex: 1, p: 0.25 }}>
        {open ? <ExpandMoreIcon fontSize="small" /> : <ChevronRightIcon fontSize="small" />}
      </IconButton>}
      <AnatomyRow node={node} options={options} />
    </Box>
    {open && children.length > 0 && <Box sx={{ ml: 1, pl: 1.25, borderLeft: 1, borderColor: 'divider' }}>
      {children.map((child: ViewModel, index: number) => <AnatomyNode key={`${child.full}-${index}`} node={child} options={options} depth={depth + 1} />)}
    </Box>}
  </Box>;
}

export function AnatomyView({ vm, setParams, inline, analyze, analysisStatus, analysisPending }: VisualizationViewProps) {
  const { openViz } = useApp();
  const [scale, setScale] = useState<'sample' | 'global'>('global');
  if (!vm.analyzed) return <AnalyzePrompt kind="anatomy" inline={inline} analyze={analyze}
    status={analysisStatus} pending={analysisPending} />;

  const anatomy = vm.anat;
  const samples = anatomy.samples;
  const global = scale === 'global';
  const options: RowOptions = {
    G: global, Mf: Math.max(1, vm.objCount) / samples, K: samples, objCount: vm.objCount,
    root: anatomy.tree, sampleR: anatomy.tree.r, rootExact: vm.compTotalShallow,
  };
  return <Box>
    <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" mb={1}>
      <ToggleButtonGroup size="small" exclusive value={scale} onChange={(_event, value) => value && setScale(value)}>
        <ToggleButton value="sample">{samples} sample instances</ToggleButton>
        <ToggleButton value="global">× {formatNumber(vm.objCount)} instances (estimated)</ToggleButton>
      </ToggleButtonGroup>
      <SamplePicker available={anatomy.available || []} current={samples} inline={inline}
        onSwitch={(sampleCount) => setParams({ samples: sampleCount })} onAnalyze={analyze} disabled={analysisPending} />
    </Stack>
    <Typography variant="caption" color="text.secondary" display="block" mb={1}>
      Full graph over the union retained set of {samples} samples. Counts and shallow bytes extrapolate;
      root retained is exact, child retained is not extrapolated.
    </Typography>
    <Box className="anatomy-row anatomy-head">
      <span>reference tree</span><span className="numeric">objects</span><span className="numeric">shallow</span>
      <span className="numeric">per instance</span><span className="numeric">retained</span>
    </Box>
    <AnatomyNode node={anatomy.tree} options={options} depth={0} />

    {anatomy.untracked?.length ? <>
      <Typography variant="subtitle2" mt={3} mb={1}>Still unreachable — {anatomy.untracked.length} groups with their structure</Typography>
      {anatomy.untracked.map((group: ViewModel, index: number) => {
        const summary = untrackedSummary(group, options);
        return <Paper variant="outlined" key={`${summary.name}-${index}`} sx={{ p: 1, mb: 1 }}>
          <Typography variant="body2"><strong>{summary.name}</strong> — {summary.objects} objects · {summary.shallow} shallow · {summary.retained} retained</Typography>
          {(group.tree.kids || []).map((child: ViewModel, childIndex: number) =>
            <AnatomyNode key={childIndex} node={child} options={options} depth={1} />)}
        </Paper>;
      })}
    </> : <Paper variant="outlined" sx={{ p: 1.5, mt: 2 }}>
      <Typography variant="body2" color="text.secondary">Every object is reachable in the tree — no untracked remainder.{!anatomy.fullEdges && ' Warning: no complete edges extraction; re-run analysis to reveal large-array children.'}</Typography>
    </Paper>}

    {anatomy.graph?.nodes?.length > 0 && <Stack direction="row" spacing={1} alignItems="center" mt={2}>
      <Typography variant="body2" color="text.secondary">Class graph: {formatNumber(anatomy.graph.nodes.length)} classes · {formatNumber(anatomy.graph.links.length)} connections</Typography>
      <Button size="small" onClick={() => openViz('graph', vm.dumpId, vm.className)}>Open graph</Button>
      <Button size="small" onClick={() => openViz('flow', vm.dumpId, vm.className)}>Open flow</Button>
    </Stack>}
    <Typography variant="caption" color="text.secondary" display="block" mt={2}>
      Complete outbounds: {anatomy.fullEdges ? 'extracted' : 'not extracted'} · depth cap {anatomy.depth}.
      Synthetic fields are dimmed. ⇆ marks inbound references; “in k/K” is field presence.
      Shared/external objects are owned elsewhere. Retained percentages vary with sample count because
      sharing with unsampled instances is intentionally not estimated. Root exact retained: {vm.compTotalShallow == null ? 'unavailable' : formatBytes(vm.compTotalShallow)}.
    </Typography>
    {analysisStatus && <Typography variant="body2" color="secondary" mt={1}>{analysisStatus}</Typography>}
  </Box>;
}
