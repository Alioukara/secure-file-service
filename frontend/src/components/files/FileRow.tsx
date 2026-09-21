import Stack from '@mui/material/Stack'
import TableCell from '@mui/material/TableCell'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import InsertDriveFileOutlinedIcon from '@mui/icons-material/InsertDriveFileOutlined'
import { formatDate, formatSize } from '../../utils/format'
import type { FileSummary } from '../../types/file.types'
import { FileActions } from './FileActions'
import { StatusChip } from './StatusChip'

const SECONDARY = { display: { xs: 'none', md: 'table-cell' } } as const

interface Props {
  file: FileSummary
  onRescan: (fileId: string) => void
  rescanning: boolean
}

export function FileRow({ file, onRescan, rescanning }: Props) {
  return (
    <TableRow hover>
      <TableCell sx={{ maxWidth: 320 }}>
        <Stack direction="row" spacing={1.25} alignItems="center">
          <InsertDriveFileOutlinedIcon fontSize="small" sx={{ color: 'text.disabled' }} />
          <Stack spacing={0.25} sx={{ minWidth: 0 }}>
            <Typography variant="body2" fontFamily="monospace" noWrap title={file.originalFilename}>
              {file.originalFilename}
            </Typography>
            {/* Folded-away columns resurface here rather than disappearing. */}
            <Typography variant="caption" color="text.secondary" sx={{ display: { md: 'none' } }}>
              {formatSize(file.sizeBytes)} · {formatDate(file.createdAt)}
            </Typography>
          </Stack>
        </Stack>
      </TableCell>
      <TableCell align="right" sx={SECONDARY}>
        {formatSize(file.sizeBytes)}
      </TableCell>
      <TableCell>
        <StatusChip status={file.status} />
      </TableCell>
      <TableCell sx={SECONDARY}>{formatDate(file.createdAt)}</TableCell>
      <TableCell align="right">
        <FileActions file={file} onRescan={onRescan} rescanning={rescanning} />
      </TableCell>
    </TableRow>
  )
}
