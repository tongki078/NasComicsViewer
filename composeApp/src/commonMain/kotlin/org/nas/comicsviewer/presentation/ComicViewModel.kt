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
    val selectedMetadata: ComicMetadata? = null, // 시리즈 전체 정보 보존용
    val seriesEpisodes: List<NasFile> = emptyList(),
    val selectedZipPath: String? = null, // 현재 뷰어에서 보고 있는 파일
    val viewerPosterUrl: String? = null, // 뷰어용 배경 이미지
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
                _uiState.update { it.copy(currentFiles = processedFiles, totalItems = result.total_items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "목록 로드 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    fun loadMoreBooks() {
        if (uiState.value.isLoading || !canLoadMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                currentPage++
                val result = nasRepository.scanComicFolders(uiState.value.currentPath, currentPage, pageSize)
                val processedFiles = processScanResult(result.items)
                val newFiles = _uiState.value.currentFiles + processedFiles
                canLoadMore = newFiles.size < result.total_items
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
            // 단일 파일 클릭 시: 시리즈 메타데이터는 유지하고 선택된 경로만 업데이트
            _uiState.update { it.copy(
                selectedZipPath = file.path, 
                viewerPosterUrl = file.metadata?.posterUrl ?: it.selectedMetadata?.posterUrl
            ) }
            return
        }
        
        // 폴더(시리즈) 클릭 시
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
                        currentPath = file.path,
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
        if (_uiState.value.isSeriesView) {
            // 시리즈 뷰에서 나갈 때 상태 초기화
            val history = _uiState.value.pathHistory
            if (history.size > 1) {
                val newHistory = history.dropLast(1)
                scanBooks(newHistory.last(), isBack = true)
            } else {
                onHome()
            }
            return
        }
        val history = _uiState.value.pathHistory
        if (history.size > 1) {
            val newHistory = history.dropLast(1)
            scanBooks(newHistory.last(), isBack = true)
        } else {
            onHome()
        }
    }

    fun showError(message: String) { _uiState.update { it.copy(errorMessage = message, isLoading = false) } }
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}
