package org.nas.comicsviewer.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

actual fun provideZipManager(): ZipManager = AndroidZipManager()

class AndroidZipManager : ZipManager {
    override fun listImagesInZip(filePath: String): List<String> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()

        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { 
                        val name = it.name.lowercase()
                        name.endsWith(".jpg") || 
                        name.endsWith(".jpeg") || 
                        name.endsWith(".png") ||
                        name.endsWith(".webp")
                    }
                    .map { it.name }
                    .sorted()
                    .toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun extractImage(zipPath: String, imageName: String): ByteArray? {
        return try {
            ZipFile(zipPath).use { zip ->
                val entry = zip.getEntry(imageName) ?: return null
                zip.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toByteArray()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}