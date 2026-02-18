package org.nas.comicsviewer.presentation

import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.domain.usecase.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

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
    val selectedZipPath: String? = null
)

class ComicViewModel(
    private val nasRepository: NasRepository,
    val posterRepository: PosterRepository,
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val scanComicFoldersUseCase: ScanComicFoldersUseCase,
    private val setCredentialsUseCase: SetCredentialsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicBrowserUiState())
    val uiState: StateFlow<ComicBrowserUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var prefetchJob: Job? = null // 백그라운드 수집용 잡
    private val allScannedFiles = mutableListOf<NasFile>()
    private val PAGE_SIZE = 24

    fun initialize(rootUrl: String, username: String, password: String) {
        setCredentialsUseCase.execute(username, password)
        loadCategories(rootUrl)
    }

    private fun loadCategories(rootUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val categories = getCategoriesUseCase.execute(rootUrl)
                _uiState.update { it.copy(categories = categories) }
                if (categories.isNotEmpty()) scanCategory(categories[0].path, 0)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "연결 실패: ${e.message}") }
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
        prefetchJob?.cancel() // 새 스캔 시작 시 이전 수집 중단
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
                    selectedZipPath = null
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
            
            // [획기적 추가] 스캔 완료 후 백그라운드에서 포스터 미리 수집 시작
            startPosterPrefetch()
        }
    }

    // 백그라운드에서 모든 만화의 포스터를 하나씩 미리 캐싱하는 로직
    private fun startPosterPrefetch() {
        prefetchJob = viewModelScope.launch {
            allScannedFiles.forEach { file ->
                // 이미 캐시된 포스터가 있는지 확인하고 없으면 가져옴
                // PosterRepository.getMetadata 내부에서 SQLite 캐시를 확인하므로 안전함
                posterRepository.getMetadata(file.path)
                // NAS 부하 방지를 위해 0.5초 간격으로 조용히 작업
                delay(500)
            }
        }
    }

    fun loadNextPage() {
        if (_uiState.value.isSeriesView) return
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
                        selectedZipPath = null
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

    fun closeViewer() {
        _uiState.update { it.copy(selectedZipPath = null) }
    }

    fun onBack() {
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