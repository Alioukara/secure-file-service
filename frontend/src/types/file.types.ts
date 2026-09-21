export type FileStatus =
  | 'PENDING'
  | 'SCANNING'
  | 'SCAN_FAILED'
  | 'SCAN_FAILED_EXHAUSTED'
  | 'CLEAN'
  | 'INFECTED'
  | 'UNSCANNABLE'

export interface FileSummary {
  fileId: string
  originalFilename: string
  contentType: string
  sizeBytes: number
  status: FileStatus
  createdAt: string
}

export interface FileStatusDetail {
  fileId: string
  status: FileStatus
  reason: string | null
  scanAttempts: number
  leaseExpiries: number
  createdAt: string
  updatedAt: string
}

export interface UploadResponse {
  fileId: string
  status: FileStatus
}
