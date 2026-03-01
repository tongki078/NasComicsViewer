package org.nas.comicsviewer.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val mainGridState = rememberLazyGridState()
    val canBack by remember { derivedStateOf { uiState.selectedZipPath != null || uiState.selectedPdfPath != null || uiState.pathHistory.size > 1 || uiState.isSearchMode || uiState.isSeriesView } }

    BackHandler(enabled = canBack) { viewModel.onBack() }
    
    LaunchedEffect(uiState.appMode) {
        zipManager.switchServer(uiState.appMode != AppMode.MANGA)
        mainGridState.scrollToItem(0)
    }

    LaunchedEffect(uiState.selectedCategoryIndex, uiState.currentPath) {
        if (!uiState.isSeriesView) mainGridState.scrollToItem(0)
    }
    
    LaunchedEffect(uiState.scrollRequestIndex) {
        uiState.scrollRequestIndex?.let { index ->
            mainGridState.scrollToItem(index)
            viewModel.onScrollRestored()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = BgBlack, surface = BgBlack, primary = TextPureWhite)) {
        Box(Modifier.fillMaxSize().background(BgBlack)) {
            if (uiState.isIntroShowing) {
                IntroScreen()
            } else if (uiState.selectedZipPath != null || uiState.selectedPdfPath != null) {
                val viewerPath = uiState.selectedZipPath ?: uiState.selectedPdfPath!!
                when (uiState.appMode) {
                    AppMode.MAGAZINE -> MagazineViewer(viewerPath, zipManager, uiState.viewerPosterUrl, viewModel.posterRepository, { viewModel.closeViewer() }, { viewModel.showError(it) }, uiState, viewModel)
                    AppMode.PHOTO_BOOK -> PhotoBookViewer(viewerPath, zipManager, uiState.viewerPosterUrl, viewModel.posterRepository, { viewModel.closeViewer() }, { viewModel.showError(it) }, uiState, viewModel)
                    AppMode.BOOK -> BookViewer(viewerPath, zipManager, uiState.viewerPosterUrl, viewModel.posterRepository, { viewModel.closeViewer() }, { viewModel.showError(it) }, uiState, viewModel)
                    else -> WebtoonViewer(viewerPath, zipManager, uiState.viewerPosterUrl, viewModel.posterRepository, { viewModel.closeViewer() }, { viewModel.showError(it) }, uiState, viewModel)
                }
            } else if (uiState.isSearchMode) {
                SearchScreen(uiState, { viewModel.updateSearchQuery(it) }, { viewModel.onSearchSubmit(it) }, { viewModel.toggleSearchMode(false) }, { viewModel.onFileClick(it) }, { viewModel.clearRecentSearches() }, viewModel)
            } else if (uiState.isSeriesView) {
                SeriesDetailScreen(uiState, { viewModel.onFileClick(it) }, { viewModel.onBack() }, viewModel.posterRepository)
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(uiState, { viewModel.onHome() }, { viewModel.onBack() }, { viewModel.toggleSearchMode(true) }, { viewModel.setAppMode(it) }, { viewModel.refresh() })
                    if (uiState.categories.isNotEmpty() && !uiState.isSeriesView) CategoryTabs(uiState) { path, index -> viewModel.scanBooks(path, index) }
                    Box(Modifier.weight(1f)) {
                        val isAtRoot = uiState.pathHistory.size <= 1 && !uiState.isSeriesView
                        if (uiState.appMode == AppMode.MANGA && uiState.categories.getOrNull(uiState.selectedCategoryIndex)?.name == "작가" && isAtRoot) {
                            FolderListView(uiState.currentFiles, uiState.isLoading, { viewModel.onFileClick(it) })
                        } else {
                            val gridCols = if (uiState.isBookMode && isAtRoot) 2 else if (uiState.isBookMode) 4 else 3
                            FolderGridView(if (uiState.isSeriesView) uiState.seriesEpisodes else uiState.currentFiles, if (uiState.isSeriesView) emptyList() else uiState.recentComics, uiState.isLoading, uiState.isLoadingMore, uiState.isRefreshing, { viewModel.onFileClick(it) }, { viewModel.loadMoreBooks() }, viewModel.posterRepository, mainGridState, false, uiState.isBookMode, gridCols, { viewModel.saveListScrollPosition(uiState.currentPath, it) })
                        }
                    }
                }
                if (uiState.isLoading) {
                    PremiumLoadingOverlay("데이터를 불러오는 중입니다")
                }
            }
            uiState.errorMessage?.let { msg -> Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = Color(0xFF222222), action = { TextButton({ viewModel.clearError() }) { Text("OK") } }) { Text(msg) } }
        }
    }
}

