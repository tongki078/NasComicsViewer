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
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<NasFile> = emptyList(),
    val recentSearches: List<String> = emptyList()
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
    private var currentPage = 1
    private var canLoadMore = true
    private val pageSize = 50

    // 뒤로가기 시 스크롤 유지를 위해 이전 목록들을 저장함
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
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "서버 연결 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    fun scanBooks(path: String, index: Int? = null, isBack: Boolean = false) {
        if (isBack && listCache.containsKey(path)) {
            // 뒤로가기인데 이미 캐시된 데이터가 있다면 스캔 없이 복구
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
                val result = nasRepository.scanComicFolders(path, currentPage, pageSize)
                val processedFiles = processScanResult(result.items)
                canLoadMore = processedFiles.size < result.total_items
                
                listCache[path] = processedFiles // 캐시에 저장
                
                _uiState.update { it.copy(currentFiles = processedFiles, totalItems = result.total_items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "목록 로드 실패: ${e.message}", isLoading = false) }
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
                val processedFiles = processScanResult(result.items)
                val newFiles = _uiState.value.currentFiles + processedFiles
                canLoadMore = newFiles.size < result.total_items
                
                listCache[uiState.value.currentPath] = newFiles // 캐시 업데이트
                
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
            _uiState.update { it.copy(
                selectedZipPath = file.path, 
                viewerPosterUrl = file.metadata?.posterUrl ?: it.selectedMetadata?.posterUrl
            ) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val metadata = nasRepository.getMetadata(file.path)
                if (metadata != null && (metadata.chapters?.isNotEmpty() == true || metadata.summary != "줄거리 정보가 없습니다.")) {
                    _uiState.update { it.copy(
                        isSeriesView = true,
                        selectedMetadata = metadata,
                        seriesEpisodes = metadata.chapters ?: emptyList(),
                        isLoading = false,
                        // 시리즈 뷰 진입 시 히스토리에는 추가하지만 목록은 유지함 (뒤로가기 시 즉시 복구 위해)
                        pathHistory = it.pathHistory + file.path
                    ) }
                } else {
                    scanBooks(file.path)
                }
            } catch (e: Exception) {
                scanBooks(file.path)
            }
        }
    }

    fun onHome() {
        if (uiState.value.categories.isNotEmpty()) scanBooks(uiState.value.categories[0].path, 0)
    }

    fun toggleSearchMode(enabled: Boolean) {
        _uiState.update { it.copy(isSearchMode = enabled, searchQuery = "", searchResults = emptyList()) }
        if (enabled) loadRecentSearches()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onSearchSubmit(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                posterRepository.insertRecentSearch(query)
                loadRecentSearches()
            } catch (e: Exception) { }
        }
    }

    private fun loadRecentSearches() {
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
                loadRecentSearches()
            } catch (e: Exception) { }
        }
    }

    fun closeViewer() { 
        _uiState.update { it.copy(selectedZipPath = null, viewerPosterUrl = null) } 
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
                // 시리즈 뷰에서 뒤로갈 때는 목록 캐시를 확인하여 즉시 복구
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
