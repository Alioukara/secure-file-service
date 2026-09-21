import TablePagination from '@mui/material/TablePagination'
import { PAGE_SIZE_OPTIONS } from '../../constants/file.constants'

interface Props {
  page: number
  size: number
  totalElements: number
  onPageChange: (page: number) => void
  onSizeChange: (size: number) => void
}

/**
 * Driven by the totals the API returns, never by the rows on screen: only the
 * backend knows how many files match the current filter.
 */
export function FilePagination({ page, size, totalElements, onPageChange, onSizeChange }: Props) {
  return (
    <TablePagination
      component="div"
      count={totalElements}
      page={page}
      rowsPerPage={size}
      rowsPerPageOptions={PAGE_SIZE_OPTIONS}
      labelRowsPerPage="Par page"
      labelDisplayedRows={({ from, to, count }) => `${from}–${to} sur ${count}`}
      onPageChange={(_, next) => onPageChange(next)}
      onRowsPerPageChange={(event) => onSizeChange(Number(event.target.value))}
    />
  )
}
