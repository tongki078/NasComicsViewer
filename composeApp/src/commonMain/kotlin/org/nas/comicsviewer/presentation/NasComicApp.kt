package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.BackHandler
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.toImageBitmap

private val KakaoYellow = Color(0xFFFEE500)
private val BgBlack = Color(0xFF000000)
private val SurfaceGrey = Color(0xFF111111)
private val TextPureWhite = Color(0xFFFFFFFF)
private val TextMuted = Color(0xFF888888)

@Composable
fun NasComicApp(viewModel: ComicViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val zipManager = remember { provideZipManager() }
    val scope = rememberCoroutineScope()
    
    val mainGridState = rememberLazyGridState()

    val canBack by remember {
        derivedStateOf {
            uiState.selectedZipPath != null || uiState.pathHistory.size > 1 || uiState.isSearchMode || uiState.isSeriesView
        }
    }

    BackHandler(enabled = canBack) {
        viewModel.onBack()
    }
    
    LaunchedEffect(uiState.isWebtoonMode) {
        zipManager.switchServer(uiState.isWebtoonMode)
        mainGridState.scrollToItem(0)
    }

    LaunchedEffect(uiState.selectedCategoryIndex, uiState.currentPath) {
        if (!uiState.isSeriesView) {
            mainGridState.scrollToItem(0)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = BgBlack, surface = BgBlack, primary = TextPureWhite)) {
        Box(Modifier.fillMaxSize().background(BgBlack)) {
            if (uiState.isIntroShowing) {
                IntroScreen()
            } else if (uiState.selectedZipPath != null) {
                 WebtoonViewer(
                     path = uiState.selectedZipPath!!, 
                     manager = zipManager, 
                     posterUrl = uiState.viewerPosterUrl,
                     repo = viewModel.posterRepository,
                     onClose = { viewModel.closeViewer() }, 
                     onError = { viewModel.showError(it) },
                     uiState = uiState,
                     viewModel = viewModel
                 )
            } else if (uiState.isSearchMode) {
                SearchScreen(
                    state = uiState,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onSearch = { viewModel.onSearchSubmit(it) },
                    onClose = { viewModel.toggleSearchMode(false) },
                    onFileClick = { viewModel.onFileClick(it) },
                    onClearHistory = { viewModel.clearRecentSearches() },
                    viewModel = viewModel
                )
            } else if (uiState.isSeriesView) {
                SeriesDetailScreen(uiState, { viewModel.onFileClick(it) }, { viewModel.onBack() }, viewModel.posterRepository)
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        uiState = uiState,
                        onHomeClick = { viewModel.onHome() }, 
                        onSearchClick = { viewModel.toggleSearchMode(true) },
                        onModeToggle = { viewModel.toggleServerMode() }
                    )
                    
                    if (uiState.categories.isNotEmpty()) {
                        CategoryTabs(uiState) { path, index -> viewModel.scanBooks(path, index) }
                    }
                    
                    Box(Modifier.weight(1f)) {
                        val isWriterCategory = !uiState.isWebtoonMode && uiState.categories.getOrNull(uiState.selectedCategoryIndex)?.name == "작가"
                        val isAtRoot = uiState.pathHistory.size <= 1

                        if (isWriterCategory && isAtRoot) {
                            FolderListView(
                                files = uiState.currentFiles,
                                isLoading = uiState.isLoading,
                                onFileClick = { viewModel.onFileClick(it) }
                            )
                        } else {
                            Box(Modifier.fillMaxSize()) {
                                FolderGridView(
                                    files = uiState.currentFiles,
                                    recentComics = uiState.recentComics,
                                    isLoading = uiState.isLoading,
                                    isLoadingMore = uiState.isLoadingMore,
                                    onFileClick = { viewModel.onFileClick(it) },
                                    onLoadMore = { viewModel.loadMoreBooks() },
                                    posterRepository = viewModel.posterRepository,
                                    gridState = mainGridState,
                                    showCategoryBadge = false
                                )

                                // 사이드 인덱스 바 추가
                                if (uiState.currentFiles.isNotEmpty() && !uiState.isLoading) {
                                    SideIndexBar(
                                        files = uiState.currentFiles,
                                        recentComicsCount = uiState.recentComics.size,
                                        onJump = { index ->
                                            scope.launch {
                                                mainGridState.scrollToItem(index)
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.CenterEnd)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            uiState.errorMessage?.let { msg ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = Color(0xFF222222), action = { TextButton({ viewModel.clearError() }) { Text("OK") } }) { Text(msg) }
            }
        }
    }
}

@Composable
fun SideIndexBar(
    files: List<NasFile>, 
    recentComicsCount: Int, 
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val initials = remember(files) {
        files.map { getInitialSound(it.name) }.distinct()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(40.dp)
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(Color.Black.copy(0.3f), RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp)
        ) {
            initials.forEach { initial ->
                Text(
                    text = initial,
                    color = KakaoYellow.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val firstInGroup = files.indexOfFirst { getInitialSound(it.name) == initial }
                            if (firstInGroup != -1) {
                                val offset = if (recentComicsCount > 0) 1 else 0
                                onJump(firstInGroup + offset)
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun RecentComicsCarousel(recentComics: List<NasFile>, repo: PosterRepository, onFileClick: (NasFile) -> Unit) {
    if (recentComics.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text("최근 본 작품", color = TextPureWhite, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recentComics) { file ->
                Column(Modifier.width(100.dp).clickable { onFileClick(file) }) {
                    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(file.metadata?.posterUrl) {
                        file.metadata?.posterUrl?.let { thumb = repo.getImage(it) }
                    }
                    Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceGrey)) {
                        if (thumb != null) Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(file.name, color = TextPureWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = SurfaceGrey, thickness = 4.dp)
    }
}

@Composable
fun FolderListView(
    files: List<NasFile>,
    isLoading: Boolean,
    onFileClick: (NasFile) -> Unit,
    isWebtoonRoot: Boolean = false
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (isLoading && files.isEmpty()) {
            items(15) {
                Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceGrey))
            }
        } else {
            items(files) { file ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onFileClick(file) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = if (isWebtoonRoot) Icons.AutoMirrored.Filled.List else Icons.Default.Person
                    Icon(icon, null, tint = TextMuted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(file.name, color = TextPureWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextMuted.copy(0.3f), modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = SurfaceGrey, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun FolderGridView(
    files: List<NasFile>,
    recentComics: List<NasFile> = emptyList(),
    isLoading: Boolean,
    isLoadingMore: Boolean,
    onFileClick: (NasFile) -> Unit,
    onLoadMore: () -> Unit,
    posterRepository: PosterRepository,
    gridState: LazyGridState,
    showCategoryBadge: Boolean = false
) {
    val shouldLoadMore by remember(files.size, isLoading, isLoadingMore) {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= (files.size + if(recentComics.isNotEmpty()) 1 else 0) - 10 && !isLoading && !isLoadingMore
        }
    }
    
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (recentComics.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecentComicsCarousel(recentComics, posterRepository, onFileClick)
            }
        }

        if (isLoading && files.isEmpty()) {
            items(12) { 
                Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceGrey))
            }
        } else {
            items(files, key = { it.path }) { file ->
                ComicCard(file, posterRepository, showCategoryBadge) { onFileClick(file) }
            }
            
            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KakaoYellow, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

fun getInitialSound(text: String): String {
    if (text.isEmpty()) return "#"
    val firstChar = text.trim()[0]
    if (firstChar in '가'..'힣') {
        val initialIdx = (firstChar - '가') / 28 / 21
        val initials = listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
        val targetIdx = when(initialIdx) {
            0, 1 -> 0 // ㄱ, ㄲ -> ㄱ
            2 -> 1    // ㄴ
            3, 4 -> 2 // ㄷ, ㄸ -> ㄷ
            5 -> 3    // ㄹ
            6 -> 4    // ㅁ
            7, 8 -> 5 // ㅂ, ㅃ -> ㅂ
            9, 10 -> 6// ㅅ, ㅆ -> ㅅ
            11 -> 7   // ㅇ
            12, 13 -> 8// ㅈ, ㅉ -> ㅈ
            14 -> 9   // ㅊ
            15 -> 10  // ㅋ
            16 -> 11  // ㅌ
            17 -> 12  // ㅍ
            18 -> 13  // ㅎ
            else -> 0
        }
        return initials.getOrElse(targetIdx) { "ㄱ" }
    }
    if (firstChar in 'a'..'z' || firstChar in 'A'..'Z') {
        return firstChar.uppercase()
    }
    if (firstChar in '0'..'9') {
        return "0-9"
    }
    return "#"
}

@Composable
fun ComicCard(file: NasFile, repo: PosterRepository, showCategoryBadge: Boolean = false, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.metadata?.posterUrl) {
        file.metadata?.posterUrl?.let { thumb = repo.getImage(it) }
    }
    Column(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) {
            if (thumb != null) {
                Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) { 
                     Text("MANGA", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(0.4f)) 
                }
            }
            
            if (showCategoryBadge) {
                val cat = file.metadata?.category ?: file.path.substringBefore("/")
                if (cat.isNotEmpty()) {
                    Surface(
                        color = KakaoYellow.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(bottomEnd = 4.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = cat,
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (file.isDirectory) {
                Box(Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("SERIES", color = KakaoYellow, fontSize = 7.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(file.name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextPureWhite)
    }
}

@Composable
fun SeriesDetailScreen(state: ComicBrowserUiState, onVolumeClick: (NasFile) -> Unit, onBack: () -> Unit, repo: PosterRepository) {
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val metadata = state.selectedMetadata
    LaunchedEffect(metadata?.posterUrl) {
        metadata?.posterUrl?.let { url -> posterBitmap = repo.getImage(url) }
    }
    Column(Modifier.fillMaxSize().background(BgBlack).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            if (posterBitmap != null) {
                Image(posterBitmap!!, null, Modifier.fillMaxSize().blur(30.dp).alpha(0.3f), contentScale = ContentScale.Crop)
                Image(posterBitmap!!, null, Modifier.align(Alignment.BottomCenter).width(200.dp).height(280.dp).padding(bottom = 20.dp).clip(RoundedCornerShape(8.dp)).border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, BgBlack.copy(alpha = 0.5f), BgBlack))))
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(text = metadata?.title ?: "제목 없음", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = TextPureWhite)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val writers = if (!metadata?.writers.isNullOrEmpty()) metadata?.writers?.joinToString(", ") ?: "" else "작가 미상"
                Text(text = writers, color = KakaoYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Surface(color = Color(0xFF222222), shape = RoundedCornerShape(4.dp)) {
                    Text(text = metadata?.status ?: "Unknown", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (!metadata?.genres.isNullOrEmpty()) {
                FlowRow(mainAxisSpacing = 8.dp, crossAxisSpacing = 8.dp) {
                    metadata?.genres?.forEach { genre ->
                        Surface(color = SurfaceGrey, shape = CircleShape, border = BorderStroke(0.5.dp, Color.White.copy(0.1f))) {
                            Text(genre, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Text(text = metadata?.summary ?: "등록된 줄거리가 없습니다.", color = TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(40.dp))
            Text("에피소드 (${state.seriesEpisodes.size})", color = TextPureWhite, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 60.dp)) {
            state.seriesEpisodes.forEach { file ->
                VolumeListItem(file, repo) { onVolumeClick(file) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun VolumeListItem(file: NasFile, repo: PosterRepository, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    val posterUrl = file.metadata?.posterUrl ?: file.path
    LaunchedEffect(posterUrl) { 
        thumb = repo.getImage(posterUrl) 
    }
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color(0xFF0D0D0D), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.5.dp, Color(0xFF1A1A1A))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) {
                if (thumb != null) Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), Alignment.Center) { Text("BOOK", fontSize = 8.sp, color = TextMuted) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, color = TextPureWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("바로 읽기", color = KakaoYellow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun FlowRow(mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp, crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val placeholders = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rows = mutableListOf<List<androidx.compose.ui.layout.Placeable>>()
        var currentRow = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var currentRowWidth = 0
        placeholders.forEach { placeable ->
            if (currentRowWidth + placeable.width > constraints.maxWidth && currentRow.isNotEmpty()) {
                rows.add(currentRow); currentRow = mutableListOf(); currentRowWidth = 0
            }
            currentRow.add(placeable); currentRowWidth += placeable.width + mainAxisSpacing.roundToPx()
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)
        val height = rows.sumOf { r -> r.maxOf { it.height } } + (rows.size - 1).coerceAtLeast(0) * crossAxisSpacing.roundToPx()
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                var x = 0; val rowHeight = row.maxOf { it.height }
                row.forEach { placeable -> placeable.placeRelative(x, y); x += placeable.width + mainAxisSpacing.roundToPx() }
                y += rowHeight + crossAxisSpacing.roundToPx()
            }
        }
    }
}

@Composable
fun WebtoonViewer(
    path: String, 
    manager: ZipManager, 
    posterUrl: String?, 
    repo: PosterRepository, 
    onClose: () -> Unit, 
    onError: (String) -> Unit,
    uiState: ComicBrowserUiState,
    viewModel: ComicViewModel
) {
    val listState = rememberLazyListState()
    val imageNames = remember { mutableStateListOf<String>() }
    var isListLoaded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val sessionCache = remember { mutableMapOf<String, ImageBitmap>() }
    val scope = rememberCoroutineScope()

    var showEpisodeList by remember { mutableStateOf(false) }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = androidx.compose.ui.geometry.Offset(0f, offset.y + offsetChange.y)
    }

    var brightness by remember { mutableStateOf(1f) }
    var isSepia by remember { mutableStateOf(false) }
    var isGray by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var isAutoScrollEnabled by remember { mutableStateOf(false) }
    var autoScrollSpeed by remember { mutableStateOf(1f) } 

    LaunchedEffect(posterUrl) { posterUrl?.let { posterBitmap = repo.getImage(it) } }

    LaunchedEffect(path) {
        isListLoaded = false
        imageNames.clear()
        try {
            val names = manager.listImagesInZip(path)
            if (names.isNotEmpty()) {
                imageNames.addAll(names)
                isListLoaded = true
                val lastPos = uiState.readingPositions[path] ?: 0
                if (lastPos > 0) {
                    scope.launch { listState.scrollToItem(lastPos) }
                }
                names.take(3).forEach { name ->
                    scope.launch(Dispatchers.Default) {
                        val bytes = manager.extractImage(path, name)
                        bytes?.toImageBitmap()?.let { sessionCache[name] = it }
                    }
                }
            } else { 
                onError("이미지 목록을 가져올 수 없습니다.") 
                onClose()
            }
        } catch (e: Exception) {
            onError("파일을 열 수 없습니다: ${e.message}")
            onClose()
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (isListLoaded) {
            viewModel.saveReadingPosition(path, listState.firstVisibleItemIndex)
        }
    }

    LaunchedEffect(isAutoScrollEnabled, autoScrollSpeed) {
        if (isAutoScrollEnabled) {
            while (isActive) {
                try {
                    listState.scrollBy(autoScrollSpeed)
                } catch (e: Exception) {
                    isAutoScrollEnabled = false
                    break
                }
                delay(16)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!isListLoaded) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (posterBitmap != null) {
                    Image(posterBitmap!!, null, Modifier.fillMaxSize().blur(40.dp).alpha(0.4f), contentScale = ContentScale.Crop)
                }
                CircularProgressIndicator(color = KakaoYellow)
            }
        } else {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = 0f,
                        translationY = offset.y
                    )
                    .transformable(state = transformState)
            ) {
                LazyColumn(
                    state = listState, 
                    modifier = Modifier.fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { 
                            showControls = !showControls 
                            if (showControls) {
                                showSettings = false
                                showEpisodeList = false
                            }
                        }
                ) {
                    itemsIndexed(imageNames) { index, name ->
                        WebtoonPage(path, name, manager, sessionCache, isSepia, isGray)
                        LaunchedEffect(listState.firstVisibleItemIndex) {
                            val prefetchIndices = listOf(index + 1, index + 2)
                            prefetchIndices.forEach { i ->
                                if (i < imageNames.size && i <= listState.firstVisibleItemIndex + 5) {
                                    val nextName = imageNames[i]
                                    if (!sessionCache.containsKey(nextName)) {
                                        scope.launch(Dispatchers.Default) {
                                            val bytes = manager.extractImage(path, nextName)
                                            bytes?.toImageBitmap()?.let { sessionCache[nextName] = it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (brightness < 1f) {
            Box(Modifier.fillMaxSize().run { 
                val base = background(Color.Black.copy(alpha = 1f - brightness))
                if (showControls) base else base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = true }
            })
        }
        
        AnimatedVisibility(showControls, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
            Box(Modifier.fillMaxWidth().background(BgBlack.copy(0.8f)).statusBarsPadding().padding(vertical = 4.dp, horizontal = 12.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(36.dp)) {
                    Icon(Icons.Default.Close, null, tint = TextPureWhite, modifier = Modifier.size(20.dp))
                }
                if (isListLoaded) {
                    Text("${listState.firstVisibleItemIndex + 1} / ${imageNames.size}", Modifier.align(Alignment.Center), color = TextPureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { isAutoScrollEnabled = !isAutoScrollEnabled }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow, 
                            contentDescription = null, 
                            tint = if (isAutoScrollEnabled) KakaoYellow else TextPureWhite, 
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { 
                        showEpisodeList = !showEpisodeList
                        if (showEpisodeList) showSettings = false
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = TextPureWhite, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { 
                        showSettings = !showSettings
                        if (showSettings) showEpisodeList = false
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Settings, null, tint = TextPureWhite, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        AnimatedVisibility(showControls, enter = fadeIn() + slideInVertically(initialOffsetY = { it }), exit = fadeOut() + slideOutVertically(targetOffsetY = { it }), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().background(BgBlack.copy(0.6f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (isListLoaded) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { viewModel.navigateChapter(false) }, enabled = uiState.currentChapterIndex > 0, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = if (uiState.currentChapterIndex > 0) Color.White else Color.Gray, modifier = Modifier.size(20.dp))
                        }
                        
                        Slider(
                            value = listState.firstVisibleItemIndex.toFloat(),
                            onValueChange = { scope.launch { listState.scrollToItem(it.toInt()) } },
                            valueRange = 0f..(imageNames.size - 1).coerceAtLeast(0).toFloat(),
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = SliderDefaults.colors(thumbColor = KakaoYellow, activeTrackColor = KakaoYellow, inactiveTrackColor = Color.White.copy(0.2f))
                        )
                        
                        val totalEpisodesCount = if (uiState.isSearchMode && uiState.searchResults.isNotEmpty()) uiState.searchResults.size else if (uiState.isSeriesView) uiState.seriesEpisodes.size else uiState.currentFiles.size
                        IconButton(onClick = { viewModel.navigateChapter(true) }, enabled = uiState.currentChapterIndex < totalEpisodesCount - 1, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (uiState.currentChapterIndex < totalEpisodesCount - 1) Color.White else Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(showControls && !isAutoScrollEnabled, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 80.dp, end = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) } },
                    containerColor = Color.Black.copy(0.5f),
                    contentColor = Color.White,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape
                ) { Icon(Icons.Default.KeyboardArrowUp, null) }
                
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceIn(imageNames.indices)) } },
                    containerColor = Color.Black.copy(0.5f),
                    contentColor = Color.White,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape
                ) { Icon(Icons.Default.KeyboardArrowDown, null) }
            }
        }

        if (showEpisodeList) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(top = 60.dp, end = 16.dp, bottom = 16.dp).width(250.dp).fillMaxHeight(0.7f),
                color = Color(0xFF1A1A1A),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Column {
                    Text("에피소드", Modifier.padding(16.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    HorizontalDivider(color = Color.White.copy(0.1f))
                    val episodes = if (uiState.isSearchMode && uiState.searchResults.isNotEmpty()) uiState.searchResults else if (uiState.isSeriesView) uiState.seriesEpisodes else uiState.currentFiles
                    LazyColumn {
                        itemsIndexed(episodes) { idx, file ->
                            val isSelected = uiState.currentChapterIndex == idx
                            Row(
                                Modifier.fillMaxWidth().clickable { 
                                    viewModel.onFileClick(file)
                                    showEpisodeList = false
                                }.background(if (isSelected) KakaoYellow.copy(alpha = 0.1f) else Color.Transparent).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = file.name, 
                                    color = if (isSelected) KakaoYellow else Color.White, 
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) Icon(Icons.Default.Check, null, tint = KakaoYellow, modifier = Modifier.size(14.dp))
                            }
                            HorizontalDivider(color = Color.White.copy(0.05f))
                        }
                    }
                }
            }
        }

        if (showSettings) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp).width(200.dp),
                color = Color(0xFF222222),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("밝기 조절", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.2f..1f, colors = SliderDefaults.colors(thumbColor = KakaoYellow, activeTrackColor = KakaoYellow))
                    Spacer(Modifier.height(16.dp))
                    Text("오토스크롤 속도", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Slider(value = autoScrollSpeed, onValueChange = { autoScrollSpeed = it }, valueRange = 0.5f..10f, colors = SliderDefaults.colors(thumbColor = KakaoYellow, activeTrackColor = KakaoYellow))
                    Spacer(Modifier.height(16.dp))
                    Text("이미지 필터", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilterChip(selected = isSepia, onClick = { isSepia = !isSepia; if (isSepia) isGray = false }, label = { Text("세피아", fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = KakaoYellow, selectedLabelColor = Color.Black))
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = isGray, onClick = { isGray = !isGray; if (isGray) isSepia = false }, label = { Text("흑백", fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = KakaoYellow, selectedLabelColor = Color.Black))
                    }
                }
            }
        }
    }
}

@Composable
fun WebtoonPage(zipPath: String, imageName: String, manager: ZipManager, cache: MutableMap<String, ImageBitmap>, isSepia: Boolean, isGray: Boolean) {
    var bitmap by remember(imageName) { mutableStateOf(cache[imageName]) }
    LaunchedEffect(imageName) {
        if (bitmap == null) {
            val bytes = manager.extractImage(zipPath, imageName)
            bytes?.toImageBitmap()?.let {
                bitmap = it; cache[imageName] = it
            }
        }
    }
    Box(Modifier.fillMaxWidth().wrapContentHeight(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            val colorFilter = when {
                isSepia -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
                isGray -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                else -> null
            }
            Image(bitmap!!, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, colorFilter = colorFilter)
        }
    }
}

@Composable
fun SearchScreen(state: ComicBrowserUiState, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, onClose: () -> Unit, onFileClick: (NasFile) -> Unit, onClearHistory: () -> Unit, viewModel: ComicViewModel) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(Modifier.fillMaxSize().background(BgBlack).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.searchQuery, 
                onValueChange = onQueryChange, 
                modifier = Modifier.weight(1f).focusRequester(focusRequester), 
                placeholder = { Text("제목 검색...", color = TextMuted) }, 
                singleLine = true, 
                colors = TextFieldDefaults.colors(focusedContainerColor = SurfaceGrey, unfocusedContainerColor = SurfaceGrey, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = KakaoYellow, focusedTextColor = TextPureWhite, unfocusedTextColor = TextPureWhite), 
                shape = RoundedCornerShape(8.dp), 
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) }, 
                trailingIcon = { 
                    Row {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, null, tint = TextMuted) }
                            IconButton(onClick = { onSearch(state.searchQuery); focusManager.clearFocus() }) { Icon(Icons.Default.Check, null, tint = KakaoYellow) }
                        }
                    }
                }, 
                keyboardActions = KeyboardActions(onSearch = { onSearch(state.searchQuery); focusManager.clearFocus() }), 
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            Spacer(Modifier.width(8.dp)); Text("취소", color = TextPureWhite, modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        if (!state.isSearchExecuted && state.searchQuery.isEmpty()) {
            if (state.recentSearches.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 검색어", color = TextPureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("전체 삭제", color = TextMuted, fontSize = 12.sp, modifier = Modifier.clickable { onClearHistory() })
                }
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(state.recentSearches) { history ->
                        Row(Modifier.fillMaxWidth().clickable { onSearch(history) }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(history, color = TextPureWhite, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextMuted, modifier = Modifier.size(16.dp).alpha(0.5f))
                        }
                        HorizontalDivider(color = SurfaceGrey, thickness = 0.5.dp)
                    }
                }
            }
        } else {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = KakaoYellow)
                }
            } else if (state.isSearchExecuted && state.searchResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("검색 결과가 없습니다.", color = TextMuted) }
            } else if (state.searchResults.isNotEmpty()) {
                Text("검색 결과 ${state.searchResults.size}건", color = TextPureWhite, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                FolderGridView(state.searchResults, emptyList(), false, false, onFileClick, {}, viewModel.posterRepository, rememberLazyGridState(), showCategoryBadge = true)
            }
        }
    }
}

@Composable
fun TopBar(uiState: ComicBrowserUiState, onHomeClick: () -> Unit, onSearchClick: () -> Unit, onModeToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Home, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = onHomeClick))
        Spacer(Modifier.width(16.dp))
        
        val titleText = if (uiState.isWebtoonMode) "NAS WEBTOON" else "NAS MANGA"
        Text(titleText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp), color = TextPureWhite)
        
        Spacer(Modifier.weight(1f))
        
        Surface(
            modifier = Modifier.clickable(onClick = onModeToggle),
            color = if (uiState.isWebtoonMode) Color(0xFFE91E63) else KakaoYellow,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Switch Mode",
                    tint = if (uiState.isWebtoonMode) Color.White else Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (uiState.isWebtoonMode) "웹툰 모드" else "만화 모드",
                    color = if (uiState.isWebtoonMode) Color.White else Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
        
        Spacer(Modifier.width(16.dp))
        Icon(Icons.Default.Search, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = onSearchClick))
    }
}

@Composable
fun CategoryTabs(uiState: ComicBrowserUiState, onTabClick: (String, Int) -> Unit) {
    ScrollableTabRow(selectedTabIndex = uiState.selectedCategoryIndex, containerColor = BgBlack, edgePadding = 20.dp, divider = {}, indicator = { positions ->
        if (uiState.selectedCategoryIndex < positions.size) Box(Modifier.tabIndicatorOffset(positions[uiState.selectedCategoryIndex]).height(2.dp).background(TextPureWhite))
    }) {
        uiState.categories.forEachIndexed { i, cat ->
            Tab(selected = uiState.selectedCategoryIndex == i, onClick = { onTabClick(cat.path, i) }, text = { Text(cat.name, fontSize = 14.sp) }, selectedContentColor = TextPureWhite, unselectedContentColor = TextMuted)
        }
    }
}

@Composable
fun IntroScreen() {
    Box(Modifier.fillMaxSize().background(BgBlack), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).border(2.dp, TextPureWhite, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Box(Modifier.size(30.dp).background(KakaoYellow, RoundedCornerShape(2.dp))) }
            Spacer(Modifier.height(24.dp)); Text("NAS VIEWER", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp), color = TextPureWhite)
            Spacer(Modifier.height(48.dp)); LinearProgressIndicator(Modifier.width(120.dp).height(2.dp), color = KakaoYellow, trackColor = Color.White.copy(alpha = 0.1f))
        }
    }
}
