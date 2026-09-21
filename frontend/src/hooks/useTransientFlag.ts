import { useEffect, useState } from 'react'

/**
 * Turns a sticky flag into a transient one: an upload confirmation that never
 * clears keeps asserting a state the table has already moved past.
 */
export function useTransientFlag(active: boolean, durationMs: number): boolean {
  const [visible, setVisible] = useState(active)

  useEffect(() => {
    if (!active) {
      setVisible(false)
      return
    }
    setVisible(true)
    const timer = window.setTimeout(() => setVisible(false), durationMs)
    return () => window.clearTimeout(timer)
  }, [active, durationMs])

  return visible
}
