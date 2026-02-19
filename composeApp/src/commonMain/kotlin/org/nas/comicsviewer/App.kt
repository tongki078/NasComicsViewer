package org.nas.comicsviewer

import androidx.compose.runtime.*
import org.nas.comicsviewer.data.ComicDatabase
import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.PosterRepository
import org.nas.comicsviewer.data.provideNasRepository
import org.nas.comicsviewer.domain.usecase.GetCategoriesUseCase
import org.nas.comicsviewer.presentation.ComicViewModel
import org.nas.comicsviewer.presentation.NasComicApp

@Composable
fun App(database: ComicDatabase, nasRepository: NasRepository, posterRepository: PosterRepository) {
    val getCategoriesUseCase = remember { GetCategoriesUseCase(nasRepository) }
    
    val comicViewModel = remember {
        ComicViewModel(
            nasRepository = nasRepository, 
            posterRepository = posterRepository,
            getCategoriesUseCase = getCategoriesUseCase,
            database = database
        )
    }

    NasComicApp(comicViewModel)
}