import { createTheme } from '@mui/material/styles';
import { categoryColors } from './data/format';

const border = '#393b40';
const text = '#dfe1e5';
const muted = '#9da0a8';
const accent = '#6b9bff';

export const theme = createTheme({
  palette: {
    mode: 'dark',
    background: { default: '#1e1f22', paper: '#242628' },
    primary: { main: accent, dark: '#3574f0', light: '#8fb3ff' },
    secondary: { main: '#8fb3ff' },
    success: { main: categoryColors.agp },
    info: { main: '#548af7' },
    warning: { main: '#c77d55' },
    error: { main: '#e55765' },
    text: { primary: text, secondary: muted },
    divider: border,
    action: {
      hover: 'rgba(255, 255, 255, 0.045)',
      selected: 'rgba(53, 116, 240, 0.24)',
      focus: 'rgba(107, 155, 255, 0.16)',
      disabled: '#5e6066',
      disabledBackground: '#2b2d30',
    },
  },
  typography: {
    fontFamily: 'Inter, "JetBrains Sans", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    fontSize: 13,
    button: { fontSize: 12, fontWeight: 500, textTransform: 'none' },
    h5: { fontSize: 18, fontWeight: 600 },
    h6: { fontSize: 14, fontWeight: 600 },
    subtitle1: { fontSize: 13, fontWeight: 600 },
    body1: { fontSize: 13 },
    body2: { fontSize: 12 },
    caption: { fontSize: 11 },
  },
  shape: { borderRadius: 4 },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: '#1e1f22', color: text },
        '#root': { minHeight: '100vh' },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true, size: 'small' },
      styleOverrides: {
        root: { minHeight: 28, padding: '3px 10px', borderRadius: 4, whiteSpace: 'nowrap' },
        text: { color: muted, '&:hover': { color: text, backgroundColor: 'rgba(255,255,255,.05)' } },
        outlined: { borderColor: border, color: text, '&:hover': { borderColor: '#6b6d73', backgroundColor: 'rgba(255,255,255,.035)' } },
        contained: { backgroundColor: '#3574f0', color: '#fff', '&:hover': { backgroundColor: '#4682f5' } },
      },
    },
    MuiIconButton: {
      defaultProps: { size: 'small' },
      styleOverrides: { root: { borderRadius: 4, padding: 5, color: muted, '&:hover': { color: text } } },
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: 3, fontSize: 11, fontWeight: 500 },
        sizeSmall: { height: 20, fontSize: 11 },
        outlined: { borderColor: '#4b4e55', backgroundColor: 'transparent' },
        label: { paddingLeft: 7, paddingRight: 7 },
      },
    },
    MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' }, outlined: { borderColor: border } } },
    MuiCard: { styleOverrides: { root: { backgroundImage: 'none', borderColor: border } } },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: '#313438', padding: '5px 10px', fontSize: 12, lineHeight: 1.35 },
        head: { height: 32, color: muted, backgroundColor: '#2b2d30', fontWeight: 600, whiteSpace: 'nowrap' },
        body: { height: 32 },
        sizeSmall: { padding: '4px 9px' },
      },
    },
    MuiTableRow: { styleOverrides: { root: { '&.MuiTableRow-hover:hover': { backgroundColor: 'rgba(255,255,255,.035)' } } } },
    MuiTableSortLabel: {
      styleOverrides: {
        root: { color: muted, '&:hover': { color: text }, '&.Mui-active': { color: text } },
        icon: { fontSize: 14, color: `${accent} !important` },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: { backgroundColor: '#1e1f22', borderRadius: 4, fontSize: 12, '& fieldset': { borderColor: '#4b4e55' }, '&:hover fieldset': { borderColor: '#6b6d73' }, '&.Mui-focused fieldset': { borderColor: accent, borderWidth: 1 } },
        input: { padding: '6px 10px', minHeight: '1.5em' },
        inputSizeSmall: { padding: '5px 9px' },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { fontSize: 12, color: muted }, shrink: { color: muted } } },
    MuiInputAdornment: { styleOverrides: { root: { color: muted }, positionStart: { marginRight: 6 } } },
    MuiSelect: { styleOverrides: { icon: { color: muted } } },
    MuiMenu: { styleOverrides: { paper: { border: `1px solid ${border}`, backgroundColor: '#2b2d30', backgroundImage: 'none' } } },
    MuiMenuItem: { styleOverrides: { root: { minHeight: 30, fontSize: 12, paddingTop: 5, paddingBottom: 5 } } },
    MuiList: { styleOverrides: { root: { padding: 4 } } },
    MuiToggleButton: {
      styleOverrides: {
        root: { borderColor: border, color: muted, fontSize: 11, minHeight: 26, padding: '2px 9px', textTransform: 'none', '&:hover': { color: text, backgroundColor: 'rgba(255,255,255,.04)' }, '&.Mui-selected': { color: text, backgroundColor: 'rgba(53,116,240,.25)', borderColor: '#548af7' } },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 34 },
        indicator: { height: 2, backgroundColor: accent },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: { minHeight: 34, minWidth: 0, padding: '5px 12px', color: muted, fontSize: 12, fontWeight: 500, textTransform: 'none', '&.Mui-selected': { color: text } },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: { border: `1px solid #4b4e55`, borderRadius: 6, backgroundColor: '#242628', backgroundImage: 'none', boxShadow: '0 16px 48px rgba(0,0,0,.45)' },
      },
    },
    MuiDialogTitle: { styleOverrides: { root: { padding: '12px 16px', fontSize: 15, fontWeight: 600 } } },
    MuiDialogContent: { styleOverrides: { root: { borderColor: border } } },
    MuiDialogActions: { styleOverrides: { root: { padding: '10px 16px' } } },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 4, padding: '6px 10px', fontSize: 12, alignItems: 'flex-start' },
        standardInfo: { backgroundColor: 'rgba(53,116,240,.1)', border: '1px solid rgba(107,155,255,.25)' },
        standardSuccess: { backgroundColor: 'rgba(87,150,92,.1)', border: '1px solid rgba(87,150,92,.25)' },
        standardWarning: { backgroundColor: 'rgba(199,125,85,.1)', border: '1px solid rgba(199,125,85,.25)' },
        standardError: { backgroundColor: 'rgba(229,87,101,.1)', border: '1px solid rgba(229,87,101,.25)' },
        icon: { fontSize: 18, marginRight: 8, padding: 0 },
        message: { padding: 0 },
      },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: { fontSize: 11, maxWidth: 460, color: text, backgroundColor: '#2b2d30', border: '1px solid #4b4e55', boxShadow: '0 5px 16px rgba(0,0,0,.35)', padding: '5px 8px' },
      },
    },
    MuiLinearProgress: { styleOverrides: { root: { height: 3, borderRadius: 0, backgroundColor: '#393b40' }, bar: { backgroundColor: accent } } },
    MuiAccordion: { styleOverrides: { root: { backgroundColor: 'transparent', backgroundImage: 'none', '&::before': { display: 'none' } } } },
    MuiAccordionSummary: { styleOverrides: { root: { minHeight: 36, padding: '0 10px' }, content: { margin: '8px 0' } } },
    MuiSkeleton: { styleOverrides: { root: { backgroundColor: 'rgba(255,255,255,.055)' } } },
    MuiBreadcrumbs: { styleOverrides: { root: { fontSize: 12 }, separator: { color: '#6f7278' } } },
    MuiCircularProgress: { defaultProps: { color: 'primary', thickness: 4 } },
  },
});
