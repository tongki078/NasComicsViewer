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

actual fun provideNasRepository(): NasRepository = AndroidNasRepository()

class AndroidNasRepository : NasRepository {

    private var username = ""
    private var password = ""

    override fun setCredentials(username: String, password: String) {
        this.username = username
        this.password = password
    }

    private fun getContext(): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        }
        val config = PropertyConfiguration(properties)
        var context: CIFSContext = BaseContext(config)

        if (username.isNotEmpty()) {
            val auth = NtlmPasswordAuthenticator(username, password)
            context = context.withCredentials(auth)
        }

        return context
    }

    override suspend fun listFiles(url: String): List<NasFile> {
        return withContext(Dispatchers.IO) {
            val context = getContext()
            val smbFile = SmbFile(url, context)

            if (smbFile.isDirectory) {
                smbFile.listFiles().map { file ->
                    val fileName = file.name
                    val nextUrl = if (url.endsWith("/")) "$url$fileName" else "$url/$fileName"

                    NasFile(
                        name = fileName.trim('/'),
                        isDirectory = file.isDirectory,
                        path = nextUrl
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            } else {
                emptyList()
            }
        }
    }

    override suspend fun getFileContent(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val context = getContext()
                val smbFile = SmbFile(url, context)
                smbFile.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                e.printStackTrace()
                ByteArray(0)
            }
        }
    }

    override suspend fun downloadFile(url: String, destinationPath: String, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val context = getContext()
            val smbFile = SmbFile(url, context)
            val destinationFile = File(destinationPath)
            
            // Ensure parent dirs exist
            destinationFile.parentFile?.mkdirs()

            val totalBytes = smbFile.length()
            var bytesReadTotal = 0L
            val buffer = ByteArray(8192) // 8KB buffer

            smbFile.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesReadTotal += bytesRead
                        
                        if (totalBytes > 0) {
                            onProgress(bytesReadTotal.toFloat() / totalBytes)
                        }
                    }
                }
            }
            onProgress(1f) // Ensure 100% at end
        }
    }

    override fun getTempFilePath(fileName: String): String {
        val tempDir = System.getProperty("java.io.tmpdir") ?: "/tmp"
        return File(tempDir, fileName).absolutePath
    }

    override fun scanComicFolders(url: String, maxDepth: Int): Flow<NasFile> = flow {
        scanRecursiveEmit(url, 0, maxDepth)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<NasFile>.scanRecursiveEmit(url: String, currentDepth: Int, maxDepth: Int) {
        if (currentDepth > maxDepth) return
        
        try {
            // Re-create context? Usually reusing context is safer but here we need to ensure thread safety?
            // Since this is in flow context, sequential?
            // Better to instantiate context fresh or ensure thread safety.
            // But for simplicity, let's just assume `getContext()` creates fresh or safe context.
            // Our getContext() creates new context every time, which is heavy but safe.
            // Ideally optimize context creation. For now keep simple.
            
            val context = getContext() 
            val smbFile = SmbFile(url, context)
            
            if (!smbFile.isDirectory) return

            val files = smbFile.listFiles() ?: return
            
            // Check if this folder contains comic files (ZIP/CBZ)
            val hasComics = files.any { 
                val n = it.name.lowercase()
                !it.isDirectory && (n.endsWith(".zip") || n.endsWith(".cbz"))
            }

            if (hasComics) {
                // Found! Emit result
                val folderName = smbFile.name.trimEnd('/')
                emit(NasFile(
                    name = folderName,
                    isDirectory = true,
                    path = url
                ))
                return 
            }

            // Go deeper - Sort subfolders to emit in order
            files.filter { it.isDirectory }
                .sortedBy { it.name }
                .forEach { subFolder ->
                    val subName = subFolder.name
                    val nextUrl = if (url.endsWith("/")) "$url$subName" else "$url/$subName"
                    scanRecursiveEmit(nextUrl, currentDepth + 1, maxDepth)
                }
        } catch (e: Exception) {
            // Ignore errors
        }
    }
}