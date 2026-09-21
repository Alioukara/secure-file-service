import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableContainer from '@mui/material/TableContainer'
import type { FileSummary } from '../../types/file.types'
import { EmptyState } from '../common/EmptyState'
import { FileRow } from './FileRow'
import { FileTableHead } from './FileTableHead'

interface Props {
  files: FileSummary[]
  onRescan: (fileId: string) => void
  rescanning: boolean
  filtered: boolean
}

export function FileTable({ files, onRescan, rescanning, filtered }: Props) {
  if (files.length === 0) {
    return filtered ? (
      <EmptyState title="Aucun fichier dans ce filtre." hint="Essayez « Tous »." />
    ) : (
      <EmptyState title="Aucun fichier pour le moment." hint="Déposez-en un ci-dessus." />
    )
  }

  return (
    <TableContainer>
      <Table size="small">
        <FileTableHead />
        <TableBody>
          {files.map((file) => (
            <FileRow key={file.fileId} file={file} onRescan={onRescan} rescanning={rescanning} />
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
