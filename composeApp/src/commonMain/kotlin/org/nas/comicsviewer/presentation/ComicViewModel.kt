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
import kotlinx.coroutines.delay

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
    val isSeriesView: Boolean = false,
    val isIntroShowing: Boolean = true
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
                } else {
                    _uiState.update { it.copy(errorMessage = "서버에서 폴더 목록을 가져올 수 없습니다.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "연결 실패: ${e.message}") }
            } finally {
                // 어떤 경우에도 인트로는 닫아서 메인 UI를 보여줌
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
                    totalFoundCount = 0,
                    isSeriesView = false
                )
            }
            
            scanComicFoldersUseCase.execute(path)
                .onCompletion { 
                    _uiState.update { it.copy(isScanning = false) } 
                    if (allScannedFiles.isNotEmpty() && _uiState.value.currentFiles.isEmpty()) loadNextPage()
                }
                .collect { file ->
                    allScannedFiles.add(file)
                    allScannedFiles.sortBy { it.name }
                    _uiState.update { it.copy(totalFoundCount = allScannedFiles.size) }
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
        _uiState.update { it.copy(currentFiles = allScannedFiles.take(currentCount + PAGE_SIZE)) }
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
                val zips = files.filter { it.name.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".cbz") } }
                if (zips.isNotEmpty()) {
                    val sortedZips = zips.sortedBy { it.name }
                    _uiState.update { it.copy(currentPath = file.path, pathHistory = it.pathHistory + file.path, currentFiles = sortedZips, isLoading = false, isSeriesView = true) }
                    allScannedFiles.addAll(sortedZips)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    scanCategory(file.path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "폴더 접근 실패: ${e.message}") }
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
