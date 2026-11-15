import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../../data/queries';
import { dumpRepository } from '../../data/repository';
import type { DumpInfo } from '../../data/types';
import { DumpPicker } from './DumpPicker';

const dumps: DumpInfo[] = [
  { id: 'run-idea-13-base', state: 'ready', size: 100, meta: { created_at: '2025-01-02' } },
  { id: 'run-gradle-8', state: 'ready', size: 200, meta: { created_at: '2025-01-01', tags: ['local'] } },
  { id: 'run-remote-9', state: 'remote', size: 300, meta: { created_at: '2025-01-03' } },
];

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function renderPicker(client = new QueryClient()) {
  const onSelect = vi.fn();
  const onClose = vi.fn();
  const utils = render(<DumpPicker open onClose={onClose} dumps={dumps} currentDump={null}
    liveDownloads={new Set()} onSelect={onSelect} />, { wrapper: clientWrapper(client) });
  return { client, onSelect, onClose, ...utils };
}

afterEach(() => vi.restoreAllMocks());

describe('DumpPicker', () => {
  it('applies AND tag filters and preserves row selection', () => {
    const { onSelect, onClose } = renderPicker();
    fireEvent.click(screen.getByRole('button', { name: 'idea 1' }));
    fireEvent.click(screen.getByRole('button', { name: 'base 1' }));

    expect(screen.getByRole('button', { name: 'Select dump run-idea-13-base' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Select dump run-gradle-8' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Select dump run-idea-13-base' }));
    expect(onSelect).toHaveBeenCalledWith('run-idea-13-base');
    expect(onClose).toHaveBeenCalled();
  });

  it('opens the first usable search result from the keyboard', () => {
    const { onSelect, onClose } = renderPicker();
    const search = screen.getByRole('textbox', { name: 'Search dumps' });
    fireEvent.change(search, { target: { value: 'run-' } });
    fireEvent.keyDown(search, { key: 'ArrowDown' });
    const first = screen.getByRole('button', { name: 'Select dump run-idea-13-base' });
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: 'ArrowDown' });
    const second = screen.getByRole('button', { name: 'Select dump run-gradle-8' });
    expect(second).toHaveFocus();
    fireEvent.keyDown(second, { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledWith('run-gradle-8');
    expect(onClose).toHaveBeenCalled();
  });

  it('does not treat remote rows as selectable dumps', () => {
    const { onSelect, onClose } = renderPicker();
    expect(screen.queryByRole('button', { name: 'Select dump run-remote-9' })).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByText('run-remote-9')[0]);
    expect(onSelect).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('starts downloads and invalidates the dump query', async () => {
    const invalidate = vi.spyOn(QueryClient.prototype, 'invalidateQueries');
    vi.spyOn(dumpRepository, 'startDownload').mockResolvedValue({
      ok: true, data: { id: 10, kind: 'download', state: 'queued' },
    });
    renderPicker();
    fireEvent.click(screen.getByRole('button', { name: 'Download' }));

    await waitFor(() => expect(dumpRepository.startDownload).toHaveBeenCalledWith('run-remote-9'));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.dumps }));
  });

  it('normalizes comma-separated manual tags', async () => {
    const setTags = vi.spyOn(dumpRepository, 'setTags').mockResolvedValue({
      ok: true, data: { id: 'run-gradle-8', tags: ['local', 'one', 'two'] },
    });
    renderPicker();
    const row = screen.getByRole('button', { name: 'Select dump run-gradle-8' });
    fireEvent.click(within(row).getByRole('button', { name: '+ tag' }));
    const input = within(row).getByRole('textbox', { name: 'Add tags to run-gradle-8' });
    fireEvent.change(input, { target: { value: 'one, two, one, ,' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(setTags).toHaveBeenCalledWith('run-gradle-8', ['local', 'one', 'two']));
  });
});
