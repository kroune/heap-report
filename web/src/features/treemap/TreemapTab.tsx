import { useMemo, useState } from 'react';
import {
  Box, Breadcrumbs, Button, Card, Chip, Link, Stack, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import type { EChartsOption } from 'echarts';
import { useApp } from '../../app/AppContext';
import { EChart } from '../../components/EChart';
import { EmptyState, ErrorState, LoadingState } from '../../components/QueryState';
import { categoryColors, formatBytes, formatNumber } from '../../data/format';
import { useTrees } from '../../data/queries';
import type { DumpInfo, TreeNode } from '../../data/types';
import { nodeCategory, nodeCount, nodeValue, shadeCategory, type TreeMetric } from './treemapUtils';

const metrics: { value: TreeMetric; label: string }[] = [
  { value: 'r', label: 'Retained heap' }, { value: 's', label: 'Shallow heap' }, { value: 'c', label: 'Instance count' },
];

export function TreemapTab({ dump, inline }: { dump: DumpInfo | null; inline: boolean }) {
  const { openViz } = useApp();
  const [metric, setMetric] = useState<TreeMetric>('r');
  const [zoomPath, setZoomPath] = useState<string[]>([]);
  const blocked = !inline && (!dump || dump.state === 'remote' || dump.state === 'failed');
  const query = useTrees(dump?.id || null, !blocked);

  const baseTree = metric === 'r' ? query.data?.trees.dom : query.data?.trees.hist;
  const root = useMemo(() => {
    let node = baseTree;
    for (const name of zoomPath) {
      node = node?.children?.find((child) => child.name === name);
      if (!node) return baseTree;
    }
    return node;
  }, [baseTree, zoomPath]);

  const stats = query.data?.stats;
  const total = metric === 'r' ? stats?.totalRetained : metric === 's' ? stats?.totalShallow : stats?.totalObjects;
  const formatMetric = (value: number) => metric === 'c' ? formatNumber(value) : formatBytes(value);

  const option = useMemo<EChartsOption>(() => ({
    backgroundColor: 'transparent',
    tooltip: {
      confine: true,
      formatter: (params: unknown) => {
        const { data } = params as { data?: { source?: TreeNode; value: number } };
        const node = data?.source;
        if (!node) return '';
        const value = nodeValue(node, metric);
        const percentage = total ? (100 * value / total).toFixed(2) : '0.00';
        const perModule = stats?.modules ? value / stats.modules : value;
        return `<strong>${escapeHtml(node.disp || node.name)}</strong><br/>` +
          `${metrics.find((item) => item.value === metric)?.label}: <b>${formatMetric(value)}</b> (${percentage}%)<br/>` +
          `Instances: ${formatNumber(nodeCount(node))}<br/>` +
          (metric === 'c' ? '' : `Per module: ${formatBytes(perModule)}<br/>`) +
          (node.leaf && node.r ? `Retained / instance: ${formatBytes(node.r / Math.max(1, node.c || 1))}<br/>` : '') +
          `<span style="color:#8b94a3">${node.leaf ? 'Click to open class' : 'Click to zoom'}</span>`;
      },
    },
    series: [{
      type: 'treemap',
      data: (root?.children || []).filter((node) => nodeValue(node, metric) > 0).map((node) => ({
        name: node.name.split('.').pop() || node.name,
        value: nodeValue(node, metric),
        source: node,
        itemStyle: { color: shadeCategory(nodeCategory(node), node.name) },
      })),
      width: '100%', height: '100%', top: 0, left: 0,
      roam: false, nodeClick: false, breadcrumb: { show: false },
      label: { show: true, formatter: '{b}', fontSize: 13 },
      upperLabel: { show: false },
      itemStyle: { borderColor: '#0d1117', borderWidth: 1, gapWidth: 1 },
      emphasis: { label: { fontWeight: 'bold' } },
      visibleMin: 1,
    }],
  }), [root, metric, total, stats, formatMetric]);

  if (!dump) return <EmptyState>Select a heap dump to explore its tree.</EmptyState>;
  if (blocked) return <EmptyState>Dump “{dump.id}” is {dump.state}.{dump.error ? ` ${dump.error}` : ''}</EmptyState>;
  if (query.isLoading) return <LoadingState>Loading heap trees…</LoadingState>;
  if (query.isError) return <ErrorState error={query.error} title="Unable to load heap trees" />;

  const children = root?.children || [];
  if (!children.some((node) => nodeValue(node, metric) > 0)) return <EmptyState>No tree data for this metric.</EmptyState>;

  const onChartClick = (params: unknown) => {
    const node = (params as { data?: { source?: TreeNode } }).data?.source;
    if (!node || !dump) return;
    if (node.leaf) openViz('anatomy', dump.id, node.disp || node.name);
    else setZoomPath((path) => [...path, node.name]);
  };

  return <Card variant="outlined">
    <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" p={1.5}>
      <ToggleButtonGroup exclusive size="small" value={metric} onChange={(_event, value: TreeMetric | null) => {
        if (value) { setMetric(value); setZoomPath([]); }
      }}>
        {metrics.map((item) => <ToggleButton key={item.value} value={item.value}>{item.label}</ToggleButton>)}
      </ToggleButtonGroup>
      <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 900 }}>
        Retained view shows top-level dominators only; dominated objects roll up into their owner.
      </Typography>
    </Stack>
    <Breadcrumbs sx={{ px: 2, pb: 1 }} separator="›">
      <Link component="button" color="secondary" onClick={() => setZoomPath([])}>all</Link>
      {zoomPath.map((name, index) => <Link component="button" color="secondary" key={name}
        onClick={() => setZoomPath((path) => path.slice(0, index + 1))}>{name}</Link>)}
    </Breadcrumbs>
    <EChart option={option} onClick={onChartClick} height="max(480px, calc(100vh - 320px))" />
    <Stack direction="row" gap={1} flexWrap="wrap" p={1.5}>
      {(baseTree?.children || []).map((node) => {
        const value = nodeValue(node, metric);
        return <Chip key={node.name} clickable onClick={() => setZoomPath([node.name])}
          avatar={<Box sx={{ width: 11, height: 11, ml: 1, borderRadius: 0.4, bgcolor: categoryColors[nodeCategory(node)] }} />}
          label={`${node.name} — ${formatMetric(value)} (${total ? (100 * value / total).toFixed(1) : '0.0'}%)`} />;
      })}
      <Button size="small" onClick={() => query.refetch()} sx={{ ml: 'auto' }}>Refresh</Button>
    </Stack>
  </Card>;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]!);
}
