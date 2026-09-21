import { alpha, createTheme, type PaletteMode } from '@mui/material'
import { CARD_SHADOW, paletteFor } from './palette'

export function buildTheme(mode: PaletteMode) {
  const shadow = CARD_SHADOW[mode]

  return createTheme({
    palette: paletteFor(mode),
    shape: { borderRadius: 12 },
    typography: {
      fontFamily: 'Inter, system-ui, -apple-system, "Segoe UI", sans-serif',
      h4: { fontWeight: 700, fontSize: '1.75rem', letterSpacing: '-0.02em' },
      h6: { fontWeight: 600, letterSpacing: '-0.01em' },
      subtitle1: { fontWeight: 600, letterSpacing: '-0.01em' },
      // Tabular figures: the counters sit in a row, and proportional digits
      // make them jump sideways every time a scan changes a total.
      h5: { fontWeight: 700, fontVariantNumeric: 'tabular-nums' },
      button: { fontWeight: 500 },
    },
    components: {
      MuiButton: { styleOverrides: { root: { textTransform: 'none' } } },
      MuiChip: { styleOverrides: { root: { fontWeight: 500 } } },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: { root: { boxShadow: shadow, backgroundImage: 'none' } },
      },
      MuiTableCell: {
        styleOverrides: {
          root: { paddingTop: 14, paddingBottom: 14 },
          head: ({ theme }) => ({
            fontWeight: 600,
            fontSize: '0.75rem',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            color: theme.palette.text.secondary,
            backgroundColor: theme.palette.action.hover,
          }),
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: ({ theme }) => ({
            '&:hover': { backgroundColor: alpha(theme.palette.primary.main, 0.04) },
          }),
        },
      },
    },
  })
}
