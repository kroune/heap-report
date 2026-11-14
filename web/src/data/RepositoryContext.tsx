import { createContext, useContext, type ReactNode } from 'react';
import type { DumpDataRepository } from './types';
import { httpDataRepository } from './repository';

const RepositoryContext = createContext<DumpDataRepository>(httpDataRepository);

export function RepositoryProvider({ repository, children }: { repository: DumpDataRepository; children: ReactNode }) {
  return <RepositoryContext.Provider value={repository}>{children}</RepositoryContext.Provider>;
}

export const useRepository = () => useContext(RepositoryContext);
