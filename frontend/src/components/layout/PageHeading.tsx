import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

export function PageHeading() {
  return (
    <Box sx={{ mb: 3 }}>
      <Typography variant="h4" component="h2">
        Dépôt et analyse
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5, maxWidth: 640 }}>
        Chaque fichier déposé part en quarantaine, y est analysé, et n'en sort que si
        l'antivirus le déclare sain.
      </Typography>
    </Box>
  )
}
