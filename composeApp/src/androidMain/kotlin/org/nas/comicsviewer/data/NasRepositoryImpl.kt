package org.nas.comicsviewer.data

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

actual fun provideNasRepository(): NasRepository = AndroidNasRepository.getInstance()

class AndroidNasRepository private constructor() : NasRepository {

    private var username = ""
    private var password = ""
    private var baseContext: CIFSContext? = null

    companion object {
        @Volatile
        private var instance: AndroidNasRepository? = null
        fun getInstance(): AndroidNasRepository {
            return instance ?: synchronized(this) {
                instance ?: AndroidNasRepository().also { instance = it }
            }
        }
    }

    override fun setCredentials(username: String, password: String) {
        this.username = username
        this.password = password
    }
    
    override fun getCredentials(): Pair<String, String> {
        return Pair(username, password)
    }

    private fun getContext(): CIFSContext {
        if (baseContext == null) {
            val properties = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
                setProperty("jcifs.smb.client.connTimeout", "10000")
                setProperty("jcifs.smb.client.sessionTimeout", "30000")
            }
            val config = PropertyConfiguration(properties)
            baseContext = BaseContext(config)
        }
        var context = baseContext!!
        if (username.isNotEmpty()) {
            val auth = NtlmPasswordAuthenticator(username, password)
            context = context.withCredentials(auth)
        }
        return context
    }

    // 획기적인 경로 보호: 모든 특수문자를 안전하게 변환
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

    override suspend fun listFiles(url: String): List<NasFile> {
        return withContext(Dispatchers.IO) {
            try {
                val context = getContext()
                val smbFile = SmbFile(getSafeUrl(url), context)
                if (smbFile.isDirectory) {
                    smbFile.listFiles()?.map { file ->
                        NasFile(
                            name = file.name.trim('/'),
                            isDirectory = file.isDirectory,
                            path = file.canonicalPath
                        )
                    }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    override suspend fun getFileContent(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val context = getContext()
                val smbFile = SmbFile(getSafeUrl(url), context)
                smbFile.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                e.printStackTrace()
                ByteArray(0)
            }
        }
    }

    override suspend fun downloadFile(url: String, destinationPath: String, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val context = getContext()
                val smbFile = SmbFile(getSafeUrl(url), context)
                val destinationFile = File(destinationPath)
                destinationFile.parentFile?.mkdirs()
                val totalBytes = smbFile.length()
                var bytesReadTotal = 0L
                val buffer = ByteArray(16384) 
                smbFile.inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesReadTotal += bytesRead
                            if (totalBytes > 0) onProgress(bytesReadTotal.toFloat() / totalBytes)
                        }
                    }
                }
                onProgress(1f)
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    override fun getTempFilePath(fileName: String): String {
        val tempDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        return File(tempDir, fileName).absolutePath
    }

    override fun scanComicFolders(url: String, maxDepth: Int): Flow<NasFile> = flow {
        scanRecursiveEmit(url, 0, 5) // 충분한 깊이로 탐색
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<NasFile>.scanRecursiveEmit(url: String, currentDepth: Int, maxDepth: Int) {
        if (currentDepth > maxDepth) return
        try {
            val context = getContext() 
            val smbFile = SmbFile(getSafeUrl(url), context)
            if (!smbFile.isDirectory) return
            val files = smbFile.listFiles() ?: return
            val hasComics = files.any { 
                val n = it.name.lowercase()
                !it.isDirectory && (n.endsWith(".zip") || n.endsWith(".cbz") || n.endsWith(".rar"))
            }
            if (hasComics) {
                emit(NasFile(name = smbFile.name.trim('/'), isDirectory = true, path = smbFile.canonicalPath))
            }
            files.filter { it.isDirectory }
                .sortedBy { it.name }
                .forEach { subFolder ->
                    scanRecursiveEmit(subFolder.canonicalPath, currentDepth + 1, maxDepth)
                }
        } catch (e: Exception) {}
    }
}