import AppBar from '@mui/material/AppBar'
import { styled } from '@mui/material/styles'
import { headerFor } from '../../theme/palette'

export const BrandBar = styled(AppBar)(({ theme }) => {
  const { bg, fg } = headerFor(theme.palette.mode)
  const dark = theme.palette.mode === 'dark'

  return {
    background: bg,
    color: fg,
    borderBottom: dark ? `1px solid ${theme.palette.divider}` : 'none',
  }
})
