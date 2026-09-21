import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { IN_FLIGHT_STATUSES, POLL_INTERVAL_MS } from '../constants/file.constants'
import { listFiles, type ListQuery } from '../services/file.service'
import type { ApiError, PageResponse } from '../types/api.types'
import type { FileSummary } from '../types/file.types'

export const FILES_QUERY_KEY = 'files'

/**
 * The query key carries the page, the size and the filter, so each combination
 * is cached on its own instead of overwriting the previous one.
 *
 * Polling only runs while something is still moving: a settled page stops
 * asking, instead of hitting the API every three seconds for nothing.
 */
export function useFiles(query: ListQuery) {
  return useQuery<PageResponse<FileSummary>, ApiError>({
    queryKey: [FILES_QUERY_KEY, query.page, query.size, query.statuses],
    queryFn: () => listFiles(query),
    // Keeps the previous page on screen while the next one loads, instead of
    // flashing an empty table.
    placeholderData: keepPreviousData,
    refetchInterval: (result) =>
      result.state.data?.content.some((file) => IN_FLIGHT_STATUSES.includes(file.status))
        ? POLL_INTERVAL_MS
        : false,
  })
}
