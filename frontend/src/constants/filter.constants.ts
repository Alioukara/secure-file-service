import type { FileStatus } from '../types/file.types'
import { DOWNLOADABLE_STATUS, IN_FLIGHT_STATUSES, REFUSED_STATUSES } from './file.constants'

export type FilterKey = 'ALL' | 'CLEAN' | 'IN_FLIGHT' | 'REFUSED'

export interface FilterDefinition {
  key: FilterKey
  label: string
  /** Empty means no filter at all: the API then returns every status. */
  statuses: readonly FileStatus[]
}

/**
 * Grouped rather than one entry per status: a user thinks in "servable or not",
 * not in seven internal states. The seven remain visible in each row's badge.
 */
export const FILTERS: readonly FilterDefinition[] = [
  { key: 'ALL', label: 'Tous', statuses: [] },
  { key: 'CLEAN', label: 'Sains', statuses: [DOWNLOADABLE_STATUS] },
  { key: 'IN_FLIGHT', label: 'En cours', statuses: IN_FLIGHT_STATUSES },
  { key: 'REFUSED', label: 'Refusés', statuses: REFUSED_STATUSES },
]

export function statusesFor(key: FilterKey): readonly FileStatus[] {
  return FILTERS.find((filter) => filter.key === key)?.statuses ?? []
}
