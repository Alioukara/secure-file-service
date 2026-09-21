import type { FileStatus } from '../types/file.types'

/** The single servable status. Mirrors the backend rule, never its negation. */
export const DOWNLOADABLE_STATUS: FileStatus = 'CLEAN'

/** The only status a rescan may be requested on. */
export const RESCANNABLE_STATUS: FileStatus = 'SCAN_FAILED_EXHAUSTED'

/** While one of these is present, the list is still moving. */
export const IN_FLIGHT_STATUSES: readonly FileStatus[] = ['PENDING', 'SCANNING', 'SCAN_FAILED']

export const REFUSED_STATUSES: readonly FileStatus[] = [
  'INFECTED',
  'UNSCANNABLE',
  'SCAN_FAILED_EXHAUSTED',
]

export const POLL_INTERVAL_MS = 3000

export const PAGE_SIZE_OPTIONS = [5, 10, 25, 50]

export const DEFAULT_PAGE_SIZE = 10
