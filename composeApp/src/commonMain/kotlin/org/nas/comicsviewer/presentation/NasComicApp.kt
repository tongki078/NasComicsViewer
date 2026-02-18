package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.comicsviewer.BackHandler
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.toImageBitmap

// --- STYLE COLORS ---
private val KakaoYellow = Color(0xFFFEE500)
private val BgBlack = Color(0xFF000000)
private val SurfaceGrey = Color(0xFF111111)
private val CardBorder = Color(0xFF1A1A1A)
private val TextPureWhite = Color(0xFFFFFFFF)
private val TextMuted = Color(0xFF777777)

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

    MaterialTheme(colorScheme = darkColorScheme(
        background = BgBlack,
        surface = BgBlack,
        primary = TextPureWhite,
        secondary = KakaoYellow
    )) {
        Box(Modifier.fillMaxSize().background(BgBlack)) {
            if (uiState.isIntroShowing) {
                IntroScreen()
            } else {
                if (currentZipPath != null) {
                     WebtoonViewer(currentZipPath!!, zipManager) { currentZipPath = null }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home",
                                tint = TextPureWhite,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { viewModel.onHome() }
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "NAS WEBTOON",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = TextPureWhite
                            )
                            Spacer(Modifier.weight(1f))
                        }

                        if (uiState.categories.isNotEmpty()) {
                            ScrollableTabRow(
                                selectedTabIndex = uiState.selectedCategoryIndex,
                                containerColor = BgBlack,
                                edgePadding = 20.dp,
                                divider = {},
                                indicator = { positions ->
                                    if (uiState.selectedCategoryIndex < positions.size) {
                                        Box(
                                            Modifier
                                                .tabIndicatorOffset(positions[uiState.selectedCategoryIndex])
                                                .height(2.dp)
                                                .background(TextPureWhite)
                                        )
                                    }
                                }
                            ) {
                                uiState.categories.forEachIndexed { i, cat ->
                                    Tab(
                                        selected = uiState.selectedCategoryIndex == i,
                                        onClick = { viewModel.scanCategory(cat.path, i) },
                                        text = { 
                                            Text(
                                                cat.name,
                                                fontWeight = if (uiState.selectedCategoryIndex == i) FontWeight.Black else FontWeight.Medium,
                                                fontSize = 14.sp
                                            ) 
                                        },
                                        selectedContentColor = TextPureWhite,
                                        unselectedContentColor = TextMuted
                                    )
                                }
                            }
                        }

                        if (uiState.pathHistory.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "〈", 
                                    color = TextPureWhite, 
                                    modifier = Modifier.clickable { viewModel.onBack() }.padding(end = 12.dp),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp
                                )
                                val currentTitle = uiState.currentPath?.trimEnd('/')?.split("/")?.lastOrNull() ?: ""
                                Text(
                                    text = currentTitle,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPureWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(Modifier.weight(1f)) {
                            if (uiState.isSeriesView) {
                                LazyColumn(
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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
            }
            
            if (uiState.isLoading && !uiState.isIntroShowing) {
                Box(Modifier.fillMaxSize().background(BgBlack.copy(0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KakaoYellow, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                }
            }

            uiState.errorMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = Color(0xFF222222),
                    contentColor = TextPureWhite,
                    action = { TextButton(onClick = { viewModel.clearError() }) { Text("OK", color = KakaoYellow) } }
                ) { Text(msg, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun IntroScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(2.dp, TextPureWhite, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(KakaoYellow, RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "NAS WEBTOON",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                ),
                color = TextPureWhite
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Powered by Kavita YAML",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
            Spacer(Modifier.height(48.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(120.dp).height(2.dp),
                color = KakaoYellow,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }
    }
}

@Composable
fun WebtoonViewer(path: String, manager: ZipManager, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    val loadedImages = remember { mutableStateListOf<Pair<String, ImageBitmap>>() }
    var isFinished by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    LaunchedEffect(path) {
        manager.streamAllImages(path, {}) { name, bytes ->
            val bitmap = bytes.toImageBitmap()
            if (bitmap != null) loadedImages.add(name to bitmap)
        }
        isFinished = true
    }

    Box(Modifier.fillMaxSize().background(BgBlack).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(loadedImages) { (_, bitmap) ->
                Image(bitmap, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            if (!isFinished) {
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KakaoYellow)
                    }
                }
            }
        }
        
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(BgBlack.copy(0.8f)).statusBarsPadding().padding(16.dp)) {
                Text(
                    "✕", 
                    color = TextPureWhite, 
                    fontSize = 20.sp, 
                    modifier = Modifier.align(Alignment.CenterStart).clickable { onClose() }.padding(8.dp)
                )
                Text("${currentPage + 1} / ${loadedImages.size}", Modifier.align(Alignment.Center), color = TextPureWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun VolumeItem(file: NasFile, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFF0A0A0A),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, CardBorder)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📖", fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            Text(
                text = file.name, 
                fontWeight = FontWeight.Medium, 
                color = TextPureWhite, 
                fontSize = 13.sp, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text("〉", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun FolderGridView(state: ComicBrowserUiState, onClick: (NasFile) -> Unit, onPage: () -> Unit) {
    val gridState = rememberLazyGridState()
    val lastIndex by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(lastIndex) { if (lastIndex >= state.currentFiles.size - 5) onPage() }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(state.currentFiles, key = { it.path }) { file ->
            ComicCard(file, remember { providePosterRepository() }) { onClick(file) }
        }
        if (state.isScanning || state.totalFoundCount > state.currentFiles.size) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = KakaoYellow, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
fun ComicCard(file: NasFile, repo: PosterRepository, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(file.path) {
        // 획기적 개선: NAS 요청 폭주 방지를 위해 카드별로 랜덤한 미세 지연 시간 추가
        delay(300 + (file.path.hashCode() % 1000).toLong().coerceAtLeast(0))
        repo.searchPoster(file.path)?.let { url ->
            repo.downloadImageFromUrl(url)?.let { bytes -> 
                if (bytes.isNotEmpty()) thumb = bytes.toImageBitmap() 
            }
        }
    }
    
    Column(
        modifier = Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Box(
            Modifier
                .aspectRatio(0.72f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceGrey)
        ) {
            if (thumb != null) {
                Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (file.isDirectory) "FOLDER" else "MANGA",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(0.4f)
                    )
                }
            }
            
            if (file.isDirectory) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, BgBlack.copy(0.6f))))
                )
                Text(
                    "FOLDER",
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    color = KakaoYellow,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        
        Spacer(Modifier.height(6.dp))
        
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 14.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = TextPureWhite
        )
    }
}
