import { useMutation, useQueryClient } from '@tanstack/react-query'
import { requestRescan } from '../services/file.service'
import { FILES_QUERY_KEY } from './useFiles'
import type { ApiError } from '../types/api.types'

export function useRescan() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, string>({
    mutationFn: requestRescan,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [FILES_QUERY_KEY] }),
  })
}
