package org.nas.comicsviewer

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.data.NasFile
import org.nas.comicsviewer.data.provideNasRepository
import org.nas.comicsviewer.data.provideZipManager
import org.nas.comicsviewer.data.providePosterRepository
import org.nas.comicsviewer.data.cleanTitle

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF121212),
            onSurface = Color.White,
            primary = Color(0xFF00DC64), // Naver Webtoon Green
            surfaceVariant = Color(0xFF1E1E1E),
            onSurfaceVariant = Color.White
        )
    ) {
        NasComicApp()
    }
}

@Composable
fun NasComicApp() {
    val scope = rememberCoroutineScope()
    val repository = remember { provideNasRepository() }
    val zipManager = remember { provideZipManager() }
    val posterRepository = remember { providePosterRepository() }
    
    // Configuration
    val rootUrl = "smb://192.168.0.2/video/GDS3/GDRIVE/READING/만화/"
    var username by remember { mutableStateOf("takumi") }
    var password by remember { mutableStateOf("qksthd078!@") }
    
    // Data State
    var categories by remember { mutableStateOf<List<NasFile>>(emptyList()) }
    var selectedCategoryIndex by remember { mutableStateOf(0) }
    
    var currentPath by remember { mutableStateOf("") }
    // Use mutableStateList for efficient updates during streaming
    val currentFiles = remember { mutableStateListOf<NasFile>() }
    
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) } // Background scanning indicator
    
    var downloadProgress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Scanning Job
    var scanJob: Job? by remember { mutableStateOf(null) }
    
    // Determine if we are viewing a specific comic (folder with zip files)
    val isEpisodeList by remember {
        derivedStateOf { 
            currentFiles.isNotEmpty() && currentFiles.any { 
                val name = it.name.lowercase()
                name.endsWith(".zip") || name.endsWith(".cbz")
            }
        }
    }
    
    // Viewer State
    var currentZipPath by remember { mutableStateOf<String?>(null) }
    var zipImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showControls by remember { mutableStateOf(true) }

    // --- Actions ---

    // Standard load path (non-recursive) - used for episodes
    fun loadPath(path: String) {
        scanJob?.cancel()
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                repository.setCredentials(username, password)
                val targetPath = if (path.endsWith("/")) path else "$path/"
                
                val files = repository.listFiles(targetPath)
                currentFiles.clear()
                currentFiles.addAll(files)
                currentPath = targetPath
                
                // Reset viewer
                currentZipPath = null
                zipImages = emptyList()
            } catch (e: Exception) {
                errorMessage = e.message
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    // Scan category recursively (Streaming)
    fun scanCategory(path: String) {
        scanJob?.cancel()
        scanJob = scope.launch {
            errorMessage = null
            currentFiles.clear()
            
            // Initial loading state (full screen)
            isLoading = true 
            
            try {
                repository.setCredentials(username, password)
                val targetPath = if (path.endsWith("/")) path else "$path/"
                currentPath = targetPath
                
                repository.scanComicFolders(targetPath, maxDepth = 3)
                    .onStart { 
                        isScanning = true
                    }
                    .onCompletion { 
                        isLoading = false
                        isScanning = false
                    }
                    .collect { file ->
                        currentFiles.add(file)
                        if (isLoading) isLoading = false
                    }
                
                // If nothing found after completion
                if (currentFiles.isEmpty()) {
                     val files = repository.listFiles(targetPath)
                     currentFiles.addAll(files)
                }
                
                currentZipPath = null
                zipImages = emptyList()
            } catch (e: Exception) {
                errorMessage = "Scan failed: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                isScanning = false
            }
        }
    }

    // Initialize: Load Categories
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            repository.setCredentials(username, password)
            val roots = repository.listFiles(rootUrl)
                .filter { it.isDirectory }
                .sortedBy { it.name }
            
            categories = roots
            
            if (roots.isNotEmpty()) {
                scanCategory(roots[0].path)
            } else {
                scanCategory(rootUrl)
            }
        } catch (e: Exception) {
            errorMessage = "Failed to connect: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    fun openZipFile(file: NasFile) {
        scanJob?.cancel()
        scope.launch {
            isLoading = true
            downloadProgress = 0f
            errorMessage = null
            try {
                repository.setCredentials(username, password)
                val fileNameHash = "${file.name}_${file.path.hashCode()}"
                val tempPath = repository.getTempFilePath(fileNameHash)
                
                repository.downloadFile(file.path, tempPath) { progress ->
                    downloadProgress = progress
                }

                val images = zipManager.listImagesInZip(tempPath)
                if (images.isNotEmpty()) {
                    currentZipPath = tempPath
                    zipImages = images
                    showControls = false
                } else {
                    errorMessage = "No images found in zip."
                }
            } catch (e: Exception) {
                errorMessage = "Error opening file: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                downloadProgress = 0f
            }
        }
    }
    
    fun navigateBack() {
        val currentCategoryPath = categories.getOrNull(selectedCategoryIndex)?.path
        if (currentCategoryPath != null && currentPath != currentCategoryPath && currentPath != "$currentCategoryPath/") {
            val parent = currentPath.trimEnd('/').substringBeforeLast('/') + "/"
            if (parent == currentCategoryPath || parent == "$currentCategoryPath/") {
                scanCategory(currentCategoryPath)
            } else {
                loadPath(parent)
            }
        }
    }

    // --- UI Structure ---

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (currentZipPath != null) {
            // 1. Viewer Mode
            WebtoonViewer(
                zipPath = currentZipPath!!,
                images = zipImages,
                zipManager = zipManager,
                onClose = {
                    currentZipPath = null
                    zipImages = emptyList()
                },
                showControls = showControls,
                onToggleControls = { showControls = !showControls }
            )
        } else {
            // 2. Browser Mode
            Column(Modifier.fillMaxSize()) {
                // Top Category Tabs
                if (categories.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedCategoryIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        edgePadding = 16.dp,
                        indicator = { tabPositions ->
                            if (selectedCategoryIndex < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(tabPositions[selectedCategoryIndex]),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    ) {
                        categories.forEachIndexed { index, category ->
                            Tab(
                                selected = selectedCategoryIndex == index,
                                onClick = {
                                    selectedCategoryIndex = index
                                    scanCategory(category.path)
                                },
                                text = { 
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (selectedCategoryIndex == index) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedCategoryIndex == index) MaterialTheme.colorScheme.primary else Color.Gray
                                    ) 
                                }
                            )
                        }
                    }
                }

                // Content Area
                Box(Modifier.weight(1f)) {
                    if (isEpisodeList) {
                        EpisodeListView(
                            files = currentFiles,
                            folderPath = currentPath,
                            posterRepository = posterRepository,
                            onFileClick = { openZipFile(it) },
                            onBack = { navigateBack() }
                        )
                    } else {
                        FolderGridView(
                            files = currentFiles,
                            nasRepository = repository,
                            posterRepository = posterRepository,
                            onFolderClick = { loadPath(it.path) },
                            onBack = { navigateBack() },
                            isScanning = isScanning
                        )
                    }
                }
            }
        }
        
        // Loading Overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(enabled = false) {}, 
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    if (downloadProgress > 0f) {
                        Text("Downloading... ${(downloadProgress * 100).toInt()}%", color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("Loading...", color = Color.White)
                    }
                }
            }
        }

        if (errorMessage != null) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { errorMessage = null }) { Text("Dismiss") } }
            ) {
                Text(errorMessage!!)
            }
        }
    }
}

