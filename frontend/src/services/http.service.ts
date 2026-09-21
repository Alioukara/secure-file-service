import axios from 'axios'
import { toApiError } from '../handlers/error.handler'

/**
 * Baked in at build time by Vite, not read at runtime: an image is tied to the
 * API it was built against.
 */
const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const http = axios.create({ baseURL })

/**
 * Normalises failures once, here, so no component ever inspects an AxiosError.
 */
http.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(toApiError(error)),
)

export function absoluteUrl(path: string): string {
  return `${baseURL}${path}`
}
