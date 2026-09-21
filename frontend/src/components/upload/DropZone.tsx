import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CloudUploadOutlinedIcon from '@mui/icons-material/CloudUploadOutlined'
import InsertDriveFileOutlinedIcon from '@mui/icons-material/InsertDriveFileOutlined'
import { formatSize } from '../../utils/format'
import { IconBadge } from '../common/IconBadge'
import { DropSurface } from './DropSurface'

interface Props {
  selected: File | null
  onSelect: (file: File | null) => void
  disabled?: boolean
  /** Rendered on the right: the panel owns sending, this component owns picking. */
  actions?: ReactNode
}

export function DropZone({ selected, onSelect, disabled = false, actions }: Props) {
  const [over, setOver] = useState(false)

  const handleDrop = (event: React.DragEvent) => {
    event.preventDefault()
    setOver(false)
    if (disabled) return
    onSelect(event.dataTransfer.files?.[0] ?? null)
  }

  return (
    <DropSurface
      over={over}
      dimmed={disabled}
      onDragOver={(event) => {
        event.preventDefault()
        setOver(true)
      }}
      onDragLeave={() => setOver(false)}
      onDrop={handleDrop}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', sm: 'center' }}
      >
        <IconBadge tone="primary" badgeSize={44}>
          {selected ? <InsertDriveFileOutlinedIcon /> : <CloudUploadOutlinedIcon />}
        </IconBadge>

        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="subtitle1" noWrap title={selected?.name}>
            {selected ? selected.name : 'Déposer un fichier'}
          </Typography>
          <Typography variant="body2" color="text.secondary" noWrap>
            {selected ? formatSize(selected.size) : 'Glissez-le ici, ou parcourez vos fichiers.'}
          </Typography>
        </Box>

        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ flexShrink: 0 }}>
          {selected ? (
            <Button color="inherit" disabled={disabled} onClick={() => onSelect(null)}>
              Changer
            </Button>
          ) : (
            <Button component="label" variant="outlined" disabled={disabled}>
              Parcourir
              <input
                type="file"
                hidden
                onChange={(event) => {
                  onSelect(event.target.files?.[0] ?? null)
                  // Lets the same file be picked twice in a row.
                  event.target.value = ''
                }}
              />
            </Button>
          )}
          {actions}
        </Stack>
      </Stack>
    </DropSurface>
  )
}
