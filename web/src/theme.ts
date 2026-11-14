import { createTheme } from '@mui/material/styles';
import { categoryColors } from './data/format';

export const theme = createTheme({
  palette: {
    mode: 'dark',
    background: { default: '#0d1117', paper: '#161b22' },
    primary: { main: '#f0883e' },
    secondary: { main: '#79b8ff' },
    success: { main: categoryColors.agp },
    error: { main: '#e5534b' },
    text: { primary: '#e6e9ef', secondary: '#8b94a3' },
    divider: '#2c3542',
  },
  typography: {
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
    fontSize: 14,
  },
  shape: { borderRadius: 8 },
  components: {
    MuiCard: { styleOverrides: { root: { backgroundImage: 'none' } } },
    MuiTableCell: { styleOverrides: { root: { borderColor: '#2c3542' } } },
    MuiTooltip: { styleOverrides: { tooltip: { fontSize: 12, maxWidth: 460 } } },
  },
});
