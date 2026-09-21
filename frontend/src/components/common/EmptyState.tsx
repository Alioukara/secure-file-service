import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'

interface Props {
  title: string
  hint?: string
}

export function EmptyState({ title, hint }: Props) {
  return (
    <Stack alignItems="center" spacing={1} sx={{ py: 7, color: 'text.disabled' }}>
      <ArrowUpwardIcon fontSize="large" />
      <Typography variant="body2">{title}</Typography>
      {hint && <Typography variant="caption">{hint}</Typography>}
    </Stack>
  )
}
