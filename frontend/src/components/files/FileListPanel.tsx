import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { DEFAULT_PAGE_SIZE } from '../../constants/file.constants'
import { statusesFor, type FilterKey } from '../../constants/filter.constants'
import { useFileCounts } from '../../hooks/useFileCounts'
import { useFiles } from '../../hooks/useFiles'
import { useRescan } from '../../hooks/useRescan'
import { ErrorAlert } from '../common/ErrorAlert'
import { LoadingState } from '../common/LoadingState'
import { FilePagination } from './FilePagination'
import { FileTable } from './FileTable'
import { RefreshIndicator } from './RefreshIndicator'
import { StatusSummary } from './StatusSummary'

export function FileListPanel() {
  const [filter, setFilter] = useState<FilterKey>('ALL')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE)

  const files = useFiles({ page, size, statuses: statusesFor(filter) })
  const { counts, isPending: countsPending } = useFileCounts()
  const rescan = useRescan()

  // Changing the filter or the page size reshuffles the whole set: staying on
  // page 4 of a result that now has one page would show an empty table.
  const changeFilter = (next: FilterKey) => {
    setFilter(next)
    setPage(0)
  }

  const changeSize = (next: number) => {
    setSize(next)
    setPage(0)
  }

  return (
    <Box>
      <StatusSummary
        value={filter}
        counts={counts}
        loading={countsPending}
        onChange={changeFilter}
      />

      <Card sx={{ mt: 3 }}>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ px: 3, py: 2 }}>
          <Typography variant="subtitle1">Fichiers</Typography>
          <RefreshIndicator active={files.isFetching && !files.isPending} />
        </Stack>

        <Divider />

        {rescan.isError && rescan.error && (
          <Stack sx={{ p: 2 }}>
            <ErrorAlert error={rescan.error} onClose={() => rescan.reset()} />
          </Stack>
        )}

        {files.isPending && <LoadingState />}

        {files.isError && files.error && (
          <Stack sx={{ p: 2 }}>
            <ErrorAlert error={files.error} />
          </Stack>
        )}

        {files.isSuccess && (
          <>
            <FileTable
              files={files.data.content}
              onRescan={rescan.mutate}
              rescanning={rescan.isPending}
              filtered={filter !== 'ALL'}
            />
            <Divider />
            <FilePagination
              page={files.data.page}
              size={files.data.size}
              totalElements={files.data.totalElements}
              onPageChange={setPage}
              onSizeChange={changeSize}
            />
          </>
        )}
      </Card>
    </Box>
  )
}
