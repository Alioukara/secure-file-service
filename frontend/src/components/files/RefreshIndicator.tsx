import CircularProgress from '@mui/material/CircularProgress'
import Fade from '@mui/material/Fade'
import Tooltip from '@mui/material/Tooltip'

export function RefreshIndicator({ active }: { active: boolean }) {
  return (
    <Fade in={active}>
      <Tooltip title="Actualisation automatique">
        <CircularProgress size={14} thickness={5} />
      </Tooltip>
    </Fade>
  )
}
