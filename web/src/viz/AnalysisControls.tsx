import { useState } from 'react';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';

const presets = [8, 16, 32];

export function SamplePicker({ available = [], current = null, onSwitch, onAnalyze, disabled = false, inline = false }: {
  available?: number[];
  current?: number | null;
  onSwitch: (samples: number) => void;
  onAnalyze: (samples: number) => void;
  disabled?: boolean;
  inline?: boolean;
}) {
  const [custom, setCustom] = useState('');
  const [invalid, setInvalid] = useState(false);
  const runCustom = () => {
    if (disabled || !custom.trim()) { setInvalid(false); return; }
    const samples = Number(custom);
    if (!/^\d+$/.test(custom) || samples < 1 || samples > 1024) { setInvalid(true); return; }
    setInvalid(false);
    if (available.includes(samples)) onSwitch(samples); else onAnalyze(samples);
  };
  return <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap">
    <Typography variant="caption" color="text.secondary">samples</Typography>
    {available.map((samples) => <Button key={samples} size="small" variant={samples === current ? 'contained' : 'outlined'}
      disabled={disabled} onClick={() => samples !== current && onSwitch(samples)}>{samples}</Button>)}
    {!inline && presets.filter((samples) => !available.includes(samples)).map((samples) =>
      <Button key={samples} size="small" variant="outlined" disabled={disabled}
        title={`Run a new ${samples}-sample extraction`} onClick={() => onAnalyze(samples)}>{samples} ▸</Button>)}
    {!inline && <TextField size="small" value={custom} error={invalid} placeholder="custom"
      helperText={invalid ? '1–1024' : undefined} inputProps={{ inputMode: 'numeric', 'aria-label': 'Custom sample count' }}
      onChange={(event) => { setCustom(event.target.value); setInvalid(false); }}
      onKeyDown={(event) => { if (event.key === 'Enter') runCustom(); }} onBlur={runCustom}
      sx={{ width: 92 }} disabled={disabled} />}
  </Stack>;
}

export function AnalyzePrompt({ kind, inline, analyze, status, pending }: {
  kind: string; inline: boolean; analyze: (samples?: number) => void; status: string; pending: boolean;
}) {
  return <Box p={3}>
    <Typography gutterBottom>No anatomy extracted for this class yet — the {kind} is built from the anatomy extraction.</Typography>
    {inline ? <Typography color="text.secondary">This static snapshot does not contain that extraction.</Typography>
      : <SamplePicker available={[]} onSwitch={() => undefined} onAnalyze={analyze} disabled={pending} />}
    {status && <Alert severity={status.includes('failed') ? 'error' : 'info'} sx={{ mt: 2 }}>{status}</Alert>}
  </Box>;
}
