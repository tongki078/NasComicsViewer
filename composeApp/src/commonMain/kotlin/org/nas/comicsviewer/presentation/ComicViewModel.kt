package org.nas.comicsviewer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase

enum class AppMode(val label: String) {
    MANGA("만화 모드"),
    WEBTOON("웹툰 모드"),
    MAGAZINE("잡지 모드"),
    PHOTO_BOOK("화보 모드"),
    BOOK("도서 모드")
}

data class ComicBrowserUiState(
    val categories: List<NasFile> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentFiles: List<NasFile> = emptyList(),
    val recentComics: List<NasFile> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val currentPath: String = "",
    val pathHistory: List<String> = emptyList(),
    val totalItems: Int = 0,
    val isSeriesView: Boolean = false,
    val isIntroShowing: Boolean = true,
    val selectedMetadata: ComicMetadata? = null,
    val seriesEpisodes: List<NasFile> = emptyList(),
    val selectedZipPath: String? = null,
    val viewerPosterUrl: String? = null,
    val currentChapterIndex: Int = -1,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<NasFile> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val readingPositions: Map<String, Int> = emptyMap(),
    val listScrollPositions: Map<String, Int> = emptyMap(),
    val scrollRequestIndex: Int? = null,
    val isSearchExecuted: Boolean = false,
    val appMode: AppMode = AppMode.MANGA
) {
    val isWebtoonMode: Boolean get() = appMode == AppMode.WEBTOON
    val isMagazineMode: Boolean get() = appMode == AppMode.MAGAZINE
    val isPhotoBookMode: Boolean get() = appMode == AppMode.PHOTO_BOOK
    val isBookMode: Boolean get() = appMode == AppMode.BOOK
}

