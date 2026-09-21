import Box from '@mui/material/Box'
import Container from '@mui/material/Container'
import IconButton from '@mui/material/IconButton'
import Toolbar from '@mui/material/Toolbar'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import type { PaletteMode } from '@mui/material'
import { BrandBar } from './BrandBar'

interface Props {
  mode: PaletteMode
  onToggleMode: () => void
}

export function AppHeader({ mode, onToggleMode }: Props) {
  const dark = mode === 'dark'

  return (
    <BrandBar position="sticky" elevation={0}>
      <Container maxWidth="lg" disableGutters>
        <Toolbar>
          <ShieldOutlinedIcon color="inherit" sx={{ mr: 1.5 }} />
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="h6" component="span" noWrap>
              Secure File Service
            </Typography>
          </Box>

          <Tooltip title={dark ? 'Passer en clair' : 'Passer en sombre'}>
            <IconButton color="inherit" onClick={onToggleMode} aria-label="Changer de thème">
              {dark ? <LightModeOutlinedIcon /> : <DarkModeOutlinedIcon />}
            </IconButton>
          </Tooltip>
        </Toolbar>
      </Container>
    </BrandBar>
  )
}
