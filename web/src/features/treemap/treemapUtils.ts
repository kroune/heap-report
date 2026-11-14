import { categoryColors, categoryOf } from '../../data/format';
import type { Category, TreeNode } from '../../data/types';

export type TreeMetric = 'r' | 's' | 'c';

export function nodeValue(node: TreeNode, metric: TreeMetric): number {
  return node.leaf ? node[metric] || 0 : (node.children || []).reduce((sum, child) => sum + nodeValue(child, metric), 0);
}

export function nodeCount(node: TreeNode): number {
  return node.leaf ? node.c || 0 : (node.children || []).reduce((sum, child) => sum + nodeCount(child), 0);
}

export function nodeCategory(node: TreeNode): Category {
  return node.cat || categoryOf(node.disp || node.name || '');
}

export function shadeCategory(category: Category, seed: string): string {
  let hash = 0;
  for (let index = 0; index < seed.length; index += 1) hash = (hash * 31 + seed.charCodeAt(index)) >>> 0;
  const factor = 0.82 + (hash % 100) / 100 * 0.36;
  const channels = categoryColors[category].match(/\w\w/g)!.map((value) =>
    Math.min(255, Math.round(parseInt(value, 16) * factor)));
  return `rgb(${channels[0]},${channels[1]},${channels[2]})`;
}
