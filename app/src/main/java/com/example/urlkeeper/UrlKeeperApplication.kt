package com.example.urlkeeper

import android.app.Application
import com.example.urlkeeper.backup.BackupSettingsStore
import com.example.urlkeeper.backup.GiteeGistClient
import com.example.urlkeeper.data.AppDatabase
import com.example.urlkeeper.export.OneTabExporter

class UrlKeeperApplication : Application() {
    val repository: UrlRepository by lazy {
        val database = AppDatabase.create(this)
        UrlRepository(
            dao = database.urlDao(),
            exporter = OneTabExporter(this),
            backupSettingsStore = BackupSettingsStore(this),
            gistClient = GiteeGistClient()
        )
    }
}
