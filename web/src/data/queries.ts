import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dumpRepository } from './repository';
import { unwrap } from './http';
import type { ClassQuery } from './types';
import { useRepository } from './RepositoryContext';

export const queryKeys = {
  dumps: ['dumps'] as const,
  jobs: ['jobs'] as const,
  classes: (dump: string, query: Required<ClassQuery>) => ['classes', dump, query] as const,
  trees: (dump: string) => ['trees', dump] as const,
  shapes: (dump: string) => ['shapes', dump] as const,
  compare: (oldId: string, newId: string) => ['compare', oldId, newId] as const,
  composition: (dump: string, className: string) => ['composition', dump, className] as const,
};

export function useDumps(enabled = true) {
  return useQuery({
    queryKey: queryKeys.dumps,
    queryFn: async () => unwrap(await dumpRepository.list()),
    refetchInterval: 30_000,
    enabled,
  });
}

export function useJobs(enabled = true) {
  return useQuery({
    queryKey: queryKeys.jobs,
    queryFn: async () => unwrap(await dumpRepository.jobs()),
    refetchInterval: 2_500,
    enabled,
  });
}

export function useClasses(dump: string | null, query: Required<ClassQuery>, enabled = true) {
  const repository = useRepository();
  return useQuery({
    queryKey: queryKeys.classes(dump || '', query),
    queryFn: async () => unwrap(await repository.classes(dump!, query)),
    enabled: enabled && Boolean(dump),
    placeholderData: (previous) => previous,
    retry: (count, error) => count < 20 && 'status' in error && error.status === 409,
    retryDelay: 4_000,
  });
}

export function useTrees(dump: string | null, enabled = true) {
  const repository = useRepository();
  return useQuery({
    queryKey: queryKeys.trees(dump || ''),
    queryFn: async () => unwrap(await repository.trees(dump!)),
    enabled: enabled && Boolean(dump),
    retry: (count, error) => count < 20 && 'status' in error && error.status === 409,
    retryDelay: 4_000,
  });
}

export function useDumpMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ action, id, tags }: { action: 'download' | 'cancel' | 'delete' | 'tags'; id: string; tags?: string[] }) => {
      const result = action === 'download' ? await dumpRepository.startDownload(id)
        : action === 'cancel' ? await dumpRepository.cancelDownload(id)
          : action === 'delete' ? await dumpRepository.delete(id)
            : await dumpRepository.setTags(id, tags || []);
      return unwrap<unknown>(result);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.dumps }),
  });
}
