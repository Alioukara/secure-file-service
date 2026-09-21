import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import DownloadIcon from '@mui/icons-material/Download'
import ReplayIcon from '@mui/icons-material/Replay'
import { DOWNLOADABLE_STATUS, RESCANNABLE_STATUS } from '../../constants/file.constants'
import { downloadUrl } from '../../services/file.service'
import type { FileSummary } from '../../types/file.types'

interface Props {
  file: FileSummary
  onRescan: (fileId: string) => void
  rescanning: boolean
}

export function FileActions({ file, onRescan, rescanning }: Props) {
  const downloadable = file.status === DOWNLOADABLE_STATUS

  return (
    <Stack direction="row" spacing={1} justifyContent="flex-end">
      {/* Disabled rather than hidden: the button must say the file exists and
          is simply not servable yet, not that it never existed. */}
      <Tooltip
        title={downloadable ? 'Télécharger' : "Indisponible tant que le fichier n'est pas déclaré sain"}
      >
        <span>
          <Button
            size="small"
            startIcon={<DownloadIcon />}
            disabled={!downloadable}
            href={downloadUrl(file.fileId)}
          >
            Télécharger
          </Button>
        </span>
      </Tooltip>

      {file.status === RESCANNABLE_STATUS && (
        <Button
          size="small"
          color="warning"
          startIcon={<ReplayIcon />}
          disabled={rescanning}
          onClick={() => onRescan(file.fileId)}
        >
          Relancer
        </Button>
      )}
    </Stack>
  )
}
