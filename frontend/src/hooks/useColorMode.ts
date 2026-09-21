import { useCallback, useEffect, useMemo, useState } from 'react'
import { useMediaQuery, type PaletteMode } from '@mui/material'

const STORAGE_KEY = 'sfs.color-mode'

function stored(): PaletteMode | null {
  const value = localStorage.getItem(STORAGE_KEY)
  return value === 'light' || value === 'dark' ? value : null
}

/**
 * Follows the system preference until the user decides otherwise, then keeps
 * that decision. Reading the system setting first avoids a light flash on a
 * machine set to dark.
 */
export function useColorMode() {
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')
  const [mode, setMode] = useState<PaletteMode>(() => stored() ?? (prefersDark ? 'dark' : 'light'))

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, mode)
  }, [mode])

  const toggle = useCallback(() => {
    setMode((current) => (current === 'light' ? 'dark' : 'light'))
  }, [])

  return useMemo(() => ({ mode, toggle }), [mode, toggle])
}
