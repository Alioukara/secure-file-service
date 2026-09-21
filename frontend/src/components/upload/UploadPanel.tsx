import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Collapse from '@mui/material/Collapse'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SendIcon from '@mui/icons-material/Send'
import { useTransientFlag } from '../../hooks/useTransientFlag'
import { useUpload } from '../../hooks/useUpload'
import { ErrorAlert } from '../common/ErrorAlert'
import { DropZone } from './DropZone'

const CONFIRMATION_MS = 4000

export function UploadPanel() {
  const [selected, setSelected] = useState<File | null>(null)
  const upload = useUpload()

  const confirmed = useTransientFlag(upload.isSuccess, CONFIRMATION_MS)

  const send = () => {
    if (!selected) return
    upload.mutate(selected, { onSuccess: () => setSelected(null) })
  }

  return (
    <Box sx={{ mb: 3 }}>
      <DropZone
        selected={selected}
        onSelect={setSelected}
        disabled={upload.isPending}
        actions={
          <Button
            variant="contained"
            disableElevation
            startIcon={<SendIcon />}
            disabled={!selected || upload.isPending}
            onClick={send}
          >
            Envoyer
          </Button>
        }
      />

      <Collapse in={upload.isPending}>
        <Box sx={{ mt: 1.5 }}>
          <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              {upload.progress < 100 ? 'Transfert' : 'Réception par le service'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {upload.progress} %
            </Typography>
          </Stack>
          {/* Determinate while bytes are moving, then indeterminate: once the
              last byte is sent, the wait is the server's, not the network's. */}
          <LinearProgress
            variant={upload.progress < 100 ? 'determinate' : 'indeterminate'}
            value={upload.progress}
          />
        </Box>
      </Collapse>

      <Collapse in={upload.isError}>
        <Stack sx={{ mt: 1.5 }}>
          {upload.error && <ErrorAlert error={upload.error} onClose={() => upload.reset()} />}
        </Stack>
      </Collapse>

      <Collapse in={confirmed}>
        <Alert severity="success" sx={{ mt: 1.5 }}>
          Fichier accepté. Son état apparaît ci-dessous.
        </Alert>
      </Collapse>
    </Box>
  )
}
