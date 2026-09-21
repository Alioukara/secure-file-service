import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline'
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined'
import GppBadOutlinedIcon from '@mui/icons-material/GppBadOutlined'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import type { ReactElement } from 'react'
import { FILTERS, type FilterKey } from '../../constants/filter.constants'
import type { FileCounts } from '../../hooks/useFileCounts'
import type { Tone } from '../../types/ui.types'
import { IconBadge } from '../common/IconBadge'
import { TileButton, TileCard, TileGrid, TileText } from './StatusTile'

interface Presentation {
  tone: Tone
  icon: ReactElement
}

/** Exhaustive: a fifth filter breaks the build instead of rendering colourless. */
const PRESENTATION: Record<FilterKey, Presentation> = {
  ALL: { tone: 'primary', icon: <FolderOutlinedIcon /> },
  CLEAN: { tone: 'success', icon: <CheckCircleOutlineIcon /> },
  IN_FLIGHT: { tone: 'info', icon: <HourglassEmptyIcon /> },
  REFUSED: { tone: 'error', icon: <GppBadOutlinedIcon /> },
}

interface Props {
  value: FilterKey
  counts: FileCounts
  loading: boolean
  onChange: (key: FilterKey) => void
}

export function StatusSummary({ value, counts, loading, onChange }: Props) {
  return (
    <TileGrid>
      {FILTERS.map((filter) => {
        const { tone, icon } = PRESENTATION[filter.key]
        const active = filter.key === value

        return (
          <TileCard key={filter.key} tone={tone} active={active}>
            <TileButton
              tone={tone}
              active={active}
              aria-pressed={active}
              onClick={() => onChange(filter.key)}
            >
              <IconBadge tone={tone}>{icon}</IconBadge>
              <TileText>
                {loading ? (
                  <Skeleton width={26} height={30} />
                ) : (
                  <Typography variant="h5" lineHeight={1.1}>
                    {counts[filter.key] ?? '—'}
                  </Typography>
                )}
                <Typography variant="body2" color="text.secondary" noWrap>
                  {filter.label}
                </Typography>
              </TileText>
            </TileButton>
          </TileCard>
        )
      })}
    </TileGrid>
  )
}
