package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.nas.comicsviewer.BackHandler
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.toImageBitmap

@Composable
fun NasComicApp(viewModel: ComicViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val zipManager = remember { provideZipManager() }
    val nasRepository = remember { provideNasRepository() }
    
    var currentZipPath by remember { mutableStateOf<String?>(null) }
    var zipImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showControls by remember { mutableStateOf(true) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    
    val rootUrl = "smb://192.168.0.2/video/GDS3/GDRIVE/READING/만화/"

    LaunchedEffect(Unit) {
        viewModel.initialize(rootUrl, "takumi", "qksthd078!@")
    }

    fun openZipFile(file: NasFile) {
        scope.launch {
            isDownloading = true
            downloadProgress = 0f
            try {
                val fileNameHash = "full_${file.path.hashCode()}.zip"
                val tempPath = nasRepository.getTempFilePath(fileNameHash)
                nasRepository.downloadFile(file.path, tempPath) { progress -> downloadProgress = progress }
                val images = zipManager.listImagesInZip(tempPath)
                if (images.isNotEmpty()) {
                    currentZipPath = tempPath
                    zipImages = images
                    showControls = false
                } 
            } catch (e: Exception) {
                // Handle error
            } finally {
                isDownloading = false
                downloadProgress = 0f
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onOpenZipRequested.collect { file ->
            openZipFile(file)
        }
    }

    val closeViewer = {
        currentZipPath = null
        zipImages = emptyList()
        showControls = true
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF111111), 
        surface = Color(0xFF111111), 
        onSurface = Color.White, 
        primary = Color(0xFF00DC64), 
        secondary = Color(0xFF00DC64),
        surfaceVariant = Color(0xFF1E1E1E), 
        onSurfaceVariant = Color.White
    )) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (currentZipPath != null) {
                 BackHandler { closeViewer() }
                 
                 WebtoonViewer(
                     zipPath = currentZipPath!!, 
                     images = zipImages, 
                     zipManager = zipManager, 
                     onClose = closeViewer, 
                     showControls = showControls, 
                     onToggleControls = { showControls = !showControls }
                 )
            } else {
                Column(Modifier.fillMaxSize()) {
                    // --- NAVER WEBTOON STYLE TOP BAR ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.onHome() },
                            color = Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("🏠", fontSize = 22.sp)
                            }
                        }
                        
                        Spacer(Modifier.width(8.dp))
                        
                        Text(
                            "NAS WEBTOON",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp,
                                fontSize = 22.sp
                            ),
                            color = Color.White
                        )
                        
                        Spacer(Modifier.weight(1f))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 12.dp).clickable { /* TODO: Search */ })
                            Text("👤", fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp).clickable { /* TODO: Profile */ })
                        }
                    }

                    // --- CATEGORY TABS ---
                    if (uiState.categories.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = uiState.selectedCategoryIndex, 
                            containerColor = MaterialTheme.colorScheme.surface, 
                            edgePadding = 16.dp, 
                            indicator = { tabPositions -> 
                                if (uiState.selectedCategoryIndex < tabPositions.size) { 
                                    Box(
                                        Modifier
                                            .tabIndicatorOffset(tabPositions[uiState.selectedCategoryIndex])
                                            .height(3.dp)
                                            .padding(horizontal = 4.dp)
                                            .background(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    )
                                } 
                            },
                            divider = { HorizontalDivider(color = Color(0xFF252525), thickness = 0.5.dp) }
                        ) {
                            uiState.categories.forEachIndexed { index, category ->
                                Tab(
                                    selected = uiState.selectedCategoryIndex == index, 
                                    onClick = { viewModel.scanCategory(category.path, index) }, 
                                    selectedContentColor = MaterialTheme.colorScheme.primary,
                                    unselectedContentColor = Color(0xFF888888),
                                    text = { 
                                        Text(
                                            category.name, 
                                            fontSize = 15.sp,
                                            fontWeight = if (uiState.selectedCategoryIndex == index) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 12.dp)
                                        ) 
                                    }
                                )
                            }
                        }
                    }

                    // --- BREADCRUMBS / NAVIGATION ---
                    if (uiState.pathHistory.size > 1) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A))
                                .padding(horizontal = 16.dp, vertical = 12.dp), 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.onBack() },
                                color = Color(0xFF333333)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("◀", color = Color.White, fontSize = 10.sp)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            val currentDirName = uiState.currentPath?.trimEnd('/')?.split("/")?.lastOrNull() ?: "Home"
                            Text(
                                text = currentDirName, 
                                color = Color.White, 
                                style = MaterialTheme.typography.bodyMedium, 
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = Color(0xFF252525), thickness = 0.5.dp)
                    }

                    Box(Modifier.weight(1f)) {
                         FolderGridView(uiState, onFileClick = { file ->
                            viewModel.onFileClick(file)
                         }, onLoadNextPage = { viewModel.loadNextPage() })
                    }
                }
            }
            
            // --- LOADING OVERLAYS ---
            if (uiState.isLoading || isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable(enabled = false) {}, 
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isDownloading) {
                            Text("Downloading Webtoon...", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress }, 
                                color = MaterialTheme.colorScheme.primary, 
                                trackColor = Color.Gray.copy(alpha = 0.3f),
                                modifier = Modifier.width(240.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(downloadProgress * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                            if (uiState.isScanning) {
                                Spacer(Modifier.height(16.dp))
                                Text("Scanning library items...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            uiState.errorMessage?.let {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = Color(0xFFE74C3C),
                    contentColor = Color.White
                ) { Text(it) }
            }
        }
    }
}

