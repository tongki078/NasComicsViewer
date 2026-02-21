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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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

    BackHandler(enabled = uiState.selectedZipPath != null || uiState.pathHistory.size > 1 || uiState.isSearchMode || uiState.isSeriesView) {
        viewModel.onBack()
    }

    MaterialTheme(colorScheme = darkColorScheme(background = BgBlack, surface = BgBlack, primary = TextPureWhite)) {
        Box(Modifier.fillMaxSize().background(BgBlack)) {
            if (uiState.isIntroShowing) {
                IntroScreen()
            } else if (uiState.selectedZipPath != null) {
                 WebtoonViewer(
                     path = uiState.selectedZipPath!!, 
                     manager = zipManager, 
                     posterUrl = uiState.selectedMetadata?.posterUrl,
                     repo = viewModel.posterRepository,
                     onClose = { viewModel.closeViewer() }, 
                     onError = { viewModel.showError(it) }
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
                    TopBar(onHomeClick = { viewModel.onHome() }, onSearchClick = { viewModel.toggleSearchMode(true) })
                    if (uiState.categories.isNotEmpty()) {
                        CategoryTabs(uiState) { path, index -> viewModel.scanBooks(path, index) }
                    }
                    Box(Modifier.weight(1f)) {
                        FolderGridView(
                            files = uiState.currentFiles,
                            isLoadingMore = uiState.isLoadingMore,
                            onFileClick = { viewModel.onFileClick(it) },
                            onLoadMore = { viewModel.loadMoreBooks() },
                            posterRepository = viewModel.posterRepository
                        )
                    }
                }
            }
            
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KakaoYellow, strokeWidth = 2.dp)
                }
            }

            uiState.errorMessage?.let { msg ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = Color(0xFF222222), action = { TextButton({ viewModel.clearError() }) { Text("OK") } }) { Text(msg) }
            }
        }
    }
}


