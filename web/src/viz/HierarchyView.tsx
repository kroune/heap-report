import { useEffect, useMemo, useState } from 'react';
import { Box, Breadcrumbs, Link, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useApp } from '../app/AppContext';
import { categoryColors, formatBytes, formatNumber } from '../data/format';
import { layoutCells } from './prepare';
import { useElementWidth } from './useElementWidth';
import type { ViewModel, VisualizationViewProps } from './types';

type Metric = 'r' | 's' | 'n';
const valueOf = (node: ViewModel, metric: Metric) => metric === 'r' ? node.r : metric === 's' ? node.s : node.c;

export function HierarchyView({ vm }: VisualizationViewProps) {
  const { openViz } = useApp();
  const { ref, width } = useElementWidth<HTMLDivElement>();
  const [metric, setMetric] = useState<Metric>(vm.metric || 'r');
  const [stack, setStack] = useState<ViewModel[]>([vm.tree]);

  useEffect(() => {
    const next = [vm.tree];
    for (const name of vm.path || []) {
      const child = next[next.length - 1].children?.find((node: ViewModel) => node.name === name);
      if (!child) { next.length = 1; break; }
      next.push(child);
    }
    setStack(next);
  }, [vm.tree, vm.path]);

  const root = stack[stack.length - 1];
  const layout = useMemo(() => layoutCells(root, width, metric), [root, width, metric]);
  const total = Math.max(1, valueOf(root, metric));
  const formatValue = (value: number) => metric === 'n' ? formatNumber(value) : formatBytes(value);

  return <Box ref={ref}>
    <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
      <ToggleButtonGroup exclusive size="small" value={metric} onChange={(_event, value) => value && setMetric(value)}>
        <ToggleButton value="r">Retained</ToggleButton><ToggleButton value="s">Shallow</ToggleButton><ToggleButton value="n">Objects</ToggleButton>
      </ToggleButtonGroup>
      <Typography variant="caption" color="text.secondary">
        Width = share of parent · click to descend · double-click a class to open anatomy · slivers fold into “smaller”
      </Typography>
    </Stack>
    <Breadcrumbs separator="›" sx={{ my: 1 }}>
      {stack.map((node, index) => <Link component="button" color="secondary" key={`${node.name}-${index}`}
        onClick={() => setStack((current) => current.slice(0, index + 1))}>{node.name}</Link>)}
    </Breadcrumbs>
    <Box component="svg" viewBox={`0 0 ${width} ${layout.H}`} role="img" aria-label="Dominator hierarchy"
      sx={{ display: 'block', width: '100%', height: Math.min(900, layout.H), minHeight: 180 }}>
      {layout.cells.map((cell: ViewModel, index: number) => {
        const node = cell.n;
        const value = valueOf(node, metric);
        const label = node.name.length > Math.floor((cell.w - 8) / 7.3)
          ? `${node.name.slice(0, Math.max(1, Math.floor((cell.w - 8) / 7.3) - 1))}…` : node.name;
        return <g key={`${node.name}-${cell.depth}-${index}`} style={{ cursor: 'pointer' }}
          onClick={() => node.children?.length && setStack((current) => [...current, node])}
          onDoubleClick={() => node.leaf && node.disp && !node.name.startsWith('·') && openViz('anatomy', vm.dumpId, node.disp)}>
          <title>{`${node.disp || node.name}\nRetained: ${formatBytes(node.r)} (${(100 * node.r / Math.max(1, vm.tree.r)).toFixed(1)}% of dump)\nShallow: ${formatBytes(node.s)}\nObjects: ${formatNumber(node.c)}\nIn this view: ${(100 * value / total).toFixed(1)}%`}</title>
          <rect x={cell.x} y={cell.depth * layout.ROWH} width={Math.max(0, cell.w - 0.6)} height={layout.ROWH - 1.5}
            rx={1} fill={node.more ? '#3d434f' : categoryColors[node.cat as keyof typeof categoryColors] || categoryColors.other}
            stroke="#0d1117" strokeWidth={0.5} strokeDasharray={node.more ? '3 2' : undefined} />
          {cell.w > 46 && <text x={cell.x + 4} y={cell.depth * layout.ROWH + 15} fill="white" fontSize={12} pointerEvents="none">{label}</text>}
          {cell.w > 150 && <text x={cell.x + cell.w - 6} y={cell.depth * layout.ROWH + 15} fill="white" opacity={0.75}
            fontSize={11} textAnchor="end" pointerEvents="none">{formatValue(value)} · {(100 * value / total).toFixed(1)}%</text>}
        </g>;
      })}
    </Box>
  </Box>;
}
