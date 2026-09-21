import axios from 'axios'
import type { ApiError, ProblemDetail } from '../types/api.types'

const UNREACHABLE = "L'API est injoignable. Le service est-il démarré ?"
const UNEXPECTED = 'Une erreur inattendue est survenue.'

/**
 * The API answers every refusal with a ProblemDetail. Its `detail` is surfaced
 * as-is: a bare "erreur 409" would discard what the service said.
 */
export function toApiError(error: unknown): ApiError {
  if (!axios.isAxiosError(error)) {
    return { status: null, message: UNEXPECTED, reason: null }
  }

  if (!error.response) {
    return { status: null, message: UNREACHABLE, reason: null }
  }

  const problem = error.response.data as Partial<ProblemDetail> | undefined

  return {
    status: error.response.status,
    message: problem?.detail ?? UNEXPECTED,
    reason: problem?.reason ?? null,
  }
}
