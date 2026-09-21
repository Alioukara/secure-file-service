import Card from '@mui/material/Card'
import { alpha, styled } from '@mui/material/styles'

interface SurfaceProps {
  over: boolean
  dimmed: boolean
}

const isStyleProp = (prop: PropertyKey) => prop === 'over' || prop === 'dimmed'

export const DropSurface = styled(Card, {
  shouldForwardProp: (prop) => !isStyleProp(prop),
})<SurfaceProps>(({ theme, over, dimmed }) => ({
  padding: theme.spacing(2.25, 3),
  opacity: dimmed ? 0.6 : 1,
  backgroundColor: over
    ? alpha(theme.palette.primary.main, 0.06)
    : theme.palette.background.paper,
  outline: over ? `2px dashed ${theme.palette.primary.main}` : 'none',
  outlineOffset: -6,
  transition: theme.transitions.create('background-color', { duration: 150 }),
  [theme.breakpoints.down('sm')]: { padding: theme.spacing(2) },
}))
