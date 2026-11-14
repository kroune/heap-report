import { Chip } from '@mui/material';
import type { DumpState } from '../data/types';

const colors: Record<DumpState, 'default' | 'success' | 'info' | 'warning' | 'error'> = {
  ready: 'success', remote: 'info', downloading: 'warning', assembling: 'warning', indexing: 'warning', failed: 'error',
};

export function DumpStateChip({ state, size = 'small' }: { state: DumpState; size?: 'small' | 'medium' }) {
  return <Chip label={state} color={colors[state]} variant="outlined" size={size} />;
}
