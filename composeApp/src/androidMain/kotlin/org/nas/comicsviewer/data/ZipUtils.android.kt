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
import java.io.BufferedInputStream
import java.io.InputStream

actual fun provideZipManager(): ZipManager = AndroidZipManager()

class AndroidZipManager : ZipManager {
    private fun getContext(): CIFSContext {
        val (u, p) = AndroidNasRepository.getInstance().getCredentials()
        val props = java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        return jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(props)).let {
            if (u.isNotEmpty()) it.withCredentials(jcifs.smb.NtlmPasswordAuthenticator(u, p)) else it
        }
    }

    private fun getSafeUrl(url: String): String {
        return if (url.contains("#") && !url.contains("%23")) {
            url.replace("#", "%23")
        } else url
    }

    override suspend fun listImagesInZip(path: String): List<String> = emptyList()

    override suspend fun streamAllImages(path: String, onProgress: (Float) -> Unit, onImage: suspend (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val context = getContext()
        val smbFile = SmbFile(getSafeUrl(path), context)
        if (!smbFile.exists()) return@withContext

        val totalSize = smbFile.length().toDouble()
        val encodings = listOf("CP949", "UTF-8", "EUC-KR")
        var success = false

        for (enc in encodings) {
            if (success) break
            try {
                smbFile.inputStream.use { input ->
                    var bytesRead = 0L
                    val tracking = object : InputStream() {
                        override fun read(): Int = input.read().also { if (it != -1) bytesRead++ }
                        override fun read(b: ByteArray, o: Int, l: Int): Int = input.read(b, o, l).also { 
                            if (it != -1) { 
                                bytesRead += it
                                onProgress((bytesRead / totalSize).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    val zip = ZipInputStream(BufferedInputStream(tracking, 1024 * 1024), Charset.forName(enc))
                    var entry: ZipEntry? = zip.nextEntry
                    if (entry != null) success = true
                    while (entry != null) {
                        if (!entry.isDirectory && isImage(entry.name)) {
                            val out = ByteArrayOutputStream()
                            zip.copyTo(out)
                            onImage(entry.name, out.toByteArray())
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } catch (e: Exception) {}
        }
        onProgress(1.0f)
    }

    private fun isImage(n: String) = n.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
    override suspend fun extractImage(p: String, n: String): ByteArray? = null
    override fun getComicInfo(p: String): ComicInfo? = null
}
