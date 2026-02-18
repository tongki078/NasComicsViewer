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
        @Volatile private var instance: AndroidNasRepository? = null
        fun getInstance() = instance ?: synchronized(this) { instance ?: AndroidNasRepository().also { instance = it } }
    }

    override fun setCredentials(u: String, p: String) { this.username = u; this.password = p }
    override fun getCredentials() = Pair(username, password)

    private fun getContext(): CIFSContext {
        if (baseContext == null) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.connTimeout", "10000")
                setProperty("jcifs.smb.client.sessionTimeout", "30000")
            }
            baseContext = BaseContext(PropertyConfiguration(props))
        }
        return if (username.isNotEmpty()) baseContext!!.withCredentials(NtlmPasswordAuthenticator(username, password)) else baseContext!!
    }

    // 특수문자(# 등) 대응을 위한 최소한의 안전 장치
    private fun getSafeUrl(url: String): String {
        return if (url.contains("#") && !url.contains("%23")) {
            url.replace("#", "%23")
        } else url
    }

    override suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {
        try {
            val smbFile = SmbFile(getSafeUrl(url), getContext())
            smbFile.listFiles()?.map { 
                NasFile(it.name.trim('/'), it.isDirectory, it.canonicalPath) 
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getFileContent(url: String): ByteArray = withContext(Dispatchers.IO) {
        try { SmbFile(getSafeUrl(url), getContext()).inputStream.use { it.readBytes() } } catch (e: Exception) { ByteArray(0) }
    }

    override suspend fun downloadFile(url: String, destinationPath: String, onProgress: (Float) -> Unit) {}
    override fun getTempFilePath(fileName: String) = ""

    override fun scanComicFolders(url: String, maxDepth: Int): Flow<NasFile> = flow {
        scanRecursive(url, 0, 5)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<NasFile>.scanRecursive(url: String, depth: Int, max: Int) {
        if (depth > max) return
        try {
            val smbFile = SmbFile(getSafeUrl(url), getContext())
            val children = smbFile.listFiles() ?: return
            
            // 만화책이 있는 폴더인지 확인
            if (children.any { it.name.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".cbz") } }) {
                emit(NasFile(smbFile.name.trim('/'), true, smbFile.canonicalPath))
                // 만화 폴더를 찾았더라도 하위 폴더에 다른 작품이 있을 수 있으므로 계속 탐색
            }

            children.filter { it.isDirectory }.forEach { 
                scanRecursive(it.canonicalPath, depth + 1, max) 
            }
        } catch (e: Exception) {}
    }
}
