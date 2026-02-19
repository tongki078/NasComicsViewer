package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.ScanResult

class ScanComicFoldersUseCase(private val nasRepository: NasRepository) {
    suspend fun execute(path: String, page: Int, pageSize: Int): ScanResult {
        return nasRepository.scanComicFolders(path, page, pageSize)
    }
}
