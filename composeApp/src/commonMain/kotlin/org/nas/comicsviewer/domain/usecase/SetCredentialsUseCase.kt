package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository

class SetCredentialsUseCase(private val nasRepository: NasRepository) {
    fun execute(username: String, password: String) {
        // Implementation depends on how NasRepository handles credentials.
        // For now, it's a placeholder if needed by ViewModel.
    }
}
