package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.NasFile

class GetCategoriesUseCase(private val nasRepository: NasRepository) {
    suspend fun execute(rootUrl: String): List<NasFile> {
        return nasRepository.listFiles(rootUrl)
            .filter { it.isDirectory }
            .sortedBy { it.name }
    }
}