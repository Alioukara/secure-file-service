import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

export function AppFooter() {
  return (
    <Box sx={{ mt: 4 }}>
      <Typography variant="caption" color="text.disabled">
        Démonstration du parcours. L'API est le produit.
      </Typography>
    </Box>
  )
}
