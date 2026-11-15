import { useEffect, useMemo, useState } from 'react';
import {
  Autocomplete,
  Box,
  Breadcrumbs,
  Button,
  Card,
  Chip,
  Link,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  createFilterOptions,
} from '@mui/material';
import type { EChartsOption } from 'echarts';
import { useApp } from '../../app/AppContext';
import { EChart } from '../../components/EChart';
import { EmptyState, ErrorState, LoadingState } from '../../components/QueryState';
import { categoryColors, formatBytes, formatNumber } from '../../data/format';
import { useTrees } from '../../data/queries';
import type { DumpInfo, TreeNode } from '../../data/types';
import { nodeCategory, nodeCount, nodeValue, shadeCategory, type TreeMetric } from './treemapUtils';

const metrics: { value: TreeMetric; label: string; description: string }[] = [
  { value: 'r', label: 'Retained heap', description: 'Dominator tree · retained heap includes objects owned by each dominator' },
  { value: 's', label: 'Shallow heap', description: 'Histogram tree · shallow heap excludes referenced objects' },
  { value: 'c', label: 'Instance count', description: 'Histogram tree · live instance counts by package and class' },
];

const formatMetric = (metric: TreeMetric, value: number) => metric === 'c' ? formatNumber(value) : formatBytes(value);
const filterBlockOptions = createFilterOptions<TreeNode>({ limit: 200 });

