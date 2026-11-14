import type { VisualizationKind } from '../app/AppContext';

export type VizParams = Record<string, unknown>;
export type ViewModel = any;

export interface VisualizationRequest {
  kind: VisualizationKind;
  dumpId: string;
  className: string;
}

export interface VisualizationViewProps {
  vm: ViewModel;
  params: VizParams;
  setParams: (patch: VizParams) => void;
  inline: boolean;
  analyze: (samples?: number) => void;
  analysisStatus: string;
  analysisPending: boolean;
}
