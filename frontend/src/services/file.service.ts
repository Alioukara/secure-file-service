import { DEFAULT_PAGE_SIZE } from '../constants/file.constants'
import { absoluteUrl, http } from './http.service'
import type { PageResponse } from '../types/api.types'
import type { FileStatus, FileStatusDetail, FileSummary, UploadResponse } from '../types/file.types'

const FILES = '/api/files'

export interface ListQuery {
  page?: number
  size?: number
  statuses?: readonly FileStatus[]
}

export async function listFiles({
  page = 0,
  size = DEFAULT_PAGE_SIZE,
  statuses = [],
}: ListQuery = {}): Promise<PageResponse<FileSummary>> {
  const { data } = await http.get<PageResponse<FileSummary>>(FILES, {
    params: {
      page,
      size,
      // Omitted entirely when empty: the API then returns every status.
      ...(statuses.length > 0 && { status: statuses.join(',') }),
    },
  })
  return data
}

export type ProgressListener = (percent: number) => void

/** Reports byte progress: on a hundred megabytes, "en cours" says nothing. */
export async function uploadFile(file: File, onProgress?: ProgressListener): Promise<UploadResponse> {
  const body = new FormData()
  body.append('file', file)

  const { data } = await http.post<UploadResponse>(FILES, body, {
    onUploadProgress: (event) => {
      if (!onProgress || !event.total) return
      onProgress(Math.round((event.loaded / event.total) * 100))
    },
  })
  return data
}

export async function fetchStatus(fileId: string): Promise<FileStatusDetail> {
  const { data } = await http.get<FileStatusDetail>(`${FILES}/${fileId}/status`)
  return data
}

export async function requestRescan(fileId: string): Promise<void> {
  await http.post(`${FILES}/${fileId}/rescan`)
}

/**
 * A plain link rather than a fetch: the browser handles the stream and the
 * Content-Disposition on its own, without loading the file into memory.
 */
export function downloadUrl(fileId: string): string {
  return absoluteUrl(`${FILES}/${fileId}`)
}
