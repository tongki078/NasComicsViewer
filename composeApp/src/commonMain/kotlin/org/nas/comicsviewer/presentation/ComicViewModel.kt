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
import io.ktor.client.plugins.HttpTimeout
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
    private val scanComicFoldersUseCase: ScanComicFoldersUseCase,
    private val setCredentialsUseCase: SetCredentialsUseCase,
    private val database: ComicDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicBrowserUiState())
    val uiState: StateFlow<ComicBrowserUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var currentScanId = 0
    private val allScannedFiles = mutableListOf<NasFile>()
    private val PAGE_SIZE = 48

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 120000; connectTimeoutMillis = 20000 }
    }

    init {
        loadRecentSearches()
        checkServerAndLoadCategories()
    }

    private fun checkServerAndLoadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isIntroShowing = true) }
            try {
                val response = client.get("http://192.168.0.2:5555/debug").body<JsonObject>()
                val pathExists = response["exists"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!pathExists) throw Exception("ROOT 경로를 찾을 수 없습니다.")
                loadCategories("")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "서버 연결 실패: ${e.message}", isLoading = false) }
            }
        }
    }

    private fun loadCategories(rootUrl: String) {
        viewModelScope.launch {
            try {
                val categories = getCategoriesUseCase.execute(rootUrl)
                _uiState.update { it.copy(categories = categories) }
                if (categories.isNotEmpty()) scanCategory(categories[0].path, 0)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "카테고리 로드 실패") }
            } finally {
                delay(500)
                _uiState.update { it.copy(isLoading = false, isIntroShowing = false) }
            }
        }
    }

    fun onHome() {
        if (uiState.value.categories.isNotEmpty()) scanCategory(uiState.value.categories[0].path, 0)
    }

    fun scanCategory(path: String, index: Int? = null, isBack: Boolean = false) {
        scanJob?.cancel()
        val scanId = ++currentScanId
        allScannedFiles.clear()

        _uiState.update { state ->
            val newHistory = if (index != null) listOf(path) else if (isBack) state.pathHistory.dropLast(1) else state.pathHistory + path
            state.copy(
                selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                currentPath = path,
                pathHistory = newHistory,
                isScanning = true,
                currentFiles = emptyList(),
                totalFoundCount = 0,
                isSeriesView = false,
                selectedMetadata = null,
                errorMessage = null
            )
        }

        scanJob = viewModelScope.launch {
            try {
                scanComicFoldersUseCase.execute(path).collect { file ->
                    if (scanId != currentScanId) return@collect
                    file.metadata?.let { posterRepository.cacheMetadata(file.path, it) }
                    allScannedFiles.add(file)
                    val sorted = allScannedFiles.sortedBy { it.name }
                    _uiState.update { it.copy(currentFiles = sorted, totalFoundCount = sorted.size) }
                }
            } catch (e: Exception) {
                if (scanId == currentScanId) _uiState.update { it.copy(errorMessage = "목록 로드 실패") }
            } finally {
                if (scanId == currentScanId) _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun loadNextPage() {
        if (uiState.value.isScanning) return
        val currentCount = _uiState.value.currentFiles.size
        if (currentCount < allScannedFiles.size) {
            _uiState.update { it.copy(currentFiles = allScannedFiles.take(currentCount + PAGE_SIZE)) }
        }
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
                val files = nasRepository.listFiles(file.path)
                val volumes = files.filter { it.name.lowercase().endsWith(".zip") || it.name.lowercase().endsWith(".cbz") }.sortedBy { it.name }
                if (volumes.isNotEmpty()) {
                    _uiState.update { it.copy(
                        currentPath = file.path,
                        pathHistory = it.pathHistory + file.path,
                        seriesEpisodes = volumes,
                        isLoading = false,
                        isSeriesView = true,
                        selectedMetadata = metadata,
                        selectedZipPath = null
                    ) }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    scanCategory(file.path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "로드 실패") }
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
            val results = allScannedFiles.filter { it.name.contains(query, ignoreCase = true) }
            _uiState.update { it.copy(searchResults = results) }
        }
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

    fun closeViewer() { _uiState.update { it.copy(selectedZipPath = null) } }

    fun onBack() {
        if (_uiState.value.isSearchMode) { toggleSearchMode(false); return }
        if (_uiState.value.selectedZipPath != null) { closeViewer(); return }
        val history = _uiState.value.pathHistory
        if (history.size > 1) {
            val newHistory = history.dropLast(1)
            _uiState.update { it.copy(pathHistory = newHistory) }
            scanCategory(newHistory.last(), isBack = true)
        } else onHome()
    }

    fun showError(message: String) { _uiState.update { it.copy(errorMessage = message, isLoading = false) } }
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}
