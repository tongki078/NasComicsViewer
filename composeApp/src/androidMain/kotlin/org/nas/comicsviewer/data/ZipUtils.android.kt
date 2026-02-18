package org.nas.comicsviewer.data

import jcifs.CIFSContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nas.comicsviewer.domain.model.ComicInfo
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.nio.charset.Charset

actual fun provideZipManager(): ZipManager = AndroidZipManager()

class AndroidZipManager : ZipManager {

    private fun getContext(): CIFSContext {
        val repo = AndroidNasRepository.getInstance()
        val (user, pass) = repo.getCredentials()
        val auth = if (user.isNotEmpty()) jcifs.smb.NtlmPasswordAuthenticator(user, pass) else null
        val context = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.sessionTimeout", "60000")
        }))
        return if (auth != null) context.withCredentials(auth) else context
    }

    private fun getSafeUrl(url: String): String {
        return url.replace("%", "%25")
                  .replace("#", "%23")
                  .replace("[", "%5B")
                  .replace("]", "%5D")
                  .replace("{", "%7B")
                  .replace("}", "%7D")
                  .replace(" ", "%20")
                  .replace("^", "%5E")
    }

    override suspend fun listImagesInZip(filePath: String): List<String> = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(getSafeUrl(filePath), filePath)
        val encodings = listOf("UTF-8", "CP949", "EUC-KR")

        for (url in urlsToTry) {
            try {
                val smbFile = SmbFile(url, getContext())
                if (smbFile.exists()) {
                    for (enc in encodings) {
                        val images = mutableListOf<String>()
                        try {
                            smbFile.inputStream.use { input ->
                                val zipInput = ZipInputStream(input, Charset.forName(enc))
                                var entry: ZipEntry? = zipInput.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val name = entry.name.lowercase()
                                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                                            images.add(entry.name)
                                        }
                                    }
                                    zipInput.closeEntry()
                                    entry = zipInput.nextEntry
                                }
                            }
                            if (images.isNotEmpty()) return@withContext images.sorted()
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) { }
        }
        emptyList()
    }

    override suspend fun streamAllImages(zipPath: String, onImageExtracted: suspend (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(getSafeUrl(zipPath), zipPath)
        val context = getContext()
        
        val smbFile = urlsToTry.mapNotNull { url ->
            try {
                val f = SmbFile(url, context)
                if (f.exists()) f else null
            } catch (e: Exception) {
                null
            }
        }.firstOrNull() ?: return@withContext

        val encodings = listOf("UTF-8", "CP949", "EUC-KR")
        val correctEnc = encodings.firstOrNull { enc ->
            try {
                smbFile.inputStream.use { input ->
                    ZipInputStream(input, Charset.forName(enc)).nextEntry != null
                }
            } catch (e: Exception) {
                false
            }
        } ?: "UTF-8"

        try {
            smbFile.inputStream.use { input ->
                val zipInput = ZipInputStream(input, Charset.forName(correctEnc))
                var entry: ZipEntry? = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.lowercase()
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                            val output = ByteArrayOutputStream()
                            zipInput.copyTo(output)
                            onImageExtracted(entry.name, output.toByteArray())
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
        } catch (e: Exception) {}
    }

    override suspend fun extractImage(zipPath: String, imageName: String): ByteArray? = null
    override fun getComicInfo(zipPath: String): ComicInfo? = null
}
