import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'

export function LoadingState() {
  return (
    <Stack alignItems="center" sx={{ py: 6 }}>
      <CircularProgress />
    </Stack>
  )
}
