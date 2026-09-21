import { useQueries, useQueryClient } from '@tanstack/react-query'
import { POLL_INTERVAL_MS } from '../constants/file.constants'
import { FILTERS, type FilterKey } from '../constants/filter.constants'
import { listFiles } from '../services/file.service'
import type { PageResponse } from '../types/api.types'
import type { FileSummary } from '../types/file.types'
import { FILES_QUERY_KEY } from './useFiles'

export type FileCounts = Record<FilterKey, number | undefined>

const IN_FLIGHT_KEY = [FILES_QUERY_KEY, 'count', 'IN_FLIGHT']

export function useFileCounts(): { counts: FileCounts; isPending: boolean } {
  const queryClient = useQueryClient()

  const results = useQueries({
    queries: FILTERS.map((filter) => ({
      // size=1: only totalElements is read, so the API counts without shipping
      // the set. This is why no statistics endpoint was added.
      queryKey: [FILES_QUERY_KEY, 'count', filter.key],
      queryFn: () => listFiles({ page: 0, size: 1, statuses: filter.statuses }),
      // A file leaving PENDING for CLEAN moves two counts at once, so all four
      // follow the same signal rather than each watching its own bucket.
      refetchInterval: () => {
        const inFlight = queryClient.getQueryData<PageResponse<FileSummary>>(IN_FLIGHT_KEY)
        return inFlight && inFlight.totalElements > 0 ? POLL_INTERVAL_MS : false
      },
    })),
  })

  const counts = Object.fromEntries(
    FILTERS.map((filter, index) => [filter.key, results[index].data?.totalElements]),
  ) as FileCounts

  return { counts, isPending: results.some((result) => result.isPending) }
}
