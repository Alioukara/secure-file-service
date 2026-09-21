import type { PaletteMode, ThemeOptions } from '@mui/material'

const BRAND = {
  main: '#1d4ed8',
  dark: '#1739a8',
  light: '#4f79e8',
}

/** Lightened for dark mode: #1d4ed8 on a dark surface falls below 4.5:1. */
const BRAND_ON_DARK = {
  main: '#8ab0f8',
  dark: '#6790e2',
  light: '#b0cbfb',
}

export function paletteFor(mode: PaletteMode): ThemeOptions['palette'] {
  return mode === 'light'
    ? {
        mode,
        primary: { ...BRAND, contrastText: '#ffffff' },
        background: { default: '#f4f6f9', paper: '#ffffff' },
        divider: '#e4e8ee',
        text: { primary: '#101828', secondary: '#546074' },
      }
    : {
        mode,
        primary: { ...BRAND_ON_DARK, contrastText: '#0b1118' },
        background: { default: '#0d1218', paper: '#151d26' },
        divider: '#253140',
        text: { primary: '#e6ebf2', secondary: '#9aa7b8' },
      }
}

export function headerFor(mode: PaletteMode) {
  return mode === 'light'
    ? { bg: 'linear-gradient(100deg, #0f172a 0%, #172544 62%, #1c3059 100%)', fg: '#ffffff' }
    : { bg: 'linear-gradient(100deg, #0a0f17 0%, #101a2c 62%, #14223c 100%)', fg: '#e6ebf2' }
}

export const CARD_SHADOW = {
  light: '0 1px 2px rgba(16,24,40,0.06), 0 2px 8px rgba(16,24,40,0.08)',
  dark: '0 1px 2px rgba(0,0,0,0.5), 0 2px 10px rgba(0,0,0,0.4)',
}
