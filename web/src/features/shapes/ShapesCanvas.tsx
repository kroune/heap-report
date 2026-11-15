import { useEffect, useRef, useState } from 'react';
import { Box, Button, ButtonGroup, Paper, Stack, Typography } from '@mui/material';
import { useApp } from '../../app/AppContext';
import { categoryColors, formatBytes, formatNumber } from '../../data/format';
import { mountShapesCanvas } from '../../../viz/shapes/canvas.js';
import type { ViewModel } from '../../viz/types';

interface Selection { id: number; outbound: number[]; inbound: number[] }
interface CanvasCleanup { (): void; controls?: { zoomIn: () => void; zoomOut: () => void; fit: () => void } }

export function ShapesCanvas({ vm }: { vm: ViewModel }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const controls = useRef<CanvasCleanup['controls']>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const { openViz } = useApp();

  useEffect(() => {
    if (!canvas.current) return;
    setSelection(null);
    const cleanup = mountShapesCanvas(canvas.current, null, null, vm, {
      catColor: (category: keyof typeof categoryColors) => categoryColors[category] || categoryColors.other,
      onOpenViz: openViz,
    }, {
      onSelect: (id: number, references: Omit<Selection, 'id'>) => setSelection({ id, ...references }),
      onReset: () => setSelection(null),
    }) as CanvasCleanup;
    controls.current = cleanup.controls || null;
    return () => { controls.current = null; cleanup(); };
  }, [vm, openViz]);

  return <Box className="shapes-graph-content">
    <Box className="shapes-canvas-wrap">
      <canvas ref={canvas} className="shapes-canvas" tabIndex={0} aria-label="Exact structural shapes graph" />
      <ButtonGroup size="small" variant="contained" sx={{ position: 'absolute', top: 8, right: 8 }}>
        <Button aria-label="Zoom out" onClick={() => controls.current?.zoomOut()}>−</Button>
        <Button aria-label="Zoom in" onClick={() => controls.current?.zoomIn()}>+</Button>
        <Button onClick={() => controls.current?.fit()}>Fit</Button>
      </ButtonGroup>
    </Box>
    <Paper variant="outlined" className="shapes-side-panel">
      <ShapeSidePanel vm={vm} selection={selection} onOpen={(className) => openViz('flow', vm.dumpId, className)} />
    </Paper>
  </Box>;
}