@Composable
fun FolderGridView(
    files: List<NasFile>,
    isLoadingMore: Boolean,
    onFileClick: (NasFile) -> Unit,
    onLoadMore: () -> Unit,
    posterRepository: PosterRepository
) {
    val gridState = rememberLazyGridState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= files.size - 10
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(files, key = { it.path }) { file ->
            ComicCard(file, posterRepository) { onFileClick(file) }
        }

        if (isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = KakaoYellow,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ComicCard(file: NasFile, repo: PosterRepository, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.metadata?.posterUrl) {
        file.metadata?.posterUrl?.let {
            thumb = repo.getImage(it)
        }
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
        metadata?.posterUrl?.let { url ->
            posterBitmap = repo.getImage(url)
        }
    }

    Column(Modifier.fillMaxSize().background(BgBlack).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            if (posterBitmap != null) {
                Image(posterBitmap!!, null, Modifier.fillMaxSize().blur(30.dp).alpha(0.3f), contentScale = ContentScale.Crop)
                Image(posterBitmap!!, null, Modifier.align(Alignment.BottomCenter).width(200.dp).height(280.dp).padding(bottom = 20.dp).clip(RoundedCornerShape(8.dp)).border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, BgBlack.copy(alpha = 0.5f), BgBlack))))
            
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                Text("〈", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(text = metadata?.title ?: "제목 없음", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = TextPureWhite)
            Spacer(Modifier.height(12.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                val writers = if (metadata?.writers?.isNotEmpty() == true) metadata.writers.joinToString(", ") else "작가 미상"
                Text(text = writers, color = KakaoYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Surface(color = Color(0xFF222222), shape = RoundedCornerShape(4.dp)) {
                    Text(text = metadata?.status ?: "Unknown", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            if (metadata?.genres?.isNotEmpty() == true) {
                FlowRow(mainAxisSpacing = 8.dp, crossAxisSpacing = 8.dp) {
                    metadata.genres.forEach { genre ->
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

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), 
        color = Color(0xFF0D0D0D), 
        shape = RoundedCornerShape(8.dp), 
        border = BorderStroke(0.5.dp, Color(0xFF1A1A1A))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) {
                if (thumb != null) {
                    Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("BOOK", fontSize = 8.sp, color = TextMuted) }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, color = TextPureWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("바로 읽기", color = KakaoYellow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.Search, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun FlowRow(
    mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val placeholders = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rows = mutableListOf<List<androidx.compose.ui.layout.Placeable>>()
        var currentRow = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var currentRowWidth = 0
        
        placeholders.forEach { placeable ->
            if (currentRowWidth + placeable.width > constraints.maxWidth && currentRow.isNotEmpty()) {
                rows.add(currentRow)
                currentRow = mutableListOf()
                currentRowWidth = 0
            }
            currentRow.add(placeable)
            currentRowWidth += placeable.width + mainAxisSpacing.roundToPx()
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)
        
        val height = if (rows.isEmpty()) 0 else rows.sumOf { row -> row.maxOf { it.height } } + (rows.size - 1).coerceAtLeast(0) * crossAxisSpacing.roundToPx()
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                var x = 0
                val rowHeight = row.maxOf { it.height }
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + mainAxisSpacing.roundToPx()
                }
                y += rowHeight + crossAxisSpacing.roundToPx()
            }
        }
    }
}

@Composable
fun SearchScreen(
    state: ComicBrowserUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onFileClick: (NasFile) -> Unit,
    onClearHistory: () -> Unit,
    viewModel: ComicViewModel
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().background(BgBlack).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("제목 검색...", color = TextMuted) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceGrey,
                    unfocusedContainerColor = SurfaceGrey,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = KakaoYellow,
                    focusedTextColor = TextPureWhite,
                    unfocusedTextColor = TextPureWhite
                ),
                shape = RoundedCornerShape(8.dp),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.clickable { onQueryChange("") })
                    }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSearch(state.searchQuery) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
            )
            Spacer(Modifier.width(8.dp))
            Text("취소", color = TextPureWhite, modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }

        if (state.searchQuery.isEmpty()) {
            if (state.recentSearches.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("최근 검색어", color = TextPureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("전체 삭제", color = TextMuted, fontSize = 12.sp, modifier = Modifier.clickable { onClearHistory() })
                }
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(state.recentSearches) { history ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSearch(history) }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(history, color = TextPureWhite, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("↖", color = TextMuted, fontSize = 12.sp)
                        }
                        Divider(color = SurfaceGrey, thickness = 0.5.dp)
                    }
                }
            }
        } else {
            if (state.searchResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("검색 결과가 없습니다.", color = TextMuted)
                }
            } else {
                Text(
                    "검색 결과 ${state.searchResults.size}건", 
                    color = TextPureWhite, 
                    fontWeight = FontWeight.Bold, 
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                FolderGridView(state.searchResults, false, onFileClick, {}, viewModel.posterRepository)
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
    onError: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val loadedImages = remember { mutableStateListOf<Pair<String, ImageBitmap>>() }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isFinished by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(posterUrl) {
        posterUrl?.let { url ->
            posterBitmap = repo.getImage(url)
        }
    }

    LaunchedEffect(path) {
        manager.streamAllImages(path, { progress = it }) { name, bytes ->
            val bitmap = bytes.toImageBitmap()
            if (bitmap != null) loadedImages.add(name to bitmap)
        }
        isFinished = true
        if (loadedImages.isEmpty()) onError("이미지를 불러올 수 없습니다.")
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }) {
        if (loadedImages.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                if (posterBitmap != null) {
                    Image(posterBitmap!!, null, Modifier.fillMaxSize().blur(40.dp).alpha(0.4f), contentScale = ContentScale.Crop)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (progress > 0) "Reading... ${(progress * 100).toInt()}%" else "Connecting...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(loadedImages) { (_, bitmap) -> Image(bitmap, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            if (!isFinished) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = KakaoYellow,
                    trackColor = Color.Transparent
                )
            }
            AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().background(BgBlack.copy(0.8f)).statusBarsPadding().padding(16.dp)) {
                    Text("✕", color = TextPureWhite, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterStart).clickable { onClose() }.padding(8.dp))
                    val statusText = if (isFinished) "${listState.firstVisibleItemIndex + 1} / ${loadedImages.size}" else "Loading..."
                    Text(statusText, Modifier.align(Alignment.Center), color = TextPureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TopBar(onHomeClick: () -> Unit, onSearchClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Home, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = onHomeClick))
        Spacer(Modifier.width(16.dp))
        Text("NAS WEBTOON", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp), color = TextPureWhite)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.Search, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = onSearchClick))
    }
}

@Composable
fun CategoryTabs(uiState: ComicBrowserUiState, onTabClick: (String, Int) -> Unit) {
    ScrollableTabRow(selectedTabIndex = uiState.selectedCategoryIndex, containerColor = BgBlack, edgePadding = 20.dp, divider = {}, indicator = { positions ->
        if (uiState.selectedCategoryIndex < positions.size) {
            Box(Modifier.tabIndicatorOffset(positions[uiState.selectedCategoryIndex]).height(2.dp).background(TextPureWhite))
        }
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
            Box(Modifier.size(80.dp).border(2.dp, TextPureWhite, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).background(KakaoYellow, RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.height(24.dp))
            Text("NAS WEBTOON", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp), color = TextPureWhite)
            Spacer(Modifier.height(48.dp))
            LinearProgressIndicator(Modifier.width(120.dp).height(2.dp), color = KakaoYellow, trackColor = Color.White.copy(alpha = 0.1f))
        }
    }
}
