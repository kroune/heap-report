import { createContext, useContext } from 'react';

export type VisualizationKind = 'anatomy' | 'hierarchy' | 'graph' | 'flow';

interface AppContextValue {
  inline: boolean;
  openViz: (kind: VisualizationKind, dumpId: string, className: string) => void;
}

export const AppContext = createContext<AppContextValue>({
  inline: false,
  openViz: () => { throw new Error('Visualization provider is not mounted'); },
});

export const useApp = () => useContext(AppContext);
