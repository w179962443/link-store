package com.example.urlkeeper.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.urlkeeper.data.UrlEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OneTabExporter(private val context: Context) {
    fun export(entries: List<UrlEntry>): ExportResult {
        require(entries.isNotEmpty()) { "没有可导出的 URL" }

        val fileName = "onetab-${Timestamp.format(Date())}.txt"
        val bytes = entries.toOneTabText().toByteArray(Charsets.UTF_8)
        val archiveFile = writeArchiveCopy(fileName, bytes)
        val publicLocation = writeDownloadsCopy(fileName, bytes)
        return ExportResult(fileName, publicLocation, archiveFile.absolutePath)
    }

    private fun writeArchiveCopy(fileName: String, bytes: ByteArray): File {
        val archiveDir = File(context.filesDir, "export-archive")
        archiveDir.mkdirs()
        val archiveFile = File(archiveDir, fileName)
        archiveFile.outputStream().use { output ->
            output.write(bytes)
            output.fdSyncIfPossible()
        }
        return archiveFile
    }

    private fun writeDownloadsCopy(fileName: String, bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UrlChatExports")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建导出文件")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                    output.fdSyncIfPossible()
                } ?: error("无法写入导出文件")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/UrlChatExports/$fileName"
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UrlChatExports")
            directory.mkdirs()
            val file = File(directory, fileName)
            file.outputStream().use { output ->
                output.write(bytes)
                output.fdSyncIfPossible()
            }
            file.absolutePath
        }
    }

    private fun List<UrlEntry>.toOneTabText(): String = joinToString(separator = "\n", postfix = "\n") { entry ->
        "${entry.domain} | ${entry.url}"
    }

    private fun java.io.OutputStream.fdSyncIfPossible() {
        if (this is java.io.FileOutputStream) fd.sync()
    }

    private companion object {
        val Timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

data class ExportResult(
    val fileName: String,
    val publicLocation: String,
    val archiveLocation: String
)
