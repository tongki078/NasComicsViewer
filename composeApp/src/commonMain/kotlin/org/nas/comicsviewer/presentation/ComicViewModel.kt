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
    val totalFoundCount: Int = 0
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
                _uiState.update { it.copy(errorMessage = "Failed to load categories: ${e.message}") }
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

    fun scanCategory(path: String, index: Int? = null) {
        scanJob?.cancel()
        allScannedFiles.clear()
        
        scanJob = viewModelScope.launch {
            _uiState.update { state -> 
                state.copy(
                    selectedCategoryIndex = index ?: state.selectedCategoryIndex,
                    currentPath = path,
                    pathHistory = if (index != null) listOf(path) else state.pathHistory + path,
                    isScanning = true, 
                    currentFiles = emptyList(),
                    totalFoundCount = 0
                )
            }
            
            scanComicFoldersUseCase.execute(path)
                .onCompletion { 
                    _uiState.update { it.copy(isScanning = false) } 
                    if (_uiState.value.currentFiles.isEmpty() && allScannedFiles.isNotEmpty()) {
                        loadNextPage()
                    }
                }
                .collect { file ->
                    allScannedFiles.add(file)
                    _uiState.update { it.copy(totalFoundCount = allScannedFiles.size) }
                    
                    if (allScannedFiles.size <= PAGE_SIZE) {
                        _uiState.update { it.copy(currentFiles = allScannedFiles.toList()) }
                    }
                }
        }
    }

    fun loadNextPage() {
        val currentCount = _uiState.value.currentFiles.size
        if (allScannedFiles.size <= currentCount) return

        val nextItems = allScannedFiles.drop(currentCount).take(PAGE_SIZE)
        if (nextItems.isNotEmpty()) {
            _uiState.update { it.copy(currentFiles = it.currentFiles + nextItems) }
        }
    }

    fun onFileClick(file: NasFile) {
        if (!file.isDirectory) {
            viewModelScope.launch { _onOpenZipRequested.emit(file) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val files = nasRepository.listFiles(file.path)
                val zips = files.filter { it.name.lowercase().endsWith(".zip") || it.name.lowercase().endsWith(".cbz") }
                
                if (zips.isNotEmpty()) {
                    if (zips.size == 1) {
                        _onOpenZipRequested.emit(zips[0])
                        _uiState.update { it.copy(isLoading = false) }
                    } else {
                        val sortedZips = zips.sortedBy { it.name }
                        _uiState.update { state ->
                            state.copy(
                                currentPath = file.path,
                                pathHistory = state.pathHistory + file.path,
                                currentFiles = sortedZips,
                                isScanning = false,
                                isLoading = false,
                                totalFoundCount = sortedZips.size
                            )
                        }
                        allScannedFiles.clear()
                        allScannedFiles.addAll(sortedZips)
                    }
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
        if (_uiState.value.pathHistory.size > 1) {
            val newHistory = _uiState.value.pathHistory.dropLast(1)
            val prevPath = newHistory.last()
            
            val categoryIndex = _uiState.value.categories.indexOfFirst { it.path == prevPath }
            
            scanCategory(prevPath, if (categoryIndex != -1) categoryIndex else null)
            _uiState.update { it.copy(pathHistory = newHistory) }
        }
    }
}