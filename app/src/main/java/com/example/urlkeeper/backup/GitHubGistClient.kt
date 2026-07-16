package com.example.urlkeeper.backup

import com.example.urlkeeper.data.UrlEntry
import java.io.IOException
import java.net.URI
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GitHubGistClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun read(settings: BackupSettings): RemoteBackupSnapshot? {
        if (!settings.enabled || settings.token.isBlank()) return null

        val gistId = normalizeGistId(settings.gistId)
        if (gistId.isBlank()) return null

        val request = requestBuilder("https://api.github.com/gists/$gistId", settings.token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwGitHubError("GitHub Gist 拉取失败", response.code, responseBody)

            val gist = JSONObject(responseBody)
            val files = gist.optJSONObject("files") ?: JSONObject()
            val resolvedFileName = resolveFileName(settings.fileName, files)
            val file = files.optJSONObject(resolvedFileName)
            val content = when {
                file == null -> ""
                file.optBoolean("truncated") -> loadRawContent(file.optString("raw_url"), settings.token)
                file.has("content") -> file.optString("content")
                else -> loadRawContent(file.optString("raw_url"), settings.token)
            }

            return RemoteBackupSnapshot(
                gistId = gist.getString("id"),
                fileName = resolvedFileName,
                entries = parseEntries(content)
            )
        }
    }

    fun write(settings: BackupSettings, entries: List<UrlEntry>, existingFileName: String? = null): String {
        require(settings.token.isNotBlank()) { "GitHub token 不能为空" }

        val gistId = normalizeGistId(settings.gistId)
        val content = entries.toBackupContent()
        return if (gistId.isBlank()) {
            createGist(settings, content)
        } else {
            updateGist(settings, gistId, content, existingFileName)
            gistId
        }
    }

    fun normalizeGistId(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""

        return runCatching {
            val uri = URI(trimmed)
            val candidate = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
            candidate.ifBlank { trimmed.substringAfterLast('/') }
        }.getOrDefault(trimmed.substringAfterLast('/'))
    }

    private fun createGist(settings: BackupSettings, content: String): String {
        val body = JSONObject()
            .put("description", "URL Keeper backup")
            .put("public", false)
            .put("files", filesPayload(settings.fileName, content))
            .toString()
            .toRequestBody(Json)

        val request = requestBuilder("https://api.github.com/gists", settings.token)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwGitHubError("GitHub 创建 Gist 失败", response.code, responseBody)
            return JSONObject(responseBody).getString("id")
        }
    }

    private fun updateGist(settings: BackupSettings, gistId: String, content: String, existingFileName: String?) {
        val body = JSONObject()
            .put("files", filesPayload(settings.fileName, content, existingFileName))
            .toString()
            .toRequestBody(Json)

        val request = requestBuilder("https://api.github.com/gists/$gistId", settings.token)
            .patch(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwGitHubError("GitHub 更新 Gist 失败", response.code, responseBody)
        }
    }

    private fun requestBuilder(url: String, token: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer ${token.trim()}")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun filesPayload(fileName: String, content: String, existingFileName: String? = null): JSONObject {
        val resolvedFileName = fileName.ifBlank { DefaultFileName }
        return JSONObject()
            .put(
                resolvedFileName,
                JSONObject().put("content", content)
            )
            .also { files ->
                val oldFileName = existingFileName?.trim().orEmpty()
                if (oldFileName.isNotBlank() && oldFileName != resolvedFileName) {
                    files.put(oldFileName, JSONObject.NULL)
                }
            }
    }

    private fun resolveFileName(preferredFileName: String, files: JSONObject): String {
        val normalizedPreferred = preferredFileName.ifBlank { DefaultFileName }
        if (files.has(normalizedPreferred)) return normalizedPreferred
        return files.keys().asSequence().firstOrNull().orEmpty().ifBlank { normalizedPreferred }
    }

    private fun loadRawContent(rawUrl: String, token: String): String {
        if (rawUrl.isBlank()) return ""

        val request = requestBuilder(rawUrl, token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwGitHubError("GitHub Gist 文件读取失败", response.code, responseBody)
            return responseBody
        }
    }

    private fun parseEntries(content: String): List<UrlEntry> {
        if (content.isBlank()) return emptyList()

        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val separatorIndex = line.indexOf('\t')
                require(separatorIndex > 0) { "GitHub Gist 内容格式无效" }

                val timestamp = line.substring(0, separatorIndex)
                val url = line.substring(separatorIndex + 1).trim()
                require(url.isNotBlank()) { "GitHub Gist 内容格式无效" }

                UrlEntry(
                    url = url,
                    domain = extractDomain(url),
                    createdAt = Instant.parse(timestamp).toEpochMilli()
                )
            }
            .toList()
    }

    private fun List<UrlEntry>.toBackupContent(): String = sortedBy { it.createdAt }
        .joinToString(separator = "\n") { entry ->
            "${Instant.ofEpochMilli(entry.createdAt).toString()}\t${entry.url}"
        }

    private fun extractDomain(url: String): String = URI(url).host.orEmpty().removePrefix("www.")

    private fun formatGitHubError(prefix: String, code: Int, responseBody: String): String {
        val details = runCatching {
            val json = JSONObject(responseBody)
            buildString {
                val message = json.optString("message")
                if (message.isNotBlank()) append(message)
                val errors = json.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val items = (0 until errors.length()).mapNotNull { index ->
                        val item = errors.optJSONObject(index) ?: return@mapNotNull null
                        listOfNotNull(
                            item.optString("resource").takeIf { it.isNotBlank() },
                            item.optString("field").takeIf { it.isNotBlank() },
                            item.optString("code").takeIf { it.isNotBlank() },
                            item.optString("message").takeIf { it.isNotBlank() }
                        ).joinToString(": ").takeIf { it.isNotBlank() }
                    }
                    if (items.isNotEmpty()) {
                        if (isNotEmpty()) append(" | ")
                        append(items.joinToString("; "))
                    }
                }
            }.trim()
        }.getOrDefault(responseBody.trim())

        return if (details.isBlank()) "$prefix: $code" else "$prefix: $code, $details"
    }

    private fun throwGitHubError(prefix: String, code: Int, responseBody: String): Nothing {
        throw GitHubSyncException(
            message = formatGitHubError(prefix, code, responseBody),
            retryable = code == 429 || code >= 500,
            cause = null
        )
    }

    private companion object {
        val Json = "application/json; charset=utf-8".toMediaType()
        const val DefaultFileName = "url-keeper-onetab.txt"
    }
}

class GitHubSyncException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : IOException(message, cause)

data class RemoteBackupSnapshot(
    val gistId: String,
    val fileName: String,
    val entries: List<UrlEntry>
)