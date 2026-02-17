package org.nas.comicsviewer.presentation

import org.nas.comicsviewer.data.NasFile
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase
import org.nas.comicsviewer.domain.usecase.ScanComicFoldersUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// UI State
data class ComicBrowserUiState(
    val categories: List<NasFile> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentFiles: List<NasFile> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)

class ComicViewModel(
    private val getCategoriesUseCase: GetCategoriesUseCase,
    private val scanComicFoldersUseCase: ScanComicFoldersUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicBrowserUiState())
    val uiState: StateFlow<ComicBrowserUiState> = _uiState.asStateFlow()

    fun loadCategories(rootUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val categories = getCategoriesUseCase.execute(rootUrl)
                _uiState.update { it.copy(categories = categories) }
                if (categories.isNotEmpty()) {
                    scanCategory(categories[0].path)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load categories: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun scanCategory(path: String, index: Int? = null) {
        viewModelScope.launch {
            // Update category index if provided
            index?.let { _uiState.update { state -> state.copy(selectedCategoryIndex = it) } }
            
            _uiState.update { it.copy(isScanning = true, currentFiles = emptyList()) }
            
            scanComicFoldersUseCase.execute(path)
                .onCompletion { _uiState.update { it.copy(isScanning = false) } }
                .collect { file ->
                    _uiState.update { state ->
                        state.copy(currentFiles = state.currentFiles + file)
                    }
                }
        }
    }
}