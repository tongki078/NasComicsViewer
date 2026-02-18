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
    // [획기적 개선] 상세 페이지 전용 에피소드 리스트 추가
    val seriesEpisodes: List<NasFile> = emptyList()
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

    private val _onOpenZipRequested = MutableSharedFlow<NasFile>()
    val onOpenZipRequested = _onOpenZipRequested.asSharedFlow()

    private var scanJob: Job? = null
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
                    seriesEpisodes = emptyList() // 상세 정보 초기화
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
        }
    }

    fun loadNextPage() {
        if (_uiState.value.isSeriesView) return
        val currentCount = _uiState.value.currentFiles.size
        _uiState.update { it.copy(currentFiles = allScannedFiles.take(currentCount + PAGE_SIZE)) }
    }

    fun onFileClick(file: NasFile) {
        if (!file.isDirectory) {
            viewModelScope.launch { _onOpenZipRequested.emit(file) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 1. 메타데이터(YAML) 로드
                val metadata = posterRepository.getMetadata(file.path)
                
                // 2. 해당 폴더 내부의 진짜 권수(ZIP) 리스트 로드
                // 경로 끝에 /가 없으면 문제가 생길 수 있으므로 보정
                val targetPath = if (file.path.endsWith("/")) file.path else "${file.path}/"
                val filesInFolder = nasRepository.listFiles(targetPath)
                val volumes = filesInFolder.filter { 
                    it.name.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".cbz") } 
                }.sortedBy { it.name }
                
                if (volumes.isNotEmpty()) {
                    _uiState.update { it.copy(
                        currentPath = file.path,
                        pathHistory = it.pathHistory + file.path,
                        seriesEpisodes = volumes, // 전용 리스트에 저장!
                        isLoading = false,
                        isSeriesView = true,
                        selectedMetadata = metadata
                    ) }
                } else {
                    // ZIP이 없으면 하위 폴더 스캔 (시리즈 속 시리즈인 경우)
                    _uiState.update { it.copy(isLoading = false) }
                    scanCategory(file.path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "에피소드 로드 실패: ${e.message}") }
            }
        }
    }

    fun onBack() {
        val history = _uiState.value.pathHistory
        if (history.size > 1) {
            val newHistory = history.dropLast(1)
            _uiState.update { it.copy(pathHistory = newHistory) }
            scanCategory(newHistory.last(), isBack = true)
        } else onHome()
    }

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}