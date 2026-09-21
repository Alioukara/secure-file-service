import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'

/** Size and date fold away on narrow screens: name, state and actions carry the meaning. */
const SECONDARY = { display: { xs: 'none', md: 'table-cell' } } as const

export function FileTableHead() {
  return (
    <TableHead>
      <TableRow>
        <TableCell>Nom</TableCell>
        <TableCell align="right" sx={SECONDARY}>
          Taille
        </TableCell>
        <TableCell>Statut</TableCell>
        <TableCell sx={SECONDARY}>Déposé le</TableCell>
        <TableCell align="right">Actions</TableCell>
      </TableRow>
    </TableHead>
  )
}
