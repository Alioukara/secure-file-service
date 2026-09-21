import Box from '@mui/material/Box'
import Container from '@mui/material/Container'
import CssBaseline from '@mui/material/CssBaseline'
import { ThemeProvider } from '@mui/material/styles'
import { useMemo } from 'react'
import { useColorMode } from './hooks/useColorMode'
import { buildTheme } from './theme'
import { AppFooter } from './components/layout/AppFooter'
import { AppHeader } from './components/layout/AppHeader'
import { PageHeading } from './components/layout/PageHeading'
import { FileListPanel } from './components/files/FileListPanel'
import { UploadPanel } from './components/upload/UploadPanel'

export default function App() {
  const { mode, toggle } = useColorMode()
  const theme = useMemo(() => buildTheme(mode), [mode])

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
        <AppHeader mode={mode} onToggleMode={toggle} />
        <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 5 } }}>
          <PageHeading />
          <UploadPanel />
          <FileListPanel />
          <AppFooter />
        </Container>
      </Box>
    </ThemeProvider>
  )
}
