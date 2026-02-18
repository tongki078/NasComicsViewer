package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.comicsviewer.BackHandler
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.toImageBitmap

@Composable
fun NasComicApp(viewModel: ComicViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val zipManager = remember { provideZipManager() }
    var currentZipPath by remember { mutableStateOf<String?>(null) }
    
    val rootUrl = "smb://192.168.0.2/video/GDS3/GDRIVE/READING/만화/"

    LaunchedEffect(Unit) {
        viewModel.initialize(rootUrl, "takumi", "qksthd078!@")
    }

    BackHandler(enabled = currentZipPath != null || uiState.pathHistory.size > 1) {
        if (currentZipPath != null) currentZipPath = null else viewModel.onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.onOpenZipRequested.collect { file ->
            currentZipPath = file.path
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF111111), primary = Color(0xFF00DC64))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (currentZipPath != null) {
                 WebtoonViewer(currentZipPath!!, zipManager) { currentZipPath = null }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🏠", Modifier.clickable { viewModel.onHome() }, fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("NAS WEBTOON", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }

                    if (uiState.categories.isNotEmpty()) {
                        ScrollableTabRow(selectedTabIndex = uiState.selectedCategoryIndex, edgePadding = 16.dp, indicator = { positions ->
                            if (uiState.selectedCategoryIndex < positions.size) {
                                Box(Modifier.tabIndicatorOffset(positions[uiState.selectedCategoryIndex]).height(3.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }) {
                            uiState.categories.forEachIndexed { i, cat ->
                                Tab(selected = uiState.selectedCategoryIndex == i, onClick = { viewModel.scanCategory(cat.path, i) }, text = { Text(cat.name) })
                            }
                        }
                    }

                    if (uiState.pathHistory.size > 1) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("◀", Modifier.clickable { viewModel.onBack() }.padding(4.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(uiState.currentPath?.split("/")?.lastOrNull() ?: "", fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(Modifier.weight(1f)) {
                        if (uiState.isSeriesView) {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.currentFiles) { file ->
                                    VolumeItem(file) { viewModel.onFileClick(file) }
                                }
                            }
                        } else {
                            FolderGridView(uiState, { viewModel.onFileClick(it) }, { viewModel.loadNextPage() })
                        }
                    }
                }
            }
            
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
fun WebtoonViewer(path: String, manager: ZipManager, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    val loadedImages = remember { mutableStateListOf<Pair<String, ImageBitmap>>() }
    var isFinished by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        manager.streamAllImages(path, {}) { name, bytes ->
            val bitmap = bytes.toImageBitmap()
            if (bitmap != null) {
                loadedImages.add(name to bitmap)
            }
        }
        isFinished = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(loadedImages) { (_, bitmap) ->
                Image(bitmap, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            if (!isFinished) {
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (showControls) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(0.7f)).statusBarsPadding().padding(16.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) { Text("✕", color = Color.White, fontSize = 24.sp) }
                Text("${listState.firstVisibleItemIndex + 1} / ${loadedImages.size}", Modifier.align(Alignment.Center), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VolumeItem(file: NasFile, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📖", fontSize = 24.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(file.name, fontWeight = FontWeight.Bold, color = Color.White)
                Text("ZIP Archive", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun FolderGridView(state: ComicBrowserUiState, onClick: (NasFile) -> Unit, onPage: () -> Unit) {
    val gridState = rememberLazyGridState()
    val lastIndex by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(lastIndex) { if (lastIndex >= state.currentFiles.size - 5) onPage() }

    LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(state.currentFiles) { ComicCard(it, remember { providePosterRepository() }) { onClick(it) } }
        if (state.isScanning) {
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
        }
    }
}

@Composable
fun ComicCard(file: NasFile, repo: PosterRepository, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        // 획기적 개선: 스크롤 시 API 남용 방지를 위해 지연 시간 추가
        delay(300)
        val title = if (file.isDirectory) file.name else file.name.substringBeforeLast(".")
        repo.searchPoster(cleanTitle(title))?.let { url ->
            repo.downloadImageFromUrl(url)?.let { bytes -> thumb = bytes.toImageBitmap() }
        }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF222222))) {
            if (thumb != null) {
                Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (file.isDirectory) "📁" else "📖", modifier = Modifier.alpha(0.3f), fontSize = 32.sp)
                }
            }
        }
        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = Color.White, modifier = Modifier.padding(top = 4.dp))
    }
}
