package org.nas.comicsviewer

import androidx.compose.runtime.*
import org.nas.comicsviewer.data.*
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase
import org.nas.comicsviewer.domain.usecase.ScanComicFoldersUseCase
import org.nas.comicsviewer.domain.usecase.SetCredentialsUseCase
import org.nas.comicsviewer.presentation.ComicViewModel
import org.nas.comicsviewer.presentation.NasComicApp

@Composable
fun App(databaseDriverFactory: DatabaseDriverFactory) {
    // 1. Database Initialization
    val database = remember { ComicDatabase(databaseDriverFactory.createDriver()) }
    
    // 2. Repositories
    val nasRepository = remember { provideNasRepository() }
    val posterRepository = remember { 
        providePosterRepository().apply { setDatabase(database) } 
    }
    
    // 3. UseCases
    val getCategoriesUseCase = remember { GetCategoriesUseCase(nasRepository) }
    val scanComicFoldersUseCase = remember { ScanComicFoldersUseCase(nasRepository) }
    val setCredentialsUseCase = remember { SetCredentialsUseCase(nasRepository) }
    
    // 4. ViewModel
    val comicViewModel = remember {
        ComicViewModel(
            nasRepository, 
            posterRepository,
            getCategoriesUseCase, 
            scanComicFoldersUseCase, 
            setCredentialsUseCase
        )
    }

    NasComicApp(comicViewModel)
}