export function TreemapTab({ dump, inline }: { dump: DumpInfo | null; inline: boolean }) {
  const { openViz } = useApp();
  const [metric, setMetric] = useState<TreeMetric>('r');
  const [zoomPath, setZoomPath] = useState<string[]>([]);
  const [blockSearch, setBlockSearch] = useState('');
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
  const visibleNodes = useMemo(() => (root?.children || [])
    .filter((node) => nodeValue(node, metric) > 0)
    .sort((a, b) => nodeValue(b, metric) - nodeValue(a, metric)), [metric, root]);

  const stats = query.data?.stats;
  const total = metric === 'r' ? stats?.totalRetained : metric === 's' ? stats?.totalShallow : stats?.totalObjects;
  const activeMetric = metrics.find((item) => item.value === metric)!;

  useEffect(() => setBlockSearch(''), [metric, zoomPath]);

  const option = useMemo<EChartsOption>(() => ({
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'Inter, Segoe UI, sans-serif', color: '#dfe1e5' },
    tooltip: {
      confine: true,
      position: 'top',
      showDelay: 0,
      hideDelay: 0,
      transitionDuration: 0,
      displayTransition: false,
      backgroundColor: '#2b2d30',
      borderColor: '#4b4e55',
      borderWidth: 1,
      textStyle: { color: '#dfe1e5', fontSize: 12 },
      extraCssText: 'border-radius:4px;box-shadow:0 5px 16px rgba(0,0,0,.35);pointer-events:none;',
      formatter: (params: unknown) => {
        const { data } = params as { data?: { source?: TreeNode; value: number } };
        const node = data?.source;
        if (!node) return '';
        const value = nodeValue(node, metric);
        const percentage = total ? (100 * value / total).toFixed(2) : '0.00';
        const perModule = stats?.modules ? value / stats.modules : value;
        return `<strong>${escapeHtml(node.disp || node.name)}</strong><br/>` +
          `${activeMetric.label}: <b>${formatMetric(metric, value)}</b> (${percentage}% of heap)<br/>` +
          `Instances: ${formatNumber(nodeCount(node))}<br/>` +
          (metric === 'c' ? '' : `Per module: ${formatBytes(perModule)}<br/>`) +
          (node.leaf && node.r ? `Retained / instance: ${formatBytes(node.r / Math.max(1, node.c || 1))}<br/>` : '') +
          `<span style="color:#9da0a8">${node.leaf ? 'Click to open class' : 'Click to zoom'}</span>`;
      },
    },
    series: [{
      type: 'treemap',
      animation: false,
      animationDuration: 0,
      animationDurationUpdate: 0,
      data: visibleNodes.map((node) => ({
        name: node.name.split('.').pop() || node.name,
        value: nodeValue(node, metric),
        source: node,
        itemStyle: { color: shadeCategory(nodeCategory(node), node.name) },
      })),
      width: '100%', height: '100%', top: 0, left: 0,
      roam: false, nodeClick: false, breadcrumb: { show: false },
      label: { show: true, formatter: '{b}', fontSize: 12, fontWeight: 'normal', overflow: 'truncate' },
      upperLabel: { show: false },
      itemStyle: { borderColor: '#1e1f22', borderWidth: 1, gapWidth: 1 },
      emphasis: {
        label: { fontWeight: 'normal', color: '#ffffff' },
        itemStyle: { borderColor: '#f0f0f0', borderWidth: 2 },
      },
      visibleMin: 0,
    }],
  }), [activeMetric.label, metric, stats, total, visibleNodes]);

  if (!dump) return <EmptyState>Select a heap dump to explore its tree.</EmptyState>;
  if (blocked) return <EmptyState>Dump “{dump.id}” is {dump.state}.{dump.error ? ` ${dump.error}` : ''}</EmptyState>;
  if (query.isLoading) return <LoadingState>Loading heap trees…</LoadingState>;
  if (query.isError) return <ErrorState error={query.error} title="Unable to load heap trees" />;

  const activateNode = (node: TreeNode, fromRoot = false) => {
    if (!dump) return;
    if (node.leaf) openViz('anatomy', dump.id, node.disp || node.name);
    else setZoomPath((path) => [...(fromRoot ? [] : path), node.name]);
  };

  const onChartClick = (params: unknown) => {
    const node = (params as { data?: { source?: TreeNode } }).data?.source;
    if (node) activateNode(node);
  };

  return <Card variant="outlined" className="tool-panel treemap-view">
    <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" className="view-toolbar">
      <ToggleButtonGroup exclusive size="small" value={metric} onChange={(_event, value: TreeMetric | null) => {
        if (value) { setMetric(value); setZoomPath([]); }
      }}>
        {metrics.map((item) => <ToggleButton key={item.value} value={item.value}>{item.label}</ToggleButton>)}
      </ToggleButtonGroup>
      <Typography variant="body2" color="text.secondary" className="treemap-metric-description">
        {activeMetric.description}
      </Typography>
      <Autocomplete<TreeNode>
        className="treemap-node-search"
        size="small"
        options={visibleNodes}
        value={null}
        inputValue={blockSearch}
        onInputChange={(_event, value, reason) => {
          if (reason === 'input' || reason === 'clear') setBlockSearch(value);
        }}
        onChange={(_event, node) => {
          setBlockSearch('');
          if (node) activateNode(node);
        }}
        filterOptions={filterBlockOptions}
        getOptionLabel={(node) => node.disp || node.name}
        isOptionEqualToValue={(option, value) => option.name === value.name}
        autoHighlight
        clearOnEscape
        noOptionsText="No matching blocks"
        renderOption={(props, node) => {
          const { key, ...optionProps } = props;
          return <li key={key} {...optionProps}>
            <Box className="treemap-option-name">{node.disp || node.name}</Box>
            <Box component="span" className="numeric">{formatMetric(metric, nodeValue(node, metric))}</Box>
          </li>;
        }}
        renderInput={(params) => <TextField {...params}
          placeholder="Find block, including tiny ones…"
          inputProps={{ ...params.inputProps, 'aria-label': 'Find treemap block' }} />}
      />
    </Stack>
    <Breadcrumbs sx={{ px: 2, pb: 1 }} separator="›">
      <Link component="button" color="secondary" onClick={() => setZoomPath([])}>all</Link>
      {zoomPath.map((name, index) => <Link component="button" color="secondary" key={name}
        onClick={() => setZoomPath((path) => path.slice(0, index + 1))}>{name}</Link>)}
    </Breadcrumbs>
    {visibleNodes.length ? <EChart option={option} onClick={onChartClick} height="100%" fill />
      : <Box className="treemap-empty"><EmptyState>
        No blocks at this level for {activeMetric.label.toLowerCase()}.{' '}
        <Button size="small" onClick={() => setZoomPath([])}>Back to all</Button>
      </EmptyState></Box>}
    <Stack direction="row" gap={0.75} flexWrap="wrap" className="treemap-legend">
      {(baseTree?.children || []).filter((node) => nodeValue(node, metric) > 0).map((node) => {
        const value = nodeValue(node, metric);
        return <Chip key={node.name} clickable onClick={() => activateNode(node, true)}
          avatar={<Box sx={{ width: 11, height: 11, ml: 1, borderRadius: 0.4, bgcolor: categoryColors[nodeCategory(node)] }} />}
          label={`${node.name} — ${formatMetric(metric, value)} (${total ? (100 * value / total).toFixed(1) : '0.0'}%)`} />;
      })}
      <Button size="small" onClick={() => query.refetch()} sx={{ ml: 'auto' }}>Refresh</Button>
    </Stack>
  </Card>;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]!);
}