class ComicViewModel(
    private val nasRepository: NasRepository,
    val posterRepository: PosterRepository,
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val database: ComicDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicBrowserUiState())
    val uiState: StateFlow<ComicBrowserUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var currentPage = 1
    private var canLoadMore = true
    private val pageSize = 50
    private var isInitialLoad = true

    private val listCache = mutableMapOf<String, List<NasFile>>()

    private val nameMap = mapOf(
        "ㅂㅇ" to "번역",
        "ㅇㅈ" to "연재",
        "ㅇㄱ" to "완결",
        "ㅈㄱ" to "작가",
    )

    init {
        loadRecentSearches()
        loadRecentComics()
        checkServerAndLoadCategories()
    }

    private fun checkServerAndLoadCategories() {
        viewModelScope.launch {
            // 처음 앱 켤때만 인트로 보여줌, 모드 변경 시에는 로딩바만 보여줌
            _uiState.update { it.copy(isLoading = true, isIntroShowing = isInitialLoad) }
            try {
                when (_uiState.value.appMode) {
                    AppMode.WEBTOON -> {
                        _uiState.update { it.copy(categories = emptyList()) }
                        scanBooks("")
                    }
                    AppMode.MAGAZINE -> {
                        _uiState.update { it.copy(categories = emptyList()) }
                        scanBooks("잡지")
                    }
                    AppMode.PHOTO_BOOK -> {
                        val categories = getCategoriesUseCase.execute("화보")
                        _uiState.update { it.copy(categories = categories) }
                        if (categories.isNotEmpty()) {
                            scanBooks(categories[0].path, 0)
                        } else {
                            scanBooks("화보")
                        }
                    }
                    AppMode.BOOK -> {
                        _uiState.update { it.copy(categories = emptyList()) }
                        scanBooks("책")
                    }
                    AppMode.MANGA -> {
                        val categories = getCategoriesUseCase.execute("")
                        _uiState.update { it.copy(categories = categories) }
                        if (categories.isNotEmpty()) {
                            scanBooks(categories[0].path, 0)
                        }
                    }
                }
                isInitialLoad = false
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "서버 연결 실패") }
            } finally {
                _uiState.update { it.copy(isLoading = false, isIntroShowing = false) }
            }
        }
    }
    
    fun setAppMode(newMode: AppMode) {
        if (_uiState.value.appMode == newMode) return
        
        _uiState.update { it.copy(appMode = newMode) }
        
        // Repository switch server logic
        nasRepository.switchServer(newMode != AppMode.MANGA)
        posterRepository.switchServer(newMode != AppMode.MANGA)
        
        listCache.clear()
        
        _uiState.update {
            it.copy(
                categories = emptyList(),
                currentFiles = emptyList(),
                pathHistory = emptyList(),
                currentPath = "",
                selectedCategoryIndex = 0,
                isSeriesView = false,
                selectedMetadata = null,
                seriesEpisodes = emptyList(),
                selectedZipPath = null,
                listScrollPositions = emptyMap()
            )
        }
        
        checkServerAndLoadCategories()
    }

    fun toggleServerMode() {
        val newMode = if (_uiState.value.appMode == AppMode.MANGA) AppMode.WEBTOON else AppMode.MANGA
        setAppMode(newMode)
    }

    fun refresh() {
        listCache.remove(_uiState.value.currentPath)
        _uiState.update { it.copy(isRefreshing = true) }
        scanBooks(_uiState.value.currentPath, _uiState.value.selectedCategoryIndex, isBack = false)
    }

    fun scanBooks(path: String, index: Int? = null, isBack: Boolean = false) {
        if (isBack && listCache.containsKey(path)) {
            _uiState.update { state ->
                state.copy(
                    selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                    currentPath = path,
                    pathHistory = if (state.pathHistory.isEmpty()) listOf(path) else state.pathHistory.dropLast(1),
                    currentFiles = listCache[path] ?: emptyList(),
                    isSeriesView = false,
                    selectedMetadata = null,
                    isLoading = false,
                    isRefreshing = false,
                    scrollRequestIndex = state.listScrollPositions[path] ?: 0
                )
            }
            return
        }

        scanJob?.cancel()
        currentPage = 1
        canLoadMore = true

        _uiState.update { state ->
            val newHistory = if (index != null) listOf(path) else if (isBack) state.pathHistory.dropLast(1) else if (state.pathHistory.lastOrNull() == path) state.pathHistory else state.pathHistory + path
            state.copy(
                selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                currentPath = path,
                pathHistory = newHistory,
                isLoading = !state.isRefreshing,
                currentFiles = if (state.isRefreshing) state.currentFiles else emptyList(),
                totalItems = 0,
                isSeriesView = false,
                selectedMetadata = null,
                seriesEpisodes = emptyList(),
                selectedZipPath = null,
                scrollRequestIndex = if (isBack) state.listScrollPositions[path] ?: 0 else 0
            )
        }

        scanJob = viewModelScope.launch {
            try {
                val result = nasRepository.scanComicFolders(path, currentPage, pageSize)
                val processedFiles = processScanResult(path, result.items)
                canLoadMore = processedFiles.size < result.total_items
                listCache[path] = processedFiles
                _uiState.update { it.copy(currentFiles = processedFiles, totalItems = result.total_items, isLoading = false, isRefreshing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "목록 로드 실패: ${e.message}", isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun loadMoreBooks() {
        if (uiState.value.isLoading || uiState.value.isLoadingMore || !canLoadMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                currentPage++
                val result = nasRepository.scanComicFolders(uiState.value.currentPath, currentPage, pageSize)
                val processedFiles = processScanResult(uiState.value.currentPath, result.items)
                val newFiles = (uiState.value.currentFiles + processedFiles).distinctBy { it.path }
                canLoadMore = newFiles.size < result.total_items
                listCache[uiState.value.currentPath] = newFiles
                _uiState.update { it.copy(currentFiles = newFiles, isLoadingMore = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "추가 로드 실패: ${e.message}", isLoadingMore = false) }
            }
        }
    }

    private fun processScanResult(path: String, files: List<NasFile>, skipGrouping: Boolean = false): List<NasFile> {
        val mapped = files.distinctBy { it.path }.map { file ->
            val originalName = file.path.substringAfterLast('/')
            val finalName = nameMap[originalName.removeSuffix(".zip").removeSuffix(".cbz")] ?: cleanTitle(originalName)
            file.copy(name = finalName)
        }

        if (!skipGrouping && _uiState.value.appMode == AppMode.PHOTO_BOOK && path.count { it == '/' } == 2) {
            val grouped = mapped.groupBy { it.name }
            return grouped.map { (groupName, filesInGroup) ->
                NasFile(
                    name = groupName,
                    isDirectory = true,
                    path = "$path/GROUP:$groupName",
                    metadata = filesInGroup.first().metadata
                )
            }
        }
        return mapped
    }

    fun saveListScrollPosition(path: String, index: Int) {
        _uiState.update { it.copy(listScrollPositions = it.listScrollPositions + (path to index)) }
    }

    fun onScrollRestored() {
        _uiState.update { it.copy(scrollRequestIndex = null) }
    }

    fun onFileClick(file: NasFile) {
        val slashCount = file.path.count { it == '/' }
        val shouldNavigate = file.isDirectory && !_uiState.value.isSeriesView && when (_uiState.value.appMode) {
            AppMode.BOOK -> slashCount < 3 
            AppMode.PHOTO_BOOK -> slashCount < 3 
            AppMode.MAGAZINE -> slashCount < 1 
            AppMode.WEBTOON -> slashCount < 1 
            AppMode.MANGA -> {
                if (file.path.startsWith("ㅈㄱ/") || file.path.startsWith("작가/")) slashCount < 2 else slashCount < 1
            }
        }

        if (shouldNavigate) {
            scanBooks(file.path)
            return
        }

        val isViewerItem = if (_uiState.value.isSeriesView) {
            when (_uiState.value.appMode) {
                AppMode.MANGA, AppMode.BOOK -> !file.isDirectory
                else -> true 
            }
        } else if (_uiState.value.isSearchMode && file.isDirectory) {
            when (_uiState.value.appMode) {
                AppMode.MAGAZINE -> slashCount >= 2
                AppMode.WEBTOON -> slashCount >= 3
                AppMode.PHOTO_BOOK -> slashCount >= 3
                else -> false
            }
        } else {
            !file.isDirectory
        }

        if (file.isDirectory && _uiState.value.isSeriesView && !isViewerItem) {
            scanBooks(file.path)
            return
        }

        if (isViewerItem) {
            viewModelScope.launch {
                posterRepository.addToRecent(file)
                loadRecentComics()
            }
            
            val episodes = if (_uiState.value.isSearchMode && _uiState.value.searchResults.isNotEmpty()) _uiState.value.searchResults else if (_uiState.value.isSeriesView) _uiState.value.seriesEpisodes else _uiState.value.currentFiles
            val index = episodes.indexOfFirst { it.path == file.path }
            _uiState.update { it.copy(
                selectedZipPath = file.path, 
                viewerPosterUrl = file.metadata?.posterUrl ?: it.selectedMetadata?.posterUrl,
                currentChapterIndex = index
            ) }
            return
        }
        
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _uiState.update { it.copy(
                isSeriesView = true,
                isLoading = true,
                selectedMetadata = file.metadata ?: ComicMetadata(title = file.name, summary = "정보를 불러오는 중..."),
                seriesEpisodes = emptyList()
            ) }

            try {
                if (_uiState.value.isSearchMode) {
                    _uiState.update { it.copy(isSearchMode = false) }
                }

                if (file.path.contains("/GROUP:")) {
                    val realPath = file.path.substringBeforeLast("/GROUP:")
                    val groupName = file.path.substringAfterLast("/GROUP:")
                    
                    val metadataDeferred = async { nasRepository.getMetadata(realPath) }
                    val resultDeferred = async { nasRepository.scanComicFolders(realPath, 1, 1000) }
                    
                    val metadata = metadataDeferred.await()
                    val result = resultDeferred.await()
                    
                    val allFilesInParent = processScanResult(realPath, result.items, skipGrouping = true)
                    val groupFiles = allFilesInParent.filter { it.name == groupName }
                    
                    _uiState.update { it.copy(
                        selectedMetadata = file.metadata ?: ComicMetadata(title = file.name, summary = metadata?.summary ?: "상세 정보가 없습니다."),
                        seriesEpisodes = groupFiles,
                        pathHistory = it.pathHistory + file.path,
                        isLoading = false
                    ) }
                } else {
                    val metadataDeferred = async { nasRepository.getMetadata(file.path) }
                    val resultDeferred = async { nasRepository.scanComicFolders(file.path, 1, 300) }
                    
                    val metadata = metadataDeferred.await()
                    val result = resultDeferred.await()
                    
                    val processedFiles = processScanResult(file.path, result.items)
                    
                    _uiState.update { it.copy(
                        selectedMetadata = metadata ?: ComicMetadata(title = file.name, summary = "상세 정보가 없습니다."),
                        seriesEpisodes = if (_uiState.value.appMode != AppMode.BOOK && metadata?.chapters?.isNotEmpty() == true) processScanResult(file.path, metadata.chapters!!) else processedFiles,
                        pathHistory = it.pathHistory + file.path,
                        isLoading = false
                    ) }
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    selectedMetadata = ComicMetadata(title = file.name, summary = "서버 통신 중 오류가 발생했습니다.")
                ) }
            }
        }
    }

    fun navigateChapter(next: Boolean) {
        val episodes = if (_uiState.value.isSearchMode && _uiState.value.searchResults.isNotEmpty()) _uiState.value.searchResults else if (_uiState.value.isSeriesView) _uiState.value.seriesEpisodes else _uiState.value.currentFiles
        val currentIndex = _uiState.value.currentChapterIndex
        val nextIndex = if (next) currentIndex + 1 else currentIndex - 1
        
        if (nextIndex in episodes.indices) {
            val nextFile = episodes[nextIndex]
            _uiState.update { it.copy(
                selectedZipPath = nextFile.path,
                viewerPosterUrl = nextFile.metadata?.posterUrl ?: it.selectedMetadata?.posterUrl,
                currentChapterIndex = nextIndex
            ) }
        }
    }

    fun saveReadingPosition(path: String, position: Int) {
        _uiState.update { it.copy(readingPositions = it.readingPositions + (path to position)) }
    }

    fun onHome() {
        when (_uiState.value.appMode) {
            AppMode.WEBTOON -> scanBooks("")
            AppMode.MAGAZINE -> scanBooks("잡지")
            AppMode.PHOTO_BOOK -> {
                if (_uiState.value.categories.isNotEmpty()) {
                    scanBooks(_uiState.value.categories[0].path, 0)
                } else {
                    scanBooks("화보")
                }
            }
            AppMode.BOOK -> scanBooks("책")
            AppMode.MANGA -> {
                if (uiState.value.categories.isNotEmpty()) {
                    scanBooks(uiState.value.categories[0].path, 0)
                }
            }
        }
    }

    fun toggleSearchMode(enabled: Boolean) {
        _uiState.update { it.copy(isSearchMode = enabled, searchQuery = "", searchResults = emptyList(), isSearchExecuted = false) }
        if (enabled) loadRecentSearches()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, isSearchExecuted = if (query.isEmpty()) false else it.isSearchExecuted) }
    }

    fun onSearchSubmit(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, searchQuery = query, isSearchExecuted = true) }
            try {
                posterRepository.insertRecentSearch(query)
                loadRecentSearches()
                
                val searchResult = nasRepository.searchComics(query, 1, 100)
                val processed = processScanResult("", searchResult.items, skipGrouping = true)
                
                _uiState.update { it.copy(searchResults = processed, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "검색 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    fun loadRecentSearches() {
        viewModelScope.launch {
            try {
                val history = posterRepository.getRecentSearches()
                _uiState.update { it.copy(recentSearches = history) }
            } catch (e: Exception) { }
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            try {
                posterRepository.clearRecentSearches()
                _uiState.update { it.copy(recentSearches = emptyList()) }
            } catch (e: Exception) { }
        }
    }

    fun loadRecentComics() {
        viewModelScope.launch {
            try {
                val recent = posterRepository.getRecentComics()
                _uiState.update { it.copy(recentComics = recent) }
            } catch (e: Exception) { }
        }
    }

    fun closeViewer() { 
        _uiState.update { it.copy(selectedZipPath = null, viewerPosterUrl = null, currentChapterIndex = -1) } 
    }

    fun onBack() {
        if (_uiState.value.isSearchMode) {
            toggleSearchMode(false)
            return
        }
        if (_uiState.value.selectedZipPath != null) {
            closeViewer()
            return
        }
        
        val history = _uiState.value.pathHistory
        if (history.size > 1) {
            val currentIsSeries = _uiState.value.isSeriesView
            if (currentIsSeries) {
                val parentPath = history[history.size - 2]
                if (listCache.containsKey(parentPath)) {
                    _uiState.update { it.copy(
                        isSeriesView = false,
                        selectedMetadata = null,
                        currentPath = parentPath,
                        pathHistory = history.dropLast(1),
                        currentFiles = listCache[parentPath] ?: emptyList(),
                        scrollRequestIndex = it.listScrollPositions[parentPath] ?: 0
                    ) }
                    return
                }
            }
            val prevPath = history[history.size - 2]
            scanBooks(prevPath, isBack = true)
        } else {
            onHome()
        }
    }

    fun showError(message: String) { _uiState.update { it.copy(errorMessage = message, isLoading = false) } }
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}