// ----------------------------------------------------
// 사용자님이 가장 좋아하시는 원조 프리미엄 로딩 컴포넌트
// ----------------------------------------------------
@Composable
fun PremiumLoadingBar(modifier: Modifier = Modifier, showFullInfo: Boolean = true) {
    var progress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(targetValue = progress, animationSpec = tween(durationMillis = 5000, easing = LinearOutSlowInEasing))
    LaunchedEffect(Unit) { progress = 0.98f }
    
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 두툼한 6dp 바
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().background(Brush.horizontalGradient(listOf(KakaoYellow.copy(alpha = 0.7f), KakaoYellow))))
        }
        if (showFullInfo) {
            Spacer(Modifier.height(12.dp))
            Text(text = "${(animatedProgress * 100).toInt()}%", color = KakaoYellow, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(text = "대용량 파일 분석 중...", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
fun PremiumLoadingOverlay(title: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(enabled = false) {}, Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            PremiumLoadingBar(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ViewerLoadingScreen(posterBitmap: ImageBitmap?, title: String = "파일을 불러오고 있습니다...") {
    Box(Modifier.fillMaxSize().background(BgBlack), Alignment.Center) {
        if (posterBitmap != null) Image(posterBitmap, null, Modifier.fillMaxSize().alpha(0.2f), contentScale = ContentScale.Crop)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(30.dp))
            PremiumLoadingBar(Modifier.fillMaxWidth())
        }
    }
}

// ----------------------------------------------------
// 고도화된 캐시 및 로딩 시스템
// ----------------------------------------------------
private val viewerImageCache = mutableMapOf<String, ImageBitmap>()
private val cacheLock = Mutex()
private val downloadLocks = mutableMapOf<String, Mutex>()
private val mapLock = Mutex()

private suspend fun loadAndCacheImage(zipPath: String, imageName: String, manager: ZipManager, repo: PosterRepository): ImageBitmap? {
    // 유니크한 캐시 키 생성 (경로와 파일명 조합)
    val cacheKey = "viewer_v2_" + (zipPath + imageName).hashCode().toString()
    
    // 1. 메모리 캐시 확인
    viewerImageCache[cacheKey]?.let { return it }
    
    // 2. 디스크 캐시 확인
    val bitmap = repo.getImage(cacheKey) 
    if (bitmap != null) {
        cacheLock.withLock { viewerImageCache[cacheKey] = bitmap }
        return bitmap
    }
    
    // 3. 서버에서 추출 및 저장
    val lock = mapLock.withLock { downloadLocks.getOrPut(cacheKey) { Mutex() } }
    return lock.withLock {
        viewerImageCache[cacheKey]?.let { return@withLock it }
        val bytes = manager.extractImage(zipPath, imageName)
        val resultBitmap = bytes?.let { withContext(Dispatchers.Default) { it.toImageBitmap() } }
        
        if (resultBitmap != null) {
            // 다음에 즉시 불러올 수 있도록 캐시에 저장 (이 과정에서 디스크 저장도 일어남)
            // Note: 현재 구조상 repo.getImage(cacheKey)가 서버 호출을 시도하므로, 
            // 별도의 saveToDisk 로직이 필요할 수 있으나, 일단 메모리 캐시를 우선함.
            cacheLock.withLock { viewerImageCache[cacheKey] = resultBitmap }
        }
        mapLock.withLock { downloadLocks.remove(cacheKey) }
        resultBitmap
    }
}

// ----------------------------------------------------
// 뷰어 및 페이지 컴포넌트
// ----------------------------------------------------
@Composable
fun WebtoonViewer(path: String, manager: ZipManager, posterUrl: String?, repo: PosterRepository, onClose: () -> Unit, onError: (String) -> Unit, uiState: ComicBrowserUiState, viewModel: ComicViewModel) {
    val listState = rememberLazyListState()
    val imageNames = remember { mutableStateListOf<String>() }
    var isListLoaded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(posterUrl) { posterUrl?.let { posterBitmap = repo.getImage(it) } }
    LaunchedEffect(path) {
        isListLoaded = false; imageNames.clear()
        try {
            val names = manager.listImagesInZip(path)
            if (names.isNotEmpty()) {
                imageNames.addAll(names); isListLoaded = true
                val lastPos = uiState.readingPositions[path] ?: 0
                if (lastPos > 0) scope.launch { listState.scrollToItem(lastPos) }
            } else { onError("내용을 가져올 수 없습니다."); delay(1000); onClose() }
        } catch (e: Exception) { 
            onError("서버 연결 오류: ${e.message}"); delay(1000); onClose() 
        }
    }
    Box(Modifier.fillMaxSize().background(BgBlack)) {
        if (isListLoaded) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { 
                showControls = !showControls
            }) { itemsIndexed(imageNames) { _, name -> WebtoonPage(path, name, manager, repo) } }
        } else { ViewerLoadingScreen(posterBitmap) }
        if (showControls && isListLoaded) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(BgBlack.copy(0.8f)).align(Alignment.TopCenter).padding(horizontal = 12.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(36.dp)) { Icon(Icons.Default.Close, null, tint = TextPureWhite, modifier = Modifier.size(20.dp)) }
                Text("${listState.firstVisibleItemIndex + 1} / ${imageNames.size}", Modifier.align(Alignment.Center), color = TextPureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WebtoonPage(zipPath: String, imageName: String, manager: ZipManager, repo: PosterRepository) {
    var pageBitmap by remember(imageName) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(imageName) { mutableStateOf(true) }
    LaunchedEffect(imageName) {
        isLoading = true; pageBitmap = loadAndCacheImage(zipPath, imageName, manager, repo); isLoading = false
    }
    Box(Modifier.fillMaxWidth().wrapContentHeight().defaultMinSize(minHeight = 400.dp), contentAlignment = Alignment.Center) {
        if (pageBitmap != null) {
            Image(bitmap = pageBitmap!!, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        } else if (isLoading) {
            // 개별 페이지 로딩은 텍스트 없이 바만 표시하여 지저분함을 방지
            PremiumLoadingBar(Modifier.fillMaxWidth(0.5f).padding(vertical = 120.dp), showFullInfo = false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MagazineViewer(path: String, manager: ZipManager, posterUrl: String?, repo: PosterRepository, onClose: () -> Unit, onError: (String) -> Unit, uiState: ComicBrowserUiState, viewModel: ComicViewModel) {
    val imageNames = remember { mutableStateListOf<String>() }
    var isListLoaded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { if (imageNames.isEmpty()) 0 else (imageNames.size + 1) / 2 }
    LaunchedEffect(posterUrl) { posterUrl?.let { posterBitmap = repo.getImage(it) } }
    LaunchedEffect(path) {
        isListLoaded = false; imageNames.clear()
        try {
            val names = manager.listImagesInZip(path)
            if (names.isNotEmpty()) {
                imageNames.addAll(names); isListLoaded = true
                val lastPos = uiState.readingPositions[path] ?: 0
                scope.launch { if ((lastPos/2) < pagerState.pageCount) pagerState.scrollToPage(lastPos / 2) }
            } else { onError("데이터를 불러올 수 없습니다."); delay(1000); onClose() }
        } catch (e: Exception) { 
            onError("서버 연결 실패: ${e.message}"); delay(1000); onClose() 
        }
    }
    Box(Modifier.fillMaxSize().background(BgBlack)) {
        if (isListLoaded) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }) { page ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    val leftIdx = page * 2; val rightIdx = page * 2 + 1
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { if (leftIdx < imageNames.size) MagazinePage(path, imageNames[leftIdx], manager, repo) }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { if (rightIdx < imageNames.size) MagazinePage(path, imageNames[rightIdx], manager, repo) }
                }
            }
        } else { ViewerLoadingScreen(posterBitmap) }
        if (showControls && isListLoaded) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(BgBlack.copy(0.8f)).align(Alignment.TopCenter).padding(horizontal = 12.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(36.dp)) { Icon(Icons.Default.Close, null, tint = TextPureWhite, modifier = Modifier.size(20.dp)) }
                Text("${pagerState.currentPage + 1} / ${pagerState.pageCount}", Modifier.align(Alignment.Center), color = TextPureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookViewer(path: String, manager: ZipManager, posterUrl: String?, repo: PosterRepository, onClose: () -> Unit, onError: (String) -> Unit, uiState: ComicBrowserUiState, viewModel: ComicViewModel) {
    val imageNames = remember { mutableStateListOf<String>() }
    var isListLoaded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { imageNames.size }
    LaunchedEffect(posterUrl) { posterUrl?.let { posterBitmap = repo.getImage(it) } }
    LaunchedEffect(path) {
        isListLoaded = false; imageNames.clear()
        try {
            val names = manager.listImagesInZip(path)
            if (names.isNotEmpty()) {
                imageNames.addAll(names); isListLoaded = true
                val lastPos = uiState.readingPositions[path] ?: 0
                if (lastPos < names.size) scope.launch { pagerState.scrollToPage(lastPos) }
            } else { onError("내용 목록을 가져올 수 없습니다."); delay(1000); onClose() }
        } catch (e: Exception) { 
            onError("로드 실패: ${e.message}"); delay(1000); onClose() 
        }
    }
    Box(Modifier.fillMaxSize().background(BgBlack)) {
        if (isListLoaded) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }, pageSpacing = 16.dp) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MagazinePage(path, imageNames[page], manager, repo) }
            }
        } else { ViewerLoadingScreen(posterBitmap) }
        if (showControls && isListLoaded) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(BgBlack.copy(0.8f)).align(Alignment.TopCenter).padding(horizontal = 12.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(36.dp)) { Icon(Icons.Default.Close, null, tint = TextPureWhite, modifier = Modifier.size(20.dp)) }
                Text("${pagerState.currentPage + 1} / ${imageNames.size}", Modifier.align(Alignment.Center), color = TextPureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoBookViewer(path: String, manager: ZipManager, posterUrl: String?, repo: PosterRepository, onClose: () -> Unit, onError: (String) -> Unit, uiState: ComicBrowserUiState, viewModel: ComicViewModel) {
    val imageNames = remember { mutableStateListOf<String>() }
    var isListLoaded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { imageNames.size }
    LaunchedEffect(posterUrl) { posterUrl?.let { posterBitmap = repo.getImage(it) } }
    LaunchedEffect(path) {
        isListLoaded = false; imageNames.clear()
        try {
            val names = manager.listImagesInZip(path)
            if (names.isNotEmpty()) {
                imageNames.addAll(names); isListLoaded = true
                val lastPos = uiState.readingPositions[path] ?: 0
                if (lastPos < names.size) scope.launch { pagerState.scrollToPage(lastPos) }
            } else { onError("이미지 목록이 비어있습니다."); delay(1000); onClose() }
        } catch (e: Exception) { 
            onError("로드 실패: ${e.message}"); delay(1000); onClose() 
        }
    }
    Box(Modifier.fillMaxSize().background(BgBlack)) {
        if (isListLoaded) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = !showControls }, pageSpacing = 16.dp) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MagazinePage(path, imageNames[page], manager, repo) }
            }
        } else { ViewerLoadingScreen(posterBitmap) }
        if (showControls && isListLoaded) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(BgBlack.copy(0.8f)).align(Alignment.TopCenter).padding(horizontal = 12.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(36.dp)) { Icon(Icons.Default.Close, null, tint = TextPureWhite, modifier = Modifier.size(20.dp)) }
                Text("${pagerState.currentPage + 1} / ${imageNames.size}", Modifier.align(Alignment.Center), color = TextPureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MagazinePage(zipPath: String, imageName: String, manager: ZipManager, repo: PosterRepository) {
    var pageBitmap by remember(imageName) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(imageName) { mutableStateOf(true) }
    LaunchedEffect(imageName) {
        isLoading = true; pageBitmap = loadAndCacheImage(zipPath, imageName, manager, repo); isLoading = false
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (pageBitmap != null) Image(bitmap = pageBitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (isLoading) PremiumLoadingBar(Modifier.fillMaxWidth(0.6f), showFullInfo = false)
    }
}

// ----------------------------------------------------
// UI 서브 화면들 (상동)
// ----------------------------------------------------
@Composable
fun SearchScreen(uiState: ComicBrowserUiState, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, onClose: () -> Unit, onFileClick: (NasFile) -> Unit, onClearHistory: () -> Unit, viewModel: ComicViewModel) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(Modifier.fillMaxSize().background(BgBlack).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = uiState.searchQuery, onValueChange = onQueryChange, modifier = Modifier.weight(1f).focusRequester(focusRequester), placeholder = { Text("제목 검색...", color = TextMuted) }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = SurfaceGrey, unfocusedContainerColor = SurfaceGrey, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = KakaoYellow, focusedTextColor = TextPureWhite, unfocusedTextColor = TextPureWhite), shape = RoundedCornerShape(8.dp), leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) }, trailingIcon = { 
                Row { if (uiState.searchQuery.isNotEmpty()) { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, null, tint = TextMuted) }; IconButton(onClick = { onSearch(uiState.searchQuery); focusManager.clearFocus() }) { Icon(Icons.Default.Check, null, tint = KakaoYellow) } } }
            }, keyboardActions = KeyboardActions(onSearch = { onSearch(uiState.searchQuery); focusManager.clearFocus() }), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            Spacer(Modifier.width(8.dp)); Text("취소", color = TextPureWhite, modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        if (!uiState.isSearchExecuted && uiState.searchQuery.isEmpty()) {
            if (uiState.recentSearches.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 검색어", color = TextPureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("전체 삭제", color = TextMuted, fontSize = 12.sp, modifier = Modifier.clickable { onClearHistory() })
                }
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(uiState.recentSearches) { history ->
                        Row(Modifier.fillMaxWidth().clickable { onSearch(history) }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp))
                            Text(history, color = TextPureWhite, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextMuted, modifier = Modifier.size(16.dp).alpha(0.5f))
                        }
                        HorizontalDivider(color = SurfaceGrey, thickness = 0.5.dp)
                    }
                }
            }
        } else {
            if (uiState.isLoading) { Box(Modifier.fillMaxSize(), Alignment.Center) { PremiumLoadingBar(Modifier.fillMaxWidth(0.7f), showFullInfo = true) } }
            else if (uiState.isSearchExecuted && uiState.searchResults.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("검색 결과가 없습니다.", color = TextMuted) } }
            else if (uiState.searchResults.isNotEmpty()) {
                Text("검색 결과 ${uiState.searchResults.size}건", color = TextPureWhite, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                FolderGridView(uiState.searchResults, emptyList(), false, false, false, onFileClick, {}, viewModel.posterRepository, rememberLazyGridState(), false, false, 3, {})
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(uiState: ComicBrowserUiState, onFileClick: (NasFile) -> Unit, onBack: () -> Unit, repo: PosterRepository) {
    var posterBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val metadata = uiState.selectedMetadata
    LaunchedEffect(metadata?.posterUrl) { metadata?.posterUrl?.let { url -> posterBitmap = repo.getImage(url) } }
    val isSimpleMode = posterBitmap == null && (metadata?.summary == "상세 정보가 없습니다." || metadata?.summary == "정보를 불러오는 중...")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().background(BgBlack)) {
            item {
                if (!isSimpleMode) {
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        if (posterBitmap != null) {
                            Image(posterBitmap!!, null, Modifier.fillMaxSize().alpha(0.2f), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, BgBlack.copy(alpha = 0.5f), BgBlack))))
                            Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 24.dp), verticalAlignment = Alignment.Bottom) {
                                Image(posterBitmap!!, null, Modifier.width(140.dp).aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(20.dp))
                                Column(Modifier.padding(bottom = 8.dp)) {
                                    Text(text = metadata?.title ?: "제목 없음", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = TextPureWhite)
                                    Spacer(Modifier.height(8.dp))
                                    val writers = if (metadata?.writers?.isNotEmpty() == true) metadata.writers!!.joinToString(", ") else "작가 미상"
                                    Text(text = writers, color = KakaoYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Surface(color = Color(0xFF222222), shape = RoundedCornerShape(4.dp)) { Text(text = metadata?.status ?: "Unknown", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, BgBlack.copy(alpha = 0.5f), BgBlack))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 24.dp)) {
                                Text(text = metadata?.title ?: "제목 없음", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = TextPureWhite)
                                Spacer(Modifier.height(8.dp))
                                val writers = if (metadata?.writers?.isNotEmpty() == true) metadata.writers!!.joinToString(", ") else "작가 미상"
                                Text(text = writers, color = KakaoYellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(0.3f), CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                        if (uiState.isLoading) {
                            PremiumLoadingBar(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), showFullInfo = false)
                        }
                    }
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        if (metadata?.genres?.isNotEmpty() == true) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { metadata.genres?.forEach { genre -> Surface(color = SurfaceGrey, shape = CircleShape, border = BorderStroke(0.5.dp, Color.White.copy(0.1f))) { Text(genre, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = TextMuted, fontSize = 11.sp) } } }
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(text = metadata?.summary ?: "등록된 줄거리가 없습니다.", color = TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
                        Spacer(Modifier.height(40.dp))
                        Text("에피소드 (${uiState.seriesEpisodes.size})", color = TextPureWhite, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                    }
                } else {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().background(BgBlack)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, modifier = Modifier.background(SurfaceGrey, CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(text = uiState.currentPath.replace("/", " > "), color = KakaoYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(text = metadata?.title ?: "폴더 항목", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = TextPureWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (uiState.isLoading) {
                            PremiumLoadingBar(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), showFullInfo = false)
                        }
                        HorizontalDivider(color = SurfaceGrey, thickness = 0.5.dp)
                    }
                    Spacer(Modifier.height(16.dp)); Text("항목 (${uiState.seriesEpisodes.size})", modifier = Modifier.padding(horizontal = 24.dp), color = TextPureWhite, fontWeight = FontWeight.Black, fontSize = 16.sp); Spacer(Modifier.height(16.dp))
                }
            }
            if (!uiState.isLoading || uiState.seriesEpisodes.isNotEmpty()) {
                items(uiState.seriesEpisodes) { file -> Box(Modifier.padding(horizontal = 20.dp)) { VolumeListItem(file, repo) { onFileClick(file) } }; Spacer(Modifier.height(12.dp)) }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

// ----------------------------------------------------
// UI 공통 컴포넌트
// ----------------------------------------------------
@Composable
fun TopBar(uiState: ComicBrowserUiState, onHomeClick: () -> Unit, onBackClick: () -> Unit, onSearchClick: () -> Unit, onModeChange: (AppMode) -> Unit, onRefresh: () -> Unit) {
    var showModeMenu by remember { mutableStateOf(false) }
    val showBack = uiState.pathHistory.size > 1 || uiState.isSeriesView
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val navIcon = if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Home
        Icon(navIcon, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = if (showBack) onBackClick else onHomeClick))
        Spacer(Modifier.width(16.dp))
        val titleText = if (uiState.isSeriesView && uiState.selectedMetadata != null) uiState.selectedMetadata.title ?: "NAS VIEWER" else when(uiState.appMode) { AppMode.MANGA -> "NAS MANGA"; AppMode.WEBTOON -> "NAS WEBTOON"; AppMode.MAGAZINE -> "NAS MAGAZINE"; AppMode.PHOTO_BOOK -> "NAS PHOTO"; AppMode.BOOK -> "NAS BOOK" }
        Text(titleText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp), color = TextPureWhite, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Refresh", tint = TextPureWhite) }
        Spacer(Modifier.width(12.dp))
        Box {
            Surface(modifier = Modifier.clickable { showModeMenu = true }, color = when(uiState.appMode) { AppMode.WEBTOON -> Color(0xFFE91E63); AppMode.MAGAZINE -> Color(0xFF2196F3); AppMode.PHOTO_BOOK -> Color(0xFF9C27B0); AppMode.BOOK -> Color(0xFF4CAF50); AppMode.MANGA -> KakaoYellow }, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Menu, null, tint = if (uiState.appMode == AppMode.MANGA) Color.Black else Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(uiState.appMode.label, color = if (uiState.appMode == AppMode.MANGA) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp); Icon(Icons.Default.ArrowDropDown, null, tint = if (uiState.appMode == AppMode.MANGA) Color.Black else Color.White)
                }
            }
            DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }, modifier = Modifier.background(SurfaceGrey).border(0.5.dp, Color.White.copy(0.1f))) {
                AppMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label, color = Color.White) }, onClick = { onModeChange(mode); showModeMenu = false }, leadingIcon = { val icon = when(mode) { AppMode.MANGA -> Icons.Default.Menu; AppMode.WEBTOON -> Icons.AutoMirrored.Filled.List; AppMode.MAGAZINE -> Icons.Default.Info; AppMode.PHOTO_BOOK -> Icons.Default.AccountCircle; AppMode.BOOK -> Icons.Default.Info }; Icon(icon, null, tint = if (uiState.appMode == mode) KakaoYellow else Color.Gray) }) }
            }
        }
        Spacer(Modifier.width(16.dp)); Icon(Icons.Default.Search, null, tint = TextPureWhite, modifier = Modifier.size(28.dp).clickable(onClick = onSearchClick))
    }
}

