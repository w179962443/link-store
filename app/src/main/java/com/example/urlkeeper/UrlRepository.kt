package com.example.urlkeeper

import com.example.urlkeeper.backup.BackupSettings
import com.example.urlkeeper.backup.BackupSettingsStore
import com.example.urlkeeper.backup.GitHubGistClient
import com.example.urlkeeper.data.UrlDao
import com.example.urlkeeper.data.UrlEntry
import com.example.urlkeeper.export.ExportResult
import com.example.urlkeeper.export.OneTabExporter
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UrlRepository(
    private val dao: UrlDao,
    private val exporter: OneTabExporter,
    private val backupSettingsStore: BackupSettingsStore,
    private val gistClient: GitHubGistClient
) {
    private val syncMutex = Mutex()

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

    suspend fun saveBackupSettings(settings: BackupSettings) = withContext(Dispatchers.IO) {
        backupSettingsStore.save(settings)
    }

    suspend fun syncBackupNow(settingsOverride: BackupSettings? = null): String = withContext(Dispatchers.IO) {
        settingsOverride?.let { backupSettingsStore.save(it) }

        val settings = backupSettingsStore.settings.first()
        require(settings.enabled) { "请先启用 GitHub 备份" }
        require(settings.token.isNotBlank()) { "请先填写 GitHub token" }

        syncBackupIfEnabled(settings)?.let { "GitHub Gist 已同步: $it" } ?: "没有需要同步的内容"
    }

    private suspend fun syncBackupIfEnabled(settings: BackupSettings? = null): String? = syncMutex.withLock {
        val resolvedSettings = settings ?: backupSettingsStore.settings.first()
        if (!resolvedSettings.enabled || resolvedSettings.token.isBlank()) return null

        val localEntries = dao.getAll()
        val remoteSnapshot = gistClient.read(resolvedSettings)
        val mergedEntries = mergeEntries(localEntries, remoteSnapshot?.entries.orEmpty())

        if (remoteSnapshot == null && mergedEntries.isEmpty()) return null

        insertMissingLocalEntries(localEntries, mergedEntries)

        val gistId = gistClient.write(
            settings = resolvedSettings.copy(gistId = remoteSnapshot?.gistId ?: resolvedSettings.gistId),
            entries = mergedEntries,
            existingFileName = remoteSnapshot?.fileName
        )

        if (resolvedSettings.gistId.trim() != gistId) {
            backupSettingsStore.save(resolvedSettings.copy(gistId = gistId))
        }
        return gistId
    }

    private suspend fun syncBackupSafely(): String? = runCatching {
        syncBackupIfEnabled()?.let { "GitHub Gist 已同步: $it" }
    }.getOrElse { throwable ->
        "本地已保存，GitHub 备份失败: ${throwable.message ?: "未知错误"}"
    }

    private suspend fun insertMissingLocalEntries(localEntries: List<UrlEntry>, mergedEntries: List<UrlEntry>) {
        val localKeys = localEntries.mapTo(mutableSetOf(), ::entryKey)
        mergedEntries.forEach { entry ->
            if (entryKey(entry) !in localKeys) {
                dao.insert(entry.copy(id = 0))
            }
        }
    }

    private fun mergeEntries(localEntries: List<UrlEntry>, remoteEntries: List<UrlEntry>): List<UrlEntry> {
        val primary = if (localEntries.size >= remoteEntries.size) localEntries else remoteEntries
        val secondary = if (primary === localEntries) remoteEntries else localEntries
        val merged = LinkedHashMap<Long, UrlEntry>()

        primary.forEach { entry ->
            val key = entryKey(entry)
            if (key !in merged) merged[key] = entry
        }
        secondary.forEach { entry ->
            val key = entryKey(entry)
            if (key !in merged) merged[key] = entry
        }

        return merged.values.sortedWith(compareBy<UrlEntry>({ it.createdAt }, { it.url }))
    }

    private fun entryKey(entry: UrlEntry): Long = entry.createdAt / 1_000L

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