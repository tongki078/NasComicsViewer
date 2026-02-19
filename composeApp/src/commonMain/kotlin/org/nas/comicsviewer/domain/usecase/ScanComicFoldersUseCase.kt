package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.NasFile
import kotlinx.coroutines.flow.Flow

class ScanComicFoldersUseCase(private val nasRepository: NasRepository) {
    fun execute(path: String): Flow<NasFile> {
        return nasRepository.scanComicFolders(path)
    }
}
