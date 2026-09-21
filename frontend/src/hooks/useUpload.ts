import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { uploadFile } from '../services/file.service'
import { FILES_QUERY_KEY } from './useFiles'
import type { ApiError } from '../types/api.types'
import type { UploadResponse } from '../types/file.types'

export function useUpload() {
  const queryClient = useQueryClient()
  const [progress, setProgress] = useState(0)

  const mutation = useMutation<UploadResponse, ApiError, File>({
    mutationFn: (file) => uploadFile(file, setProgress),
    onMutate: () => setProgress(0),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [FILES_QUERY_KEY] }),
  })

  return { ...mutation, progress }
}
