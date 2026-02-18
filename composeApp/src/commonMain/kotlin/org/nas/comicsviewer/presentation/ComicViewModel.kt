package org.nas.comicsviewer.presentation

import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.domain.usecase.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ComicBrowserUiState(
    val categories: List<NasFile> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentFiles: List<NasFile> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
    val currentPath: String? = null,
    val pathHistory: List<String> = emptyList(),
    val totalFoundCount: Int = 0,
    val isSeriesView: Boolean = false,
    val isIntroShowing: Boolean = true,
    val selectedMetadata: ComicMetadata? = null,
    val seriesEpisodes: List<NasFile> = emptyList(),
    val selectedZipPath: String? = null,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<NasFile> = emptyList(),
    val recentSearches: List<String> = emptyList()
)

class ComicViewModel(
    private val nasRepository: NasRepository,
    val posterRepository: PosterRepository,
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val scanComicFoldersUseCase: ScanComicFoldersUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicBrowserUiState())
    val uiState: StateFlow<ComicBrowserUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var prefetchJob: Job? = null
    private val allScannedFiles = mutableListOf<NasFile>()
    private val PAGE_SIZE = 24

    init {
        loadRecentSearches()
        checkServerAndLoadCategories()
    }

    private fun checkServerAndLoadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isIntroShowing = true) }
            try {
                val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                val response = client.get("http://192.168.0.2:5555/debug").body<JsonObject>()
                client.close()

                val pathExists = response["path_exists"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!pathExists) {
                    throw Exception("서버 오류: 설정된 ROOT 디렉토리를 찾을 수 없습니다. 경로를 확인하세요.")
                }
                val canList = response["list_contents_success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!canList) {
                    val error = response["list_error"]?.jsonPrimitive?.toString() ?: "알 수 없는 오류"
                    throw Exception("서버 오류: ROOT 디렉토리 접근 권한이 없습니다. (원인: $error)")
                }

                loadCategories("")

            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "초기화 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    private fun loadCategories(rootUrl: String) {
        viewModelScope.launch {
            try {
                val categories = getCategoriesUseCase.execute(rootUrl)
                _uiState.update { it.copy(categories = categories) }
                if (categories.isNotEmpty()) {
                    scanCategory(categories[0].path, 0)
                } else {
                    _uiState.update { it.copy(errorMessage = "오류: 카테고리를 찾을 수 없습니다. 서버의 ROOT 폴더가 비어있거나 잘못 설정되었을 수 있습니다.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "카테고리 로드 실패: ${e.message}") }
            } finally {
                delay(500)
                _uiState.update { it.copy(isLoading = false, isIntroShowing = false) }
            }
        }
    }

    fun onHome() {
        val categories = _uiState.value.categories
        if (categories.isNotEmpty()) scanCategory(categories[0].path, 0)
    }

    fun scanCategory(path: String, index: Int? = null, isBack: Boolean = false) {
        scanJob?.cancel()
        prefetchJob?.cancel()
        allScannedFiles.clear()
        
        scanJob = viewModelScope.launch {
            _uiState.update { state -> 
                val newHistory = when {
                    index != null -> listOf(path)
                    isBack -> state.pathHistory
                    else -> if (state.pathHistory.lastOrNull() == path) state.pathHistory else state.pathHistory + path
                }
                state.copy(
                    selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                    currentPath = path,
                    pathHistory = newHistory,
                    isScanning = true, 
                    currentFiles = emptyList(),
                    isSeriesView = false,
                    selectedMetadata = null,
                    seriesEpisodes = emptyList(),
                    selectedZipPath = null,
                    isSearchMode = false
                )
            }
            scanComicFoldersUseCase.execute(path).collect { file ->
                allScannedFiles.add(file)
                allScannedFiles.sortBy { it.name }
                if (_uiState.value.currentFiles.size < PAGE_SIZE) {
                    _uiState.update { it.copy(currentFiles = allScannedFiles.take(PAGE_SIZE), totalFoundCount = allScannedFiles.size) }
                }
            }
            _uiState.update { it.copy(isScanning = false) }
            startPosterPrefetch()
        }
    }

    private fun startPosterPrefetch() {
        prefetchJob = viewModelScope.launch {
            allScannedFiles.forEach { file ->
                posterRepository.getMetadata(file.path)
                delay(500)
            }
        }
    }

    fun loadNextPage() {
        if (_uiState.value.isSeriesView || _uiState.value.isSearchMode) return
        val currentCount = _uiState.value.currentFiles.size
        _uiState.update { it.copy(currentFiles = allScannedFiles.take(currentCount + PAGE_SIZE)) }
    }

    fun onFileClick(file: NasFile) {
        if (!file.isDirectory) {
            _uiState.update { it.copy(selectedZipPath = file.path) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val metadata = posterRepository.getMetadata(file.path)
                val targetPath = if (file.path.endsWith("/")) file.path else "${file.path}/"
                val filesInFolder = nasRepository.listFiles(targetPath)
                val volumes = filesInFolder.filter { 
                    it.name.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".cbz") } 
                }.sortedBy { it.name }
                
                if (volumes.isNotEmpty()) {
                    _uiState.update { it.copy(
                        currentPath = file.path,
                        pathHistory = it.pathHistory + file.path,
                        seriesEpisodes = volumes,
                        isLoading = false,
                        isSeriesView = true,
                        selectedMetadata = metadata,
                        selectedZipPath = null,
                        isSearchMode = false
                    ) }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    scanCategory(file.path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "에피소드 로드 실패: ${e.message}") }
            }
        }
    }

    fun toggleSearchMode(enabled: Boolean) {
        _uiState.update { it.copy(isSearchMode = enabled, searchQuery = "", searchResults = emptyList()) }
        if (enabled) loadRecentSearches()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            performSearch(query)
        } else {
            _uiState.update { it.copy(searchResults = emptyList()) }
        }
    }

    private fun performSearch(query: String) {
        val results = allScannedFiles.filter { it.name.contains(query, ignoreCase = true) }
        _uiState.update { it.copy(searchResults = results) }
    }

    fun onSearchSubmit(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                posterRepository.insertRecentSearch(query)
                loadRecentSearches()
            } catch (e: Exception) { e.printStackTrace() }
        }
        performSearch(query)
    }

    private fun loadRecentSearches() {
        viewModelScope.launch {
            try {
                val history = posterRepository.getRecentSearches()
                _uiState.update { it.copy(recentSearches = history) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            try {
                posterRepository.clearRecentSearches()
                loadRecentSearches()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun closeViewer() {
        _uiState.update { it.copy(selectedZipPath = null) }
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
            val newHistory = history.dropLast(1)
            _uiState.update { it.copy(pathHistory = newHistory) }
            scanCategory(newHistory.last(), isBack = true)
        } else onHome()
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}
