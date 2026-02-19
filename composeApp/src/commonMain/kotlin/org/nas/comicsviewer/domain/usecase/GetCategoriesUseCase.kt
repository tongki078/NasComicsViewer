package org.nas.comicsviewer.domain.usecase

import org.nas.comicsviewer.data.NasRepository
import org.nas.comicsviewer.data.NasFile

class GetCategoriesUseCase(private val nasRepository: NasRepository) {
    private val nameMap = mapOf(
        "ㅂㅇ" to "번역",
        "ㅇㅈ" to "연재",
        "ㅇㄱ" to "완결",
        "ㅈㄱ" to "작가",
    )

    suspend fun execute(rootUrl: String): List<NasFile> {
        val adultKeywords = listOf("성인", "19", "adult", "hentai", "에로", "섹스", "sex")
        
        val categories = nasRepository.listFiles(rootUrl)
            .filter { file -> 
                file.isDirectory && adultKeywords.none { keyword -> 
                    file.name.lowercase().contains(keyword) 
                }
            }
            .map { file ->
                // 이름 매핑 적용
                file.copy(name = nameMap[file.name] ?: file.name)
            }
            
        // "완결A", "완결B"를 우선순위로 정렬하고 나머지는 이름순
        return categories.sortedWith { a, b ->
            val priorityA = getPriority(a.name)
            val priorityB = getPriority(b.name)
            
            if (priorityA != priorityB) {
                priorityA - priorityB
            } else {
                a.name.compareTo(b.name)
            }
        }
    }

    private fun getPriority(name: String): Int {
        return when {
            name.contains("완결A") -> 1
            name.contains("완결B") -> 2
            else -> 10
        }
    }
}