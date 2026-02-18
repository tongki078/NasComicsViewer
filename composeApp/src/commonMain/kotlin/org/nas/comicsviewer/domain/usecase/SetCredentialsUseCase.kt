package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository

class SetCredentialsUseCase(private val nasRepository: NasRepository) {
    fun execute(username: String, password: String) {
        nasRepository.setCredentials(username, password)
    }
}