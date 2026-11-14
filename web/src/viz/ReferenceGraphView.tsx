import { useEffect, useMemo, useState } from 'react';
import {
  ReactFlow, Background, Controls, MiniMap, BaseEdge, getBezierPath, MarkerType, Handle, Position,
  type Edge, type EdgeProps, type Node, type NodeProps,
} from '@xyflow/react';
import {
  Alert, Box, Button, Chip, Collapse, IconButton, Paper, Slider, Stack, TextField,
  ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material';
import PushPinIcon from '@mui/icons-material/PushPin';
import HelpOutlineIcon from '@mui/icons-material/HelpOutline';
import { useApp } from '../app/AppContext';
import { categoryColors, formatBytes, formatNumber, shortClassName } from '../data/format';
import { AnalyzePrompt, SamplePicker } from './AnalysisControls';
import { DEFAULT_PINS, parseBytes, savePins } from './flowPins';
import type { ViewModel, VisualizationViewProps } from './types';

interface HeapNodeData extends Record<string, unknown> {
  label: string;
  fullName: string;
  color: string;
  shared: boolean;
  self: boolean;
  root: boolean;
  nested: boolean;
  selected: boolean;
  wedge: number;
}
type HeapNode = Node<HeapNodeData, 'heap'>;

function wedgePath(radius: number, fraction: number): string {
  if (fraction >= 0.995) return `M ${radius} 0 A ${radius} ${radius} 0 1 1 ${-radius} 0 A ${radius} ${radius} 0 1 1 ${radius} 0 Z`;
  const angle = 2 * Math.PI * fraction - Math.PI / 2;
  return `M 0 0 L 0 ${-radius} A ${radius} ${radius} 0 ${fraction > 0.5 ? 1 : 0} 1 ${(radius * Math.cos(angle)).toFixed(2)} ${(radius * Math.sin(angle)).toFixed(2)} Z`;
}

function HeapNodeView({ data }: NodeProps<HeapNode>) {
  return <Tooltip title={data.fullName} placement="top" enterDelay={500}>
    <Box className={`heap-graph-node ${data.selected ? 'selected' : ''} ${data.root ? 'root' : ''} ${data.shared ? 'shared' : ''}`}
      sx={{ bgcolor: data.color }}>
      {data.wedge > 0 && <Box component="svg" viewBox="-20 -20 40 40" className="heap-node-wedge">
        <path d={wedgePath(20, data.wedge)} />
      </Box>}
      <Handle type="target" position={Position.Top} style={{ opacity: 0, pointerEvents: 'none' }} />
      <Handle type="source" position={Position.Bottom} style={{ opacity: 0, pointerEvents: 'none' }} />
      {data.self && <span className="heap-self-loop" title="Self-reference">↻</span>}
      <span className="heap-node-label">{data.label}</span>
    </Box>
  </Tooltip>;
}

function HeapEdgeView(props: EdgeProps) {
  const [path] = getBezierPath(props);
  return <>
    <BaseEdge id={props.id} path={path} style={props.style} markerEnd={props.markerEnd} markerStart={props.markerStart} />
    <path d={path} fill="none" stroke="transparent" strokeWidth={14} style={{ pointerEvents: 'stroke' }}>
      <title>{String(props.data?.tip || '')}</title>
    </path>
  </>;
}
const nodeTypes = { heap: HeapNodeView };
const edgeTypes = { heap: HeapEdgeView };

function GraphSidePanel({ vm, flow, selected, scale, onOpen, onPin }: {
  vm: ViewModel; flow: boolean; selected: number | null; scale: 'sample' | 'global';
  onOpen: (className: string) => void; onPin: (className: string, pin: boolean) => void;
}) {
  const layout = vm.layout;
  const global = scale === 'global';
  const fxB = (value: number) => `${global ? '≈ ' : ''}${formatBytes(value * (global ? vm.factor : 1))}`;
  const fxN = (value: number) => `${global ? '≈ ' : ''}${formatNumber(value * (global ? vm.factor : 1))}`;
  const fxR = (value: number) => `${global ? '≈ ' : ''}${formatBytes(value * (global ? vm.rfactor : 1))}`;
  if (selected == null || !vm.details[selected]) return <Box>
    <Typography variant="body2" color="text.secondary">
      {flow ? layout.nodes.filter((node: ViewModel) => !node.dropped).length : layout.nodes.length} {flow && vm.split ? 'copies' : 'classes'} · {layout.edges.length} connections · {layout.layerCount} layers
      {flow && layout.colCount ? ` · ${layout.colCount} pinned` : !flow && layout.laneCount ? ` · ${layout.laneCount} value types` : ''}
      {layout.cycleCount ? ` · ${layout.cycleCount} cycle edges` : ''}
      {flow && layout.dropCount ? ` · ${layout.dropCount} unreachable after filtering` : ''}
    </Typography>
    <Typography variant="body2" color="text.secondary" mt={2}>
      Node size = {flow ? 'inclusive retained memory reachable downward' : 'total dominator-retained bytes'}.
      Purple rings indicate at least three holder classes. Dashed edges close class-level cycles.
      {flow && ' Amber wedges are shared inclusive-retained bytes. Dotted edges pass through filtered classes.'}
    </Typography>
    <Typography variant="body2" mt={2}>Click a node for holder/held references. Double-click to open its anatomy.</Typography>
    {global && <Typography variant="caption" display="block" color="text.secondary" mt={1}>Objects/shallow are estimated per instance; retained is anchored to the exact union retained set.</Typography>}
  </Box>;

  const detail = vm.details[selected];
  const node = layout.nodes[selected];
  const references = (title: string, rows: ViewModel[]) => <Box mt={1.5}>
    <Typography variant="overline" color="text.secondary">{title}</Typography>
    {rows.length ? rows.map((ref: ViewModel, index: number) => <Box key={`${ref.cls}-${ref.f}-${index}`} py={0.5} borderBottom={1} borderColor="divider">
      <Typography variant="body2" className="class-name">{ref.short}</Typography>
      <Stack direction="row" justifyContent="space-between" gap={1}>
        <Typography variant="caption" color="text.secondary" className="class-name">{ref.f}</Typography>
        <Typography variant="caption" className="numeric">×{formatNumber(ref.n)} · {fxR(ref.b)}</Typography>
      </Stack>
    </Box>) : <Typography color="text.secondary">—</Typography>}
  </Box>;
  return <Box>
    <Typography variant="subtitle2" color="secondary" className="class-name">{detail.short}</Typography>
    <Typography variant="caption" color="text.secondary" className="class-name">{detail.cls}</Typography>
    {flow && vm.split && <Typography variant="caption" display="block" color="text.secondary" mt={0.5}>
      {node.holders == null ? 'Residual copy: held by other holders' : node.holders.length ? `Held by ${node.holders.map(shortClassName).join(', ')}` : 'No holder inside the extracted set'}
    </Typography>}
    <Box mt={1}>
      {flow && vm.hasReach ? <>
        <MetricRow label="retained (incl. shared)" value={fxR(detail.rincl)} strong />
        <MetricRow label="exclusive" value={fxR(detail.rincl - detail.rsh)} />
        <MetricRow label="shared (wedge)" value={fxR(detail.rsh)} />
        <MetricRow label="dominator retained" value={fxR(detail.r)} />
      </> : <MetricRow label="retained" value={fxR(detail.r)} strong />}
      <MetricRow label="shallow" value={fxB(detail.s)} />
      <MetricRow label="objects" value={`${fxN(detail.n)}${global ? ' (est.)' : ''}`} />
    </Box>
    <Typography variant="caption" display="block" color="text.secondary" mt={1}>
      {flow && detail.col ? 'Pinned column · ' : flow && detail.nestedIn >= 0 ? `Nested in ${vm.details[detail.nestedIn].short} · ` : !flow && detail.lane ? 'Value lane · outbound references not drawn · ' : ''}
      layer {detail.layer} · held by {detail.ins.length} · holds {detail.outs.length}{detail.nestCount ? ` · ${detail.nestCount} nested` : ''}
    </Typography>
    <Stack direction="row" spacing={1} mt={1}>
      <Button size="small" variant="contained" onClick={() => onOpen(detail.cls)}>Open class</Button>
      {flow && (detail.col || (detail.nestedIn < 0 && selected !== layout.rootTop)) &&
        <Button size="small" startIcon={<PushPinIcon />} onClick={() => onPin(detail.cls, !detail.col)}>{detail.col ? 'Unpin' : 'Pin'}</Button>}
    </Stack>
    {references('held by — inbound refs', detail.ins)}
    {references('holds — outbound refs', detail.outs)}
  </Box>;
}

function MetricRow({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return <Stack direction="row" justifyContent="space-between" gap={1}>
    <Typography variant="caption" color="text.secondary">{label}</Typography>
    <Typography variant="caption" fontWeight={strong ? 700 : 400}>{value}</Typography>
  </Stack>;
}

function ReferenceGraphContent({ vm, flow, setParams, inline, analyze, analysisStatus, analysisPending }: VisualizationViewProps & { flow: boolean }) {
  const { openViz } = useApp();
  const [selected, setSelected] = useState<number | null>(null);
  const [topDraft, setTopDraft] = useState<number>(vm.top);
  const [scale, setScale] = useState<'sample' | 'global'>(vm.scale || 'sample');
  const [showCycles, setShowCycles] = useState(vm.cyc !== false);
  const [pinsOpen, setPinsOpen] = useState(Boolean(vm.pinsOpen));
  const [helpOpen, setHelpOpen] = useState(false);
  const [pinInput, setPinInput] = useState('');
  const [pinError, setPinError] = useState('');
  const [minInput, setMinInput] = useState(vm.minR ? formatBytes(vm.minR) : '');
  const [minError, setMinError] = useState('');

  useEffect(() => {
    setSelected(null);
    setTopDraft(vm.top);
    setScale(vm.scale || 'sample');
    setShowCycles(vm.cyc !== false);
    setMinInput(vm.minR ? formatBytes(vm.minR) : '');
  }, [vm]);

  const refetch = (patch: Record<string, unknown>) => setParams({
    top: vm.top, samples: vm.samples, scale, objCount: vm.objCount, cyc: showCycles,
    ...(flow ? { pins: vm.pins, minR: vm.minR, split: vm.split, pinsOpen } : {}), ...patch,
  });
  const setPins = (pins: string[]) => { savePins(pins); refetch({ pins }); };
  const nodes = useMemo<HeapNode[]>(() => vm.layout.nodes.flatMap((node: ViewModel, index: number) => {
    if (flow && node.dropped) return [];
    const radius = Math.max(4, node.rad || 5);
    return [{
      id: String(index), type: 'heap', position: { x: node.x - radius, y: node.y - radius },
      sourcePosition: Position.Bottom, targetPosition: Position.Top,
      width: radius * 2, height: radius * 2, draggable: false, connectable: false, selectable: false,
      data: {
        label: vm.labels[index], fullName: node.cls, color: categoryColors[node.cat as keyof typeof categoryColors] || categoryColors.other,
        shared: node.shared, self: node.self, root: flow && index === vm.layout.rootTop,
        nested: flow && node.nestedIn >= 0, selected: selected === index,
        wedge: flow && node.rincl > 0 ? node.rsh / node.rincl : 0,
      },
      style: { width: radius * 2, height: radius * 2 },
    }];
  }), [vm, flow, selected]);
  const visibleNodeIds = useMemo(() => new Set(nodes.map((node) => node.id)), [nodes]);
  const edges = useMemo<Edge[]>(() => vm.layout.edges.flatMap((edge: ViewModel, index: number) => {
    if (edge.d == null || (!showCycles && edge.cyc) || !visibleNodeIds.has(String(edge.s)) || !visibleNodeIds.has(String(edge.t))) return [];
    const related = selected != null && (edge.s === selected || edge.t === selected || edge.os === selected || edge.ot === selected || edge.ros === selected || edge.rot === selected);
    const color = selected == null ? '#4a5568' : !related ? '#4a5568' : edge.bi ? '#9b7ede' : edge.t === selected || edge.ot === selected ? '#f0883e' : '#79b8ff';
    const marker = (end: boolean) => {
      const enabled = end ? edge.aT : edge.aS;
      return enabled ? { type: MarkerType.ArrowClosed, color, width: 14, height: 14 } : undefined;
    };
    return [{
      id: `edge-${index}`, source: String(edge.s), target: String(edge.t), type: 'heap',
      data: { tip: edge.tip }, markerStart: marker(false), markerEnd: marker(true),
      style: {
        stroke: color, strokeWidth: edge.cyc ? 1 : edge.w, opacity: selected == null ? edge.cyc ? 0.15 : 0.5 : related ? 0.95 : 0.08,
        strokeDasharray: edge.via ? '1 4' : edge.cyc ? '4 5' : undefined,
      },
    }];
  }), [vm, selected, showCycles, visibleNodeIds]);

  const addPin = () => {
    const value = pinInput.trim();
    if (!value) return;
    if (vm.pins.includes(value)) { setPinError('Already pinned'); return; }
    setPinError(''); setPinInput(''); setPins([...vm.pins, value]);
  };
  const applyMinimum = () => {
    if (!minInput.trim()) { setMinError(''); if (vm.minR) refetch({ minR: 0 }); return; }
    const bytes = parseBytes(minInput);
    if (bytes == null) { setMinError('Use 5m, 500k, or 1.5g'); return; }
    setMinError(''); refetch({ minR: bytes });
  };

  return <Box className="reference-graph-shell">
    <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" mb={1}>
      <ToggleButtonGroup size="small" exclusive value={scale} onChange={(_event, value) => value && setScale(value)}>
        <ToggleButton value="sample">{vm.samples} samples</ToggleButton>
        <ToggleButton value="global" disabled={!vm.objCount}>{vm.objCount ? `× ${formatNumber(vm.objCount)} est.` : 'global unknown'}</ToggleButton>
      </ToggleButtonGroup>
      <SamplePicker available={vm.available} current={vm.samples} inline={inline}
        onSwitch={(samples) => refetch({ samples })} onAnalyze={analyze} disabled={analysisPending} />
      <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 190 }}>
        <Typography variant="caption" color="text.secondary">top</Typography>
        <Slider size="small" min={Math.min(30, vm.maxNodes)} max={vm.maxNodes} value={topDraft}
          onChange={(_event, value) => setTopDraft(Number(value))}
          onChangeCommitted={(_event, value) => refetch({ top: value })} valueLabelDisplay="auto" />
        <Typography variant="caption">{topDraft}</Typography>
      </Stack>
      {flow && <>
        <TextField size="small" label="min retained" placeholder="off (e.g. 5m)" value={minInput} error={Boolean(minError)}
          helperText={minError} onChange={(event) => { setMinInput(event.target.value); setMinError(''); }}
          onBlur={applyMinimum} onKeyDown={(event) => { if (event.key === 'Enter') applyMinimum(); }} sx={{ width: 150 }} />
        <Button size="small" variant={vm.split ? 'contained' : 'outlined'} disabled={!vm.hasSplit}
          title={vm.hasSplit ? 'One copy per direct holder-class set' : 'Re-analyze to enable holder-set analysis'}
          onClick={() => refetch({ split: !vm.split })}>holders → split</Button>
        <Button size="small" startIcon={<PushPinIcon />} onClick={() => setPinsOpen((open) => !open)}>pinned · {vm.pins.length}</Button>
        <Tooltip title="How to read this graph"><IconButton size="small" onClick={() => setHelpOpen((open) => !open)}><HelpOutlineIcon /></IconButton></Tooltip>
      </>}
      {layoutHasCycles(vm) && <Button size="small" variant={showCycles ? 'outlined' : 'contained'}
        onClick={() => setShowCycles((show) => !show)}>⟲ {vm.layout.cycleCount}</Button>}
    </Stack>

    {flow && <Collapse in={pinsOpen}>
      <Paper variant="outlined" sx={{ p: 1, mb: 1 }}>
        <Stack direction="row" gap={0.75} flexWrap="wrap" alignItems="center">
          <Typography variant="caption" color="text.secondary">Pinned classes stay in the right column:</Typography>
          {vm.pins.map((pin: string) => {
            const present = vm.layout.nodes.some((node: ViewModel) => !node.dropped && node.cls === pin);
            return <Chip key={pin} label={shortClassName(pin)} title={pin} onDelete={() => setPins(vm.pins.filter((item: string) => item !== pin))}
              variant="outlined" size="small" sx={{ opacity: present ? 1 : 0.45 }} />;
          })}
          <TextField size="small" placeholder="full class name" value={pinInput} error={Boolean(pinError)} helperText={pinError}
            onChange={(event) => { setPinInput(event.target.value); setPinError(''); }}
            onKeyDown={(event) => { if (event.key === 'Enter') addPin(); }} sx={{ width: 230 }} />
          <Button size="small" onClick={addPin}>Add</Button><Button size="small" onClick={() => setPins([...DEFAULT_PINS])}>Reset</Button>
        </Stack>
      </Paper>
    </Collapse>}
    {analysisStatus && <Alert severity="info" sx={{ mb: 1 }}>{analysisStatus}</Alert>}

    <Box className="reference-graph-content">
      <Box className="reference-graph-canvas">
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
          fitView fitViewOptions={{ padding: 0.12 }} minZoom={0.1} maxZoom={10}
          onlyRenderVisibleElements nodesDraggable={false} nodesConnectable={false} elementsSelectable={false}
          panOnScroll zoomOnScroll={false} zoomActivationKeyCode={['Control', 'Shift']} zoomOnDoubleClick={false}
          onPaneClick={() => setSelected(null)}
          onNodeClick={(_event, node) => setSelected(Number(node.id))}
          onNodeDoubleClick={(_event, node) => openViz('anatomy', vm.dumpId, node.data.fullName)}>
          <Background color="#232b38" gap={24} />
          <MiniMap pannable zoomable nodeColor={(node) => String(node.data.color || '#7d8590')} />
          <Controls showInteractive={false} />
        </ReactFlow>
        {flow && helpOpen && <Alert severity="info" onClose={() => setHelpOpen(false)} className="flow-help">
          <strong>Controls:</strong> drag or scroll to pan; Ctrl/Shift+scroll to zoom. Node size is inclusive retained;
          amber is shared by at least two sampled roots. Nested circles hold capture back-references.
          Dotted edges contract hidden classes; split copies separate distinct holder sets. Sharing with unsampled
          instances and same-class sharing under one root remain sampling blind spots.
        </Alert>}
      </Box>
      <Paper variant="outlined" className="reference-graph-side">
        <GraphSidePanel vm={vm} flow={flow} selected={selected} scale={scale}
          onOpen={(className) => openViz('anatomy', vm.dumpId, className)}
          onPin={(className, pin) => setPins(pin ? [...vm.pins, className] : vm.pins.filter((item: string) => item !== className))} />
      </Paper>
    </Box>
  </Box>;
}

function layoutHasCycles(vm: ViewModel): boolean {
  return vm.layout.cycleCount > 0;
}

export function ReferenceGraphView(props: VisualizationViewProps & { flow: boolean }) {
  const { vm, flow, inline, analyze, analysisStatus, analysisPending } = props;
  if (vm.notAnalyzed) return <AnalyzePrompt kind={flow ? 'flow graph' : 'reference graph'} inline={inline}
    analyze={analyze} status={analysisStatus} pending={analysisPending} />;
  if (vm.error) return <Alert severity="error">{vm.error}</Alert>;
  return <ReferenceGraphContent {...props} />;
}
