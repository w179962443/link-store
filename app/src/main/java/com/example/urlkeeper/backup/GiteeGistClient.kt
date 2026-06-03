package com.example.urlkeeper.backup

import com.example.urlkeeper.data.UrlEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GiteeGistClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun sync(settings: BackupSettings, entries: List<UrlEntry>): String? {
        if (!settings.enabled || settings.token.isBlank()) return null

        val content = entries.toOneTabContent()
        return if (settings.gistId.isBlank()) {
            createGist(settings, content)
        } else {
            updateGist(settings, content)
            settings.gistId
        }
    }

    private fun createGist(settings: BackupSettings, content: String): String {
        val body = JSONObject()
            .put("access_token", settings.token)
            .put("description", "URL Keeper backup")
            .put("public", false)
            .put("files", filesPayload(settings.fileName, content))
            .toString()
            .toRequestBody(Json)

        val request = Request.Builder()
            .url("https://gitee.com/api/v5/gists")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Gitee 创建 Gist 失败: ${response.code}")
            return JSONObject(responseBody).getString("id")
        }
    }

    private fun updateGist(settings: BackupSettings, content: String) {
        val body = JSONObject()
            .put("access_token", settings.token)
            .put("files", filesPayload(settings.fileName, content))
            .toString()
            .toRequestBody(Json)

        val request = Request.Builder()
            .url("https://gitee.com/api/v5/gists/${settings.gistId}")
            .patch(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Gitee 更新 Gist 失败: ${response.code}")
        }
    }

    private fun filesPayload(fileName: String, content: String): JSONObject = JSONObject()
        .put(fileName.ifBlank { "url-keeper-onetab.txt" }, JSONObject().put("content", content))

    private fun List<UrlEntry>.toOneTabContent(): String = joinToString(separator = "\n") { entry ->
        "${entry.domain} | ${entry.url}"
    }

    private companion object {
        val Json = "application/json; charset=utf-8".toMediaType()
    }
}
