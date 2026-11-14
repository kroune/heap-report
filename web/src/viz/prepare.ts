import { prepare as prepareAnatomy, findClassStats } from '../../viz/anatomy.js';
import { prepare as prepareHierarchy, layoutCells, withValues } from '../../viz/hierarchy.js';
import { prepare as prepareGraph } from '../../viz/graph.js';
import { prepare as prepareFlow } from '../../viz/flow/prepare.js';
import { shapesViewModel } from '../../viz/shapes/prepare.js';
import type { VisualizationKind } from '../app/AppContext';
import type { DumpDataRepository } from '../data/types';
import type { VizParams, ViewModel } from './types';

export function prepareVisualization(kind: VisualizationKind, repository: DumpDataRepository,
  dumpId: string, className: string, params: VizParams): Promise<ViewModel> {
  if (kind === 'anatomy') return prepareAnatomy(repository, dumpId, className, params);
  if (kind === 'hierarchy') return prepareHierarchy(repository, dumpId, className, params);
  if (kind === 'graph') return prepareGraph(repository, dumpId, className, params);
  return prepareFlow(repository, dumpId, className, params);
}

export { findClassStats, layoutCells, withValues, shapesViewModel };
