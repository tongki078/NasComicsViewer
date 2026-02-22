package org.nas.comicsviewer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase

data class ComicBrowserUiState(
    val categories: List<NasFile> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentFiles: List<NasFile> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
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
    val isSearchExecuted: Boolean = false,
    val isWebtoonMode: Boolean = false
)

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
    private var currentPage = 1
    private var canLoadMore = true
    private val pageSize = 50

    private val listCache = mutableMapOf<String, List<NasFile>>()

    private val nameMap = mapOf(
        "ㅂㅇ" to "번역",
        "ㅇㅈ" to "연재",
        "ㅇㄱ" to "완결",
        "ㅈㄱ" to "작가",
    )

    init {
        loadRecentSearches()
        checkServerAndLoadCategories()
    }

    private fun checkServerAndLoadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isIntroShowing = true) }
            try {
                val categories = getCategoriesUseCase.execute("")
                _uiState.update { it.copy(categories = categories, isLoading = false, isIntroShowing = false) }
                if (categories.isNotEmpty()) {
                    scanBooks(categories[0].path, 0)
                } else if (_uiState.value.isWebtoonMode) {
                    // 웹툰 모드일 때 빈 경로(루트)를 호출하여 초성 카테고리 목록을 가져오도록 처리
                    scanBooks("", 0)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "서버 연결 실패: ${e.message}", isLoading = false) }
            }
        }
    }
    
    fun toggleServerMode() {
        val newMode = !_uiState.value.isWebtoonMode
        _uiState.update { it.copy(isWebtoonMode = newMode) }
        
        nasRepository.switchServer(newMode)
        posterRepository.switchServer(newMode)
        
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
                selectedZipPath = null
            )
        }
        
        checkServerAndLoadCategories()
    }

    fun scanBooks(path: String, index: Int? = null, isBack: Boolean = false) {
        if (isBack && listCache.containsKey(path)) {
            _uiState.update { state ->
                state.copy(
                    selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                    currentPath = path,
                    pathHistory = state.pathHistory.dropLast(1),
                    currentFiles = listCache[path] ?: emptyList(),
                    isSeriesView = false,
                    selectedMetadata = null,
                    isLoading = false
                )
            }
            return
        }

        scanJob?.cancel()
        currentPage = 1
        canLoadMore = true

        _uiState.update { state ->
            val newHistory = if (index != null) listOf(path) else if (isBack) state.pathHistory.dropLast(1) else state.pathHistory + path
            state.copy(
                selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                currentPath = path,
                pathHistory = newHistory,
                isLoading = true,
                currentFiles = emptyList(),
                totalItems = 0,
                isSeriesView = false,
                selectedMetadata = null,
                seriesEpisodes = emptyList(),
                selectedZipPath = null
            )
        }

        scanJob = viewModelScope.launch {
            try {
                if (_uiState.value.isWebtoonMode && path.isEmpty()) {
                    val result = nasRepository.listFiles("")
                    listCache[path] = result
                    _uiState.update { it.copy(currentFiles = result, totalItems = result.size, isLoading = false) }
                } else {
                    val result = nasRepository.scanComicFolders(path, currentPage, pageSize)
                    val processedFiles = processScanResult(result.items)
                    canLoadMore = processedFiles.size < result.total_items
                    listCache[path] = processedFiles
                    _uiState.update { it.copy(currentFiles = processedFiles, totalItems = result.total_items, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "목록 로드 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    fun loadMoreBooks() {
        if (uiState.value.isLoading || uiState.value.isLoadingMore || !canLoadMore) return
        if (uiState.value.isWebtoonMode && uiState.value.currentPath.isEmpty()) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                currentPage++
                val result = nasRepository.scanComicFolders(uiState.value.currentPath, currentPage, pageSize)
                val processedFiles = processScanResult(result.items)
                val newFiles = _uiState.value.currentFiles + processedFiles
                canLoadMore = newFiles.size < result.total_items
                listCache[uiState.value.currentPath] = newFiles
                _uiState.update { it.copy(currentFiles = newFiles, isLoadingMore = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "추가 로드 실패: ${e.message}", isLoadingMore = false) }
            }
        }
    }

    private fun processScanResult(files: List<NasFile>): List<NasFile> {
        return files.map { file ->
            val originalName = file.path.substringAfterLast('/')
            val finalName = nameMap[originalName.removeSuffix(".zip").removeSuffix(".cbz")] ?: cleanTitle(originalName)
            file.copy(name = finalName)
        }
    }

    fun onFileClick(file: NasFile) {
        if (!file.isDirectory) {
            val episodes = if (_uiState.value.isSearchMode && _uiState.value.searchResults.isNotEmpty()) _uiState.value.searchResults else if (_uiState.value.isSeriesView) _uiState.value.seriesEpisodes else _uiState.value.currentFiles
            val index = episodes.indexOfFirst { it.path == file.path }
            _uiState.update { it.copy(
                selectedZipPath = file.path, 
                viewerPosterUrl = file.metadata?.posterUrl ?: it.selectedMetadata?.posterUrl,
                currentChapterIndex = index
            ) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 검색 모드에서 폴더 클릭 시 검색 모드 해제 (원활한 화면 전환을 위해)
                val wasSearchMode = _uiState.value.isSearchMode
                if (wasSearchMode) {
                    _uiState.update { it.copy(isSearchMode = false) }
                }

                val metadata = nasRepository.getMetadata(file.path)
                val result = nasRepository.scanComicFolders(file.path, 1, 100)
                val processedFiles = processScanResult(result.items)
                val hasEpisodes = processedFiles.any { !it.isDirectory }
                
                if (hasEpisodes) {
                    _uiState.update { it.copy(
                        isSeriesView = true,
                        selectedMetadata = metadata ?: ComicMetadata(title = file.name, summary = "정보를 불러올 수 없습니다."),
                        seriesEpisodes = if (metadata?.chapters?.isNotEmpty() == true) metadata.chapters!! else processedFiles.filter { !it.isDirectory },
                        isLoading = false,
                        pathHistory = it.pathHistory + file.path
                    ) }
                } else {
                    scanBooks(file.path)
                }
            } catch (e: Exception) {
                scanBooks(file.path)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
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
        if (_uiState.value.isWebtoonMode) {
            scanBooks("", 0)
        } else if (uiState.value.categories.isNotEmpty()) {
            scanBooks(uiState.value.categories[0].path, 0)
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
                val processed = processScanResult(searchResult.items)
                
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
                        currentFiles = listCache[parentPath] ?: emptyList()
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
