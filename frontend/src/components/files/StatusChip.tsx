import Chip from '@mui/material/Chip'
import Tooltip from '@mui/material/Tooltip'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline'
import GppBadOutlinedIcon from '@mui/icons-material/GppBadOutlined'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined'
import ScheduleIcon from '@mui/icons-material/Schedule'
import BlockIcon from '@mui/icons-material/Block'
import type { ReactElement } from 'react'
import type { FileStatus } from '../../types/file.types'

type ChipColor = 'default' | 'info' | 'success' | 'error' | 'warning'

interface Presentation {
  label: string
  color: ChipColor
  icon: ReactElement
  hint: string
}

/**
 * Keyed by FileStatus with no index signature: an eighth status added to the
 * union breaks the build here rather than being rendered as something
 * reassuring.
 */
const PRESENTATION: Record<FileStatus, Presentation> = {
  PENDING: {
    label: 'En attente',
    color: 'default',
    icon: <ScheduleIcon />,
    hint: "En file. L'analyse n'a pas encore commencé.",
  },
  SCANNING: {
    label: 'Analyse',
    color: 'info',
    icon: <HourglassEmptyIcon />,
    hint: 'Analyse antivirus en cours.',
  },
  SCAN_FAILED: {
    label: 'Reprise prévue',
    color: 'warning',
    icon: <ReportProblemOutlinedIcon />,
    hint: "L'antivirus a échoué. Une nouvelle tentative est programmée.",
  },
  SCAN_FAILED_EXHAUSTED: {
    label: 'Échec, épuisé',
    color: 'warning',
    icon: <ReportProblemOutlinedIcon />,
    hint: 'Toutes les tentatives ont échoué. Une relance manuelle est possible.',
  },
  CLEAN: {
    label: 'Sain',
    color: 'success',
    icon: <CheckCircleOutlineIcon />,
    hint: 'Analysé et validé. Téléchargeable.',
  },
  INFECTED: {
    label: 'Infecté',
    color: 'error',
    icon: <GppBadOutlinedIcon />,
    hint: 'Une signature a été détectée. Ce fichier ne sera jamais servi.',
  },
  UNSCANNABLE: {
    label: 'Inanalysable',
    color: 'error',
    icon: <BlockIcon />,
    hint: "Le fichier n'a pas pu être analysé en entier. Il ne sera jamais servi.",
  },
}

export function StatusChip({ status }: { status: FileStatus }) {
  const { label, color, icon, hint } = PRESENTATION[status]

  return (
    <Tooltip title={hint} arrow>
      <Chip label={label} color={color} icon={icon} size="small" variant="outlined" />
    </Tooltip>
  )
}
