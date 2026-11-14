import { Alert, Box, CircularProgress, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { errorMessage } from '../data/http';

export function LoadingState({ children = 'Loading…' }: { children?: ReactNode }) {
  return <Box display="flex" alignItems="center" justifyContent="center" gap={1.5} minHeight={180} p={3}>
    <CircularProgress size={22} /><Typography color="text.secondary">{children}</Typography>
  </Box>;
}

export function ErrorState({ error, title = 'Unable to load data' }: { error: unknown; title?: string }) {
  return <Alert severity="error" sx={{ m: 2 }}><strong>{title}</strong><br />{errorMessage(error)}</Alert>;
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <Box p={4} textAlign="center"><Typography color="text.secondary">{children}</Typography></Box>;
}
