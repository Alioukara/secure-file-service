/** RFC 7807, as the API returns it. `reason` is this service's own addition. */
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance?: string
  reason?: string
}

/** What the HTTP layer hands to the rest of the application. */
export interface ApiError {
  status: number | null
  message: string
  reason: string | null
}

/** Mirrors the backend PageResponse: deliberately not Spring's own shape. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
