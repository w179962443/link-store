package com.example.urlkeeper

import com.example.urlkeeper.backup.BackupSettings
import com.example.urlkeeper.backup.BackupSettingsStore
import com.example.urlkeeper.backup.GiteeGistClient
import com.example.urlkeeper.data.UrlDao
import com.example.urlkeeper.data.UrlEntry
import com.example.urlkeeper.export.ExportResult
import com.example.urlkeeper.export.OneTabExporter
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class UrlRepository(
    private val dao: UrlDao,
    private val exporter: OneTabExporter,
    private val backupSettingsStore: BackupSettingsStore,
    private val gistClient: GiteeGistClient
) {
    val entries: Flow<List<UrlEntry>> = dao.observeAll()
    val backupSettings: Flow<BackupSettings> = backupSettingsStore.settings

    suspend fun saveUrl(rawUrl: String): SaveResult = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)
        val domain = extractDomain(normalized)
        dao.insert(UrlEntry(url = normalized, domain = domain, createdAt = System.currentTimeMillis()))
        val backupMessage = syncBackupSafely()
        SaveResult(domain = domain, backupMessage = backupMessage)
    }

    suspend fun exportAndClear(): ExportResult = withContext(Dispatchers.IO) {
        val currentEntries = dao.getAll()
        val result = exporter.export(currentEntries)
        dao.delete(currentEntries)
        syncBackupSafely()
        result
    }

    suspend fun saveBackupSettings(settings: BackupSettings): String? = withContext(Dispatchers.IO) {
        backupSettingsStore.save(settings)
        syncBackupIfEnabled()
    }

    private suspend fun syncBackupIfEnabled(): String? {
        val settings = backupSettingsStore.settings.first()
        val gistId = gistClient.sync(settings, dao.getAll())
        if (settings.gistId.isBlank() && !gistId.isNullOrBlank()) {
            backupSettingsStore.save(settings.copy(gistId = gistId))
        }
        return gistId
    }

    private suspend fun syncBackupSafely(): String? = runCatching {
        syncBackupIfEnabled()?.let { "Gitee Gist 已同步: $it" }
    }.getOrElse { throwable ->
        "本地已保存，Gitee 备份失败: ${throwable.message ?: "未知错误"}"
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "请输入 URL" }
        val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val uri = URI(candidate)
        require(uri.scheme == "http" || uri.scheme == "https") { "只支持 http/https URL" }
        require(!uri.host.isNullOrBlank()) { "URL 缺少域名" }
        return candidate
    }

    private fun extractDomain(url: String): String = URI(url).host.removePrefix("www.")
}

data class SaveResult(
    val domain: String,
    val backupMessage: String?
)