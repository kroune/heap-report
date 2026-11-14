import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../../data/queries';
import { dumpRepository } from '../../data/repository';
import type { Job } from '../../data/types';
import { JobsPanel } from './JobsPanel';

const runningJob: Job = {
  id: 1,
  kind: 'download',
  dump: 'run-idea-base',
  state: 'running',
  log: Array.from({ length: 12 }, (_, index) => `line ${index + 1}`),
};

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

afterEach(() => vi.restoreAllMocks());

describe('JobsPanel', () => {
  it('pre-dismisses initial terminal jobs but retains jobs completed in this session and their log state', () => {
    const client = new QueryClient();
    const oldJob: Job = { id: 2, kind: 'analyze', dump: 'old-history', state: 'done' };
    const { rerender } = render(<JobsPanel jobs={[runningJob, oldJob]} />, { wrapper: clientWrapper(client) });

    expect(screen.queryByText('old-history')).not.toBeInTheDocument();
    expect(screen.getByText('run-idea-base')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show full log (12 lines)' }));
    expect(screen.getByRole('button', { name: 'Hide full log (12 lines)' })).toHaveAttribute('aria-expanded', 'true');

    rerender(<JobsPanel jobs={[{ ...runningJob, state: 'done' }, oldJob]} />);
    expect(screen.getByText('run-idea-base')).toBeInTheDocument();
    expect(screen.queryByText('old-history')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide full log (12 lines)' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('cancels active jobs and invalidates the jobs query', async () => {
    const client = new QueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const cancel = vi.spyOn(dumpRepository, 'cancelJob').mockResolvedValue({
      ok: true, data: { id: 1, cancelled: true },
    });
    render(<JobsPanel jobs={[runningJob]} />, { wrapper: clientWrapper(client) });

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith(1));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.jobs }));
  });
});
