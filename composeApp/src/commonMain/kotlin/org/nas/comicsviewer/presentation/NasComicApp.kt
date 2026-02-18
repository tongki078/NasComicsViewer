package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
    
    var currentZipPath by remember { mutableStateOf<String?>(null) }
    var zipImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showControls by remember { mutableStateOf(true) }
    var isStreamingLoading by remember { mutableStateOf(false) }
    
    val rootUrl = "smb://192.168.0.2/video/GDS3/GDRIVE/READING/만화/"

    LaunchedEffect(Unit) {
        viewModel.initialize(rootUrl, "takumi", "qksthd078!@")
    }

    // 뒤로가기 핸들러 최상위 배치
    BackHandler(enabled = currentZipPath != null || uiState.pathHistory.size > 1) {
        if (currentZipPath != null) {
            currentZipPath = null
            zipImages = emptyList()
            showControls = true
        } else {
            viewModel.onBack()
        }
    }

    fun openZipStreaming(file: NasFile) {
        scope.launch {
            isStreamingLoading = true
            try {
                val images = zipManager.listImagesInZip(file.path)
                if (images.isNotEmpty()) {
                    currentZipPath = file.path
                    zipImages = images
                    showControls = false
                } else {
                    viewModel.showError("만화책 속 이미지를 찾을 수 없습니다. (ZIP 인코딩 또는 경로 문제)")
                }
            } catch (e: Exception) {
                viewModel.showError("연결 오류: ${e.message}")
            } finally {
                isStreamingLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onOpenZipRequested.collect { file ->
            openZipStreaming(file)
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
        primary = Color(0xFF00DC64)
    )) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (currentZipPath != null) {
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
                    // Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🏠", modifier = Modifier.clickable { viewModel.onHome() }, fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("NAS WEBTOON", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }

                    // Tabs
                    if (uiState.categories.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = uiState.selectedCategoryIndex,
                            containerColor = Color.Transparent,
                            edgePadding = 16.dp,
                            indicator = { tabPositions ->
                                if (uiState.selectedCategoryIndex < tabPositions.size) {
                                    Box(Modifier.tabIndicatorOffset(tabPositions[uiState.selectedCategoryIndex]).height(3.dp).background(MaterialTheme.colorScheme.primary))
                                }
                            }
                        ) {
                            uiState.categories.forEachIndexed { index, category ->
                                Tab(
                                    selected = uiState.selectedCategoryIndex == index,
                                    onClick = { viewModel.scanCategory(category.path, index) },
                                    text = { Text(category.name) }
                                )
                            }
                        }
                    }

                    // Breadcrumbs
                    if (uiState.pathHistory.size > 1) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("◀", modifier = Modifier.clickable { viewModel.onBack() }.padding(4.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(uiState.currentPath?.split("/")?.lastOrNull() ?: "", fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(Modifier.weight(1f)) {
                        if (uiState.isSeriesView) {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.currentFiles) { file ->
                                    VolumeItem(file, onClick = { viewModel.onFileClick(file) })
                                }
                            }
                        } else {
                            FolderGridView(uiState, onFileClick = { file -> viewModel.onFileClick(file) }, onLoadNextPage = { viewModel.loadNextPage() })
                        }
                    }
                }
            }
            
            if (uiState.isLoading || isStreamingLoading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.errorMessage?.let { msg ->
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = { viewModel.clearError() }) { Text("확인") } }) { Text(msg) }
            }
        }
    }
}

@Composable
fun VolumeItem(file: NasFile, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📖", fontSize = 24.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(file.name, fontWeight = FontWeight.Bold, color = Color.White)
                Text("ZIP Archive", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(Modifier.weight(1f))
            Text("▶", color = Color(0xFF00DC64))
        }
    }
}

@Composable
fun FolderGridView(uiState: ComicBrowserUiState, onFileClick: (NasFile) -> Unit, onLoadNextPage: () -> Unit) {
    val gridState = rememberLazyGridState()
    val shouldLoadMore = remember { derivedStateOf { 
        val info = gridState.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
    }}

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !uiState.isLoading) onLoadNextPage()
    }

    LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(uiState.currentFiles, key = { it.path }) { file ->
            ComicCard(file, remember { providePosterRepository() }, onClick = { onFileClick(file) })
        }
        if (uiState.isScanning || uiState.totalFoundCount > uiState.currentFiles.size) {
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
        }
    }
}

@Composable
fun ComicCard(file: NasFile, posterRepository: PosterRepository, onClick: () -> Unit) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        try {
            delay(200)
            val searchTitle = cleanTitle(if (file.isDirectory) file.name else file.name.substringBeforeLast("."))
            posterRepository.searchPoster(searchTitle)?.let { url ->
                posterRepository.downloadImageFromUrl(url)?.let { bytes -> thumbnail = bytes.toImageBitmap() }
            }
        } catch (e: Exception) {}
    }

    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(0.75f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFF222222))) {
            if (thumbnail != null) Image(thumbnail!!, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (file.isDirectory) "📁" else "📖", fontSize = 32.sp, modifier = Modifier.alpha(0.3f)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
    }
}

@Composable
fun WebtoonViewer(zipPath: String, images: List<String>, zipManager: ZipManager, onClose: () -> Unit, showControls: Boolean, onToggleControls: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    
    val loadedImages = remember { mutableStateMapOf<String, ImageBitmap>() }
    var isInitialLoading by remember { mutableStateOf(true) }

    LaunchedEffect(zipPath) {
        zipManager.streamAllImages(zipPath) { name, bytes ->
            val bitmap = bytes.toImageBitmap()
            if (bitmap != null) {
                loadedImages[name] = bitmap
                isInitialLoading = false
            }
        }
    }
    
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleControls() }) {
        if (isInitialLoading && loadedImages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(images) { imageName ->
                val bitmap = loadedImages[imageName]
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap, 
                        contentDescription = null, 
                        contentScale = ContentScale.FillWidth, 
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(500.dp), contentAlignment = Alignment.Center) { 
                        CircularProgressIndicator(Modifier.size(32.dp)) 
                    }
                }
            }
        }

        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.7f)).statusBarsPadding().padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 24.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("${currentPage + 1} / ${images.size}", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(48.dp))
                }
                Box(Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).navigationBarsPadding().padding(24.dp).fillMaxWidth()) {
                    Slider(value = currentPage.toFloat(), onValueChange = { scope.launch { listState.scrollToItem(it.toInt()) } }, valueRange = 0f..(images.size - 1).coerceAtLeast(0).toFloat())
                }
            }
        }
    }
}