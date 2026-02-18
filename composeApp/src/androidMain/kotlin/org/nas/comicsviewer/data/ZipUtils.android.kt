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

actual fun provideZipManager(): ZipManager = AndroidZipManager()

class AndroidZipManager : ZipManager {
    private fun getContext(): CIFSContext {
        val (u, p) = AndroidNasRepository.getInstance().getCredentials()
        val props = java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.sessionTimeout", "60000")
        }
        val config = jcifs.config.PropertyConfiguration(props)
        val context = jcifs.context.BaseContext(config)
        return if (u.isNotEmpty()) context.withCredentials(jcifs.smb.NtlmPasswordAuthenticator(u, p)) else context
    }

    override suspend fun listImagesInZip(path: String): List<String> = emptyList() // 이제 사용 안함

    override suspend fun streamAllImages(path: String, onProgress: (Float) -> Unit, onImage: suspend (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val encodings = listOf("CP949", "UTF-8", "EUC-KR")
        val context = getContext()
        val smbFile = SmbFile(path, context) // path는 이미 canonicalPath로 인코딩된 상태임
        val totalSize = smbFile.length().toFloat()
        
        var success = false
        for (enc in encodings) {
            if (success) break
            try {
                smbFile.inputStream.use { input ->
                    // 128KB 버퍼로 네트워크 대역폭 활용 극대화
                    val bis = BufferedInputStream(input, 128 * 1024)
                    val zip = ZipInputStream(bis, Charset.forName(enc))
                    var entry: ZipEntry? = zip.nextEntry
                    
                    if (entry != null) success = true // 읽기 시작했다면 인코딩 맞는 것으로 간주
                    
                    var readBytes = 0L
                    while (entry != null) {
                        if (!entry.isDirectory && isImage(entry.name)) {
                            val out = ByteArrayOutputStream()
                            zip.copyTo(out)
                            onImage(entry.name, out.toByteArray())
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                        // 대략적인 진행률 (압축 해제 위치 기준)
                        onProgress(0.5f) 
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
