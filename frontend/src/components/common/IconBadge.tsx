import Box from '@mui/material/Box'
import { alpha, styled } from '@mui/material/styles'
import type { Tone } from '../../types/ui.types'

interface BadgeProps {
  tone: Tone
  badgeSize?: number
}

/** `tone` and `badgeSize` are styling inputs, not DOM attributes. */
const isStyleProp = (prop: PropertyKey) => prop === 'tone' || prop === 'badgeSize'

export const IconBadge = styled(Box, {
  shouldForwardProp: (prop) => !isStyleProp(prop),
})<BadgeProps>(({ theme, tone, badgeSize = 40 }) => ({
  flexShrink: 0,
  display: 'grid',
  placeItems: 'center',
  width: badgeSize,
  height: badgeSize,
  borderRadius: theme.shape.borderRadius,
  color: theme.palette[tone].main,
  backgroundColor: alpha(theme.palette[tone].main, 0.12),
}))