function ShapeSidePanel({ vm, selection, onOpen }: { vm: ViewModel; selection: Selection | null; onOpen: (className: string) => void }) {
  const [visible, setVisible] = useState(300);
  useEffect(() => setVisible(300), [selection]);
  if (!selection) return <ShapeSummary vm={vm} />;
  const node = vm.nodes[selection.id];
  const totalReferences = selection.outbound.length + selection.inbound.length;
  const rows = [
    ...selection.outbound.map((index) => ({ index, outbound: true })),
    ...selection.inbound.map((index) => ({ index, outbound: false })),
  ];
  return <Box sx={{ maxHeight: '100%', overflow: 'auto' }} onScroll={(event) => {
    const target = event.currentTarget;
    if (target.scrollTop + target.clientHeight > target.scrollHeight - 600) setVisible((count) => Math.min(totalReferences, count + 300));
  }}>
    <Typography variant="subtitle2" color="secondary" className="class-name">{node.short} <span className="muted">#{selection.id}</span></Typography>
    <Typography variant="caption" color="text.secondary" className="class-name">{node.label}</Typography>
    <Typography variant="caption" display="block" color="text.secondary" mt={1}>{node.kind}{node.sccSize > 1 ? ` · SCC of ${node.sccSize} members` : ''}</Typography>
    <Metric label="objects" value={`${formatNumber(node.count)}${node.sccSize > 1 ? ` = ${formatNumber(Math.round(node.count / node.sccSize))} instances × ${node.sccSize}` : ''}`} />
    <Metric label="shallow total" value={formatBytes(node.st)} />
    <Metric label="shallow min … max" value={`${formatBytes(node.smin)} … ${formatBytes(node.smax)}`} />
    {node.internalEdges > 0 && <Metric label="internal edges / instance" value={formatNumber(node.internalEdges)} />}
    {node.kind === 'scc' && <Typography variant="caption" display="block" color="text.secondary" mt={1}>
      {node.sccComplete ? `${node.members.length} canonical members and all internal references are rendered.`
        : node.members ? `Incomplete member graph: ${node.members.length} of ${node.sccSize} labels stored.`
          : 'Legacy artifact has no SCC member graph; re-analyze to render it.'}
    </Typography>}
    {node.plain && <Button size="small" variant="contained" sx={{ mt: 1 }} onClick={() => onOpen(node.label)}>Open flow</Button>}
    <Typography variant="overline" display="block" color="text.secondary" mt={2}>References — {formatNumber(totalReferences)}</Typography>
    {rows.slice(0, visible).map(({ index, outbound }) => {
      const edge = vm.layout.edges[index];
      const other = vm.nodes[outbound ? edge.t : edge.s];
      return <Box key={`${index}-${outbound}`} py={0.5} borderBottom={1} borderColor="divider">
        <Typography variant="body2" className="class-name">{other.short}</Typography>
        <Stack direction="row" justifyContent="space-between" gap={1}>
          <Typography variant="caption" color="text.secondary">{outbound ? 'holds' : 'held by'}</Typography>
          <Typography variant="caption" className="numeric">×{formatNumber(edge.mult)}{vm.nodes[edge.s].edgesPerMember ? ' / instance' : ''} · {formatNumber(edge.total)} total</Typography>
        </Stack>
      </Box>;
    })}
    {visible < totalReferences && <Typography variant="caption" color="text.secondary">Scroll for more ({formatNumber(totalReferences - visible)} remaining)</Typography>}
  </Box>;
}

function ShapeSummary({ vm }: { vm: ViewModel }) {
  const total = vm.nodes.reduce((sum: number, node: ViewModel) => sum + node.st, 0);
  const analysis = vm.analysis;
  return <Box>
    <Typography variant="body2" color="text.secondary">{analysis?.extractionMode?.type || 'legacy heap'}{!analysis && ' — re-analyze to use the current pipeline'}</Typography>
    <Typography variant="body2" color="text.secondary" mt={1}>
      {formatNumber(vm.shapeCount)} shapes over {formatNumber(vm.reachable)} heap objects · {formatBytes(total)} shallow · {vm.layout.layerCount} layers.
    </Typography>
    <Typography variant="body2" mt={2}>Exact structural collapse: every non-class heap object, no sampling.
      Plain node size = {vm.metric === 'count' ? 'collapsed object count' : 'shallow total'}; edge width = projected reference count.</Typography>
    <Typography variant="body2" color="text.secondary" mt={1}>Click a shape for details. Double-click a plain class to open flow.
      Zoom into SCC boxes for every stored member and internal connection. Sub-pixel detail becomes dots; nothing is removed.</Typography>
    {analysis && (Number.isFinite(analysis.sourceEdges) || Number.isFinite(analysis.projectedEdges)) &&
      <Typography variant="body2" color="text.secondary" mt={2}>
        {Number.isFinite(analysis.sourceEdges) && <>Source refs: {formatNumber(analysis.sourceEdges)}</>}
        {Number.isFinite(analysis.sourceEdges) && Number.isFinite(analysis.projectedEdges) && ' · '}
        {Number.isFinite(analysis.projectedEdges) && <>Projected refs: {formatNumber(analysis.projectedEdges)}</>}
      </Typography>}
    {analysis?.relaxation && <Typography variant="body2" color="text.secondary" mt={1}>
      Structural relaxation: {analysis.relaxation.file || 'rules'}@{analysis.relaxation.sha1 || '?'} · population and byte totals remain exact.
    </Typography>}
  </Box>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <Stack direction="row" justifyContent="space-between" gap={1} mt={0.5}>
    <Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="caption">{value}</Typography>
  </Stack>;
}
