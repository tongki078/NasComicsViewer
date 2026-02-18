package org.nas.comicsviewer

import androidx.compose.runtime.*
import org.nas.comicsviewer.data.provideNasRepository
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase
import org.nas.comicsviewer.domain.usecase.ScanComicFoldersUseCase
import org.nas.comicsviewer.domain.usecase.SetCredentialsUseCase
import org.nas.comicsviewer.presentation.ComicViewModel
import org.nas.comicsviewer.presentation.NasComicApp

@Composable
fun App() {
    // Simple manual DI (Dependency Injection)
    val nasRepository = remember { provideNasRepository() }
    val getCategoriesUseCase = remember { GetCategoriesUseCase(nasRepository) }
    val scanComicFoldersUseCase = remember { ScanComicFoldersUseCase(nasRepository) }
    val setCredentialsUseCase = remember { SetCredentialsUseCase(nasRepository) }
    
    val comicViewModel = remember {
        ComicViewModel(nasRepository, getCategoriesUseCase, scanComicFoldersUseCase, setCredentialsUseCase)
    }

    NasComicApp(comicViewModel)
}