@Composable
fun CategoryTabs(uiState: ComicBrowserUiState, onTabClick: (String, Int) -> Unit) {
    ScrollableTabRow(selectedTabIndex = uiState.selectedCategoryIndex, containerColor = BgBlack, edgePadding = 20.dp, divider = {}, indicator = { positions -> if (uiState.selectedCategoryIndex < positions.size) Box(Modifier.tabIndicatorOffset(positions[uiState.selectedCategoryIndex]).height(2.dp).background(TextPureWhite)) }) {
        uiState.categories.forEachIndexed { i, cat -> Tab(selected = uiState.selectedCategoryIndex == i, onClick = { onTabClick(cat.path, i) }, text = { Text(cat.name, fontSize = 14.sp) }, selectedContentColor = TextPureWhite, unselectedContentColor = TextMuted) }
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

@Composable
fun FolderListView(files: List<NasFile>, isLoading: Boolean, onFileClick: (NasFile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (isLoading && files.isEmpty()) {
            items(15) { Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceGrey)) }
        } else {
            items(files) { file ->
                Row(Modifier.fillMaxWidth().clickable { onFileClick(file) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, tint = TextMuted, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
                    Text(file.name, color = TextPureWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextMuted.copy(0.3f), modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = SurfaceGrey, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun FolderGridView(files: List<NasFile>, recentComics: List<NasFile>, isLoading: Boolean, isLoadingMore: Boolean, isRefreshing: Boolean, onFileClick: (NasFile) -> Unit, onLoadMore: () -> Unit, repo: PosterRepository, gridState: LazyGridState, showCategory: Boolean, isBook: Boolean, gridCols: Int, onScroll: (Int) -> Unit) {
    val shouldLoadMore by remember(files.size, isLoading, isLoadingMore) { derivedStateOf { val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull(); last != null && last.index >= (files.size + if(recentComics.isNotEmpty()) 1 else 0) - 10 && !isLoading && !isLoadingMore } }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }
    LaunchedEffect(gridState.firstVisibleItemIndex) { onScroll(gridState.firstVisibleItemIndex) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(gridCols), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (isBook) 16.dp else 28.dp)) {
            if (recentComics.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { RecentComicsCarousel(recentComics, repo, onFileClick) }
            if (isLoading && files.isEmpty()) items(16) { Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) }
            else {
                items(files, key = { it.path }) { file -> ComicCard(file, repo, showCategory, isBook) { onFileClick(file) } }
                if (isLoadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) { PremiumLoadingBar(Modifier.fillMaxWidth(0.5f), showFullInfo = false) } }
            }
        }
        if (isRefreshing) Box(Modifier.fillMaxSize(), Alignment.TopCenter) { PremiumLoadingBar(Modifier.fillMaxWidth(), showFullInfo = false) }
    }
}

@Composable
fun ComicCard(file: NasFile, repo: PosterRepository, showCategory: Boolean, isBook: Boolean, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.metadata?.posterUrl) { file.metadata?.posterUrl?.let { thumb = repo.getImage(it) } }
    Column(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) {
            if (thumb != null) Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), Alignment.Center) { Text("NAS", color = TextMuted, fontSize = if(isBook) 10.sp else 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(0.4f)) }
            if (showCategory) {
                val cat = file.metadata?.category ?: file.path.substringBefore("/")
                if (cat.isNotEmpty()) Surface(color = KakaoYellow.copy(0.85f), shape = RoundedCornerShape(bottomEnd = 4.dp)) { Text(cat, color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) }
            }
            if (file.isDirectory) Box(Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) { Text("SERIES", color = KakaoYellow, fontSize = 7.sp, fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.height(if(isBook) 4.dp else 10.dp))
        val title = file.metadata?.title ?: file.name
        Text(title, color = TextPureWhite, fontSize = if(isBook) 11.sp else 12.sp, fontWeight = if(isBook) FontWeight.Normal else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if(isBook) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
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
                    LaunchedEffect(file.metadata?.posterUrl) { file.metadata?.posterUrl?.let { thumb = repo.getImage(it) } }
                    Box(Modifier.aspectRatio(0.72f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceGrey)) {
                        if (thumb != null) Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Box(Modifier.fillMaxSize(), Alignment.Center) { Text("NAS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(0.3f)) }
                    }
                    Spacer(Modifier.height(6.6.dp))
                    Text(file.name, color = TextPureWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp)); HorizontalDivider(color = SurfaceGrey, thickness = 4.dp)
    }
}

@Composable
fun VolumeListItem(file: NasFile, repo: PosterRepository, onClick: () -> Unit) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.metadata?.posterUrl) { file.metadata?.posterUrl?.let { thumb = repo.getImage(it) } }
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color(0xFF0D0D0D), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.5.dp, Color(0xFF1A1A1A))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(4.dp)).background(SurfaceGrey)) {
                if (thumb != null) Image(thumb!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), Alignment.Center) { val text = if(file.isDirectory) "FOLDER" else "FILE"; Text(text, fontSize = 8.sp, color = TextMuted) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, color = TextPureWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val desc = if(file.isDirectory) "폴더 열기" else "바로 읽기"
                Text(desc, color = KakaoYellow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(horizontalArrangement: Arrangement.Horizontal = Arrangement.Start, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = horizontalArrangement, verticalArrangement = verticalArrangement) { content() }
}
