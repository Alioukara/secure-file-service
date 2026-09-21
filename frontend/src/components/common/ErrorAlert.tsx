import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import type { ApiError } from '../../types/api.types'

interface Props {
  error: ApiError
  onClose?: () => void
}

export function ErrorAlert({ error, onClose }: Props) {
  return (
    <Alert severity="error" onClose={onClose}>
      {error.reason && <AlertTitle>{error.reason}</AlertTitle>}
      {error.message}
    </Alert>
  )
}
