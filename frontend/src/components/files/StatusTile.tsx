import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Card from '@mui/material/Card'
import { alpha, styled } from '@mui/material/styles'
import type { Tone } from '../../types/ui.types'

interface TileProps {
  tone: Tone
  active: boolean
}

const isStyleProp = (prop: PropertyKey) => prop === 'tone' || prop === 'active'

export const TileGrid = styled(Box)(({ theme }) => ({
  display: 'grid',
  gap: theme.spacing(2),
  gridTemplateColumns: 'repeat(2, 1fr)',
  [theme.breakpoints.up('sm')]: { gridTemplateColumns: 'repeat(4, 1fr)' },
}))

/** A card clips its children to its own radius, so the border belongs here. */
export const TileCard = styled(Card, {
  shouldForwardProp: (prop) => !isStyleProp(prop),
})<TileProps>(({ theme, tone, active }) => ({
  border: `2px solid ${active ? theme.palette[tone].main : 'transparent'}`,
  transition: theme.transitions.create('border-color', { duration: 150 }),
}))

export const TileButton = styled(ButtonBase, {
  shouldForwardProp: (prop) => !isStyleProp(prop),
})<TileProps>(({ theme, tone, active }) => ({
  width: '100%',
  justifyContent: 'flex-start',
  gap: theme.spacing(1.75),
  padding: theme.spacing(1.75, 2),
  backgroundColor: active ? alpha(theme.palette[tone].main, 0.07) : 'transparent',
  transition: theme.transitions.create('background-color', { duration: 150 }),
  '&:hover': { backgroundColor: alpha(theme.palette[tone].main, 0.07) },
}))

export const TileText = styled(Box)({
  textAlign: 'left',
  minWidth: 0,
})