@Composable
fun FolderGridView(uiState: ComicBrowserUiState, onFileClick: (NasFile) -> Unit, onLoadNextPage: () -> Unit) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .map { it ?: 0 }
            .distinctUntilChanged()
            .filter { index -> uiState.currentFiles.isNotEmpty() && index >= uiState.currentFiles.size - 8 } 
            .collect { 
                onLoadNextPage()
            }
    }

    if (uiState.currentFiles.isEmpty() && !uiState.isScanning && !uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("No items found here", color = Color.Gray, style = MaterialTheme.typography.bodyMedium) 
            }
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3), 
            contentPadding = PaddingValues(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 32.dp), 
            horizontalArrangement = Arrangement.spacedBy(6.dp), 
            verticalArrangement = Arrangement.spacedBy(16.dp), 
            modifier = Modifier.fillMaxSize()
        ) {
            items(uiState.currentFiles, key = { it.path }) { file -> 
                 val posterRepository = remember { providePosterRepository() }
                 ComicCard(file, posterRepository, onClick = { onFileClick(file) })
            }
            if (uiState.isPaging || uiState.isScanning) {
                item(span = { GridItemSpan(maxLineSpan) }) { 
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { 
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) 
                    } 
                }
            }
        }
    }
}

@Composable
fun ComicCard(file: NasFile, posterRepository: PosterRepository, onClick: () -> Unit) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(file.path) {
        try {
            delay(300) 
            val baseName = if (file.isDirectory) file.name else file.name.substringBeforeLast(".")
            val searchTitle = cleanTitle(baseName)
            
            if (searchTitle.isNotBlank()) {
                posterRepository.searchPoster(searchTitle)?.let { url ->
                    posterRepository.downloadImageFromUrl(url)?.let { bytes -> 
                        thumbnail = bytes.toImageBitmap() 
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .aspectRatio(0.75f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp)) 
                .background(Color(0xFF1E1E1E))
        ) {
            if (thumbnail != null) {
                Image(
                    thumbnail!!, 
                    null, 
                    contentScale = ContentScale.Crop, 
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            if (file.isDirectory) listOf(Color(0xFF2C3E50), Color(0xFF121212))
                            else listOf(Color(0xFF34495E), Color(0xFF121212))
                        )
                    ), 
                    contentAlignment = Alignment.Center
                ) {
                     Text(if (file.isDirectory) "📁" else "📖", fontSize = 28.sp, modifier = Modifier.alpha(0.5f))
                }
            }
            
            if (file.isDirectory) {
                Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopStart) {
                    Surface(
                        color = Color(0xFF00DC64), 
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            "FOLDER", 
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), 
                            style = MaterialTheme.typography.labelSmall, 
                            color = Color.Black, 
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.3f))
                    )
                )
            )
        }
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = file.name, 
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 16.sp
            ), 
            color = Color(0xFFEEEEEE), 
            maxLines = 2, 
            overflow = TextOverflow.Ellipsis, 
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
fun WebtoonViewer(zipPath: String, images: List<String>, zipManager: ZipManager, onClose: () -> Unit, showControls: Boolean, onToggleControls: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleControls() }) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(images) { imageName ->
                var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(imageName) { 
                    delay(100) 
                    imageBitmap = zipManager.extractImage(zipPath, imageName)?.toImageBitmap() 
                }
                if (imageBitmap != null) {
                    Image(imageBitmap!!, null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                } else {
                    Box(Modifier.fillMaxWidth().height(500.dp), contentAlignment = Alignment.Center) { 
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) 
                    }
                }
            }
        }
        
        // Viewer Top Bar
        AnimatedVisibility(showControls, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .fillMaxWidth(), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { 
                    Text("✕", color = Color.White, fontSize = 24.sp) 
                }
                
                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "${currentPage + 1} / ${images.size}", 
                        color = Color.White, 
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(Modifier.width(48.dp))
            }
        }
        
        // Viewer Bottom Slider
        AnimatedVisibility(showControls, enter = fadeIn() + slideInVertically(initialOffsetY = { it }), exit = fadeOut() + slideOutVertically(targetOffsetY = { it }), modifier = Modifier.align(Alignment.BottomCenter)) {
             Column(
                 Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                     .navigationBarsPadding()
                     .padding(horizontal = 24.dp, vertical = 24.dp)
             ) {
                Slider(
                    value = currentPage.toFloat(), 
                    onValueChange = { scope.launch { listState.scrollToItem(it.toInt()) } }, 
                    valueRange = 0f..(images.size - 1).coerceAtLeast(0).toFloat(), 
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary, 
                        activeTrackColor = MaterialTheme.colorScheme.primary, 
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}