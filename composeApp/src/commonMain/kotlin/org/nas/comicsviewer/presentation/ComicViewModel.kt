package org.nas.comicsviewer.presentation

import org.nas.comicsviewer.data.NasFile
import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.PosterRepository
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase
import org.nas.comicsviewer.domain.usecase.ScanComicFoldersUseCase
import org.nas.comicsviewer.domain.usecase.SetCredentialsUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job

data class ComicBrowserUiState(
    val categories: List<NasFile> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentFiles: List<NasFile> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val isPaging: Boolean = false,
    val errorMessage: String? = null,
    val currentPath: String? = null,
    val pathHistory: List<String> = emptyList(),
    val totalFoundCount: Int = 0,
    val isSeriesView: Boolean = false
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
                if (categories.isNotEmpty()) {
                    scanCategory(categories[0].path, 0)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "카테고리 로드 실패: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onHome() {
        val categories = _uiState.value.categories
        if (categories.isNotEmpty()) {
            scanCategory(categories[0].path, 0)
        }
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
                    totalFoundCount = 0,
                    isSeriesView = false
                )
            }
            
            scanComicFoldersUseCase.execute(path)
                .onCompletion { 
                    _uiState.update { it.copy(isScanning = false) } 
                    loadNextPage()
                }
                .collect { file ->
                    allScannedFiles.add(file)
                    // 발견될 때마다 이름순 정렬 유지
                    allScannedFiles.sortWith(compareBy { it.name })
                    
                    _uiState.update { it.copy(totalFoundCount = allScannedFiles.size) }
                    
                    // 첫 페이지 분량만큼은 즉시 UI 반영
                    if (_uiState.value.currentFiles.size < PAGE_SIZE) {
                        _uiState.update { it.copy(currentFiles = allScannedFiles.take(PAGE_SIZE)) }
                    }
                }
        }
    }

    fun loadNextPage() {
        if (_uiState.value.isSeriesView) return
        
        val currentCount = _uiState.value.currentFiles.size
        if (allScannedFiles.size <= currentCount) return

        val nextItems = allScannedFiles.take(currentCount + PAGE_SIZE)
        _uiState.update { it.copy(currentFiles = nextItems) }
    }

    fun onFileClick(file: NasFile) {
        if (!file.isDirectory) {
            viewModelScope.launch { _onOpenZipRequested.emit(file) }
            return
        }

        scanJob?.cancel()
        allScannedFiles.clear()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val files = nasRepository.listFiles(file.path)
                val zips = files.filter { 
                    val n = it.name.lowercase()
                    n.endsWith(".zip") || n.endsWith(".cbz") || n.endsWith(".rar") 
                }
                
                if (zips.isNotEmpty()) {
                    val sortedZips = zips.sortedBy { it.name }
                    _uiState.update { state ->
                        state.copy(
                            currentPath = file.path,
                            pathHistory = state.pathHistory + file.path,
                            currentFiles = sortedZips,
                            isScanning = false,
                            isLoading = false,
                            totalFoundCount = sortedZips.size,
                            isSeriesView = true
                        )
                    }
                    allScannedFiles.addAll(sortedZips)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    scanCategory(file.path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun onBack() {
        val history = _uiState.value.pathHistory
        if (history.size > 1) {
            val newHistory = history.dropLast(1)
            val prevPath = newHistory.last()
            val categoryIndex = _uiState.value.categories.indexOfFirst { it.path == prevPath }
            _uiState.update { it.copy(pathHistory = newHistory) }
            scanCategory(prevPath, if (categoryIndex != -1) categoryIndex else null, isBack = true)
        } else {
            onHome()
        }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}