@Composable
fun FolderGridView(
    files: List<NasFile>,
    nasRepository: org.nas.comicsviewer.data.NasRepository,
    posterRepository: org.nas.comicsviewer.data.PosterRepository,
    onFolderClick: (NasFile) -> Unit,
    onBack: () -> Unit,
    isScanning: Boolean = false
) {
    val directories = files.filter { it.isDirectory }
    
    Column(Modifier.fillMaxSize()) {
        if (directories.isEmpty() && !isScanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No comics found", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(directories) { file ->
                    ComicCard(file, nasRepository, posterRepository, onClick = { onFolderClick(file) })
                }
                
                if (isScanning) {
                    item {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeListView(
    files: List<NasFile>,
    folderPath: String,
    posterRepository: org.nas.comicsviewer.data.PosterRepository,
    onFileClick: (NasFile) -> Unit,
    onBack: () -> Unit
) {
    val title = folderPath.trimEnd('/').substringAfterLast('/')
    val cleanTitle = cleanTitle(title)
    
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cleanTitle) {
        try {
            val url = posterRepository.searchPoster(cleanTitle)
            if (url != null) {
                val bytes = posterRepository.downloadImageFromUrl(url)
                posterBitmap = bytes?.toImageBitmap()
            }
        } catch (e: Exception) {}
    }

    Column(Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            if (posterBitmap != null) {
                Image(
                    bitmap = posterBitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)) 
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(),
                verticalAlignment = Alignment.Bottom
            ) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    modifier = Modifier.width(100.dp).aspectRatio(0.7f)
                ) {
                    if (posterBitmap != null) {
                        Image(
                            bitmap = posterBitmap!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Gray))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Total ${files.count { it.name.endsWith(".zip") || it.name.endsWith(".cbz") }} Episodes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
            ) {
                Text("⬅", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val episodes = files.filter { 
                val n = it.name.lowercase()
                n.endsWith(".zip") || n.endsWith(".cbz")
            }.sortedBy { it.name }

            items(episodes) { file ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth().clickable { onFileClick(file) }
                ) {
                    Column {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.size(50.dp, 36.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("EP", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun ComicCard(
    file: NasFile,
    nasRepository: org.nas.comicsviewer.data.NasRepository,
    posterRepository: org.nas.comicsviewer.data.PosterRepository,
    onClick: () -> Unit
) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(file) {
        try {
            val cleanName = cleanTitle(file.name)
            val posterUrl = posterRepository.searchPoster(cleanName)
            if (posterUrl != null) {
                val bytes = posterRepository.downloadImageFromUrl(posterUrl)
                if (bytes != null) thumbnail = bytes.toImageBitmap()
            }
        } catch (e: Exception) {}
    }

    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick)
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.aspectRatio(0.7f).fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(colors = listOf(Color(0xFF2196F3), Color(0xFF1565C0)))),
                        contentAlignment = Alignment.Center
                    ) {
                         Text("📁", style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
fun WebtoonViewer(
    zipPath: String,
    images: List<String>,
    zipManager: org.nas.comicsviewer.data.ZipManager,
    onClose: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggleControls() }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(images) { index, imageName ->
                var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(imageName) {
                    withContext(Dispatchers.Default) {
                        val bytes = zipManager.extractImage(zipPath, imageName)
                        val bitmap = bytes?.toImageBitmap()
                        imageBitmap = bitmap
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap!!,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF1E1E1E)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.background(Color.Black.copy(alpha = 0.8f)).statusBarsPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${currentPage + 1} / ${images.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose) { Text("❌", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            }
        }
        
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
             Column(
                modifier = Modifier.background(Color.Black.copy(alpha = 0.8f)).navigationBarsPadding().padding(16.dp).fillMaxWidth()
            ) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { newValue -> scope.launch { listState.scrollToItem(newValue.toInt()) } },
                    valueRange = 0f..(images.size - 1).coerceAtLeast(0).toFloat(),
                )
            }
        }
    }
}