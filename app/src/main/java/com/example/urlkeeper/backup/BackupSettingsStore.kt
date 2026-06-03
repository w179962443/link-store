package com.example.urlkeeper.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backupDataStore by preferencesDataStore("backup_settings")

class BackupSettingsStore(private val context: Context) {
    val settings: Flow<BackupSettings> = context.backupDataStore.data.map { preferences ->
        BackupSettings(
            enabled = preferences[Keys.enabled] ?: false,
            token = preferences[Keys.token].orEmpty(),
            gistId = preferences[Keys.gistId].orEmpty(),
            fileName = preferences[Keys.fileName] ?: "url-keeper-onetab.txt"
        )
    }

    suspend fun save(settings: BackupSettings) {
        context.backupDataStore.edit { preferences ->
            preferences[Keys.enabled] = settings.enabled
            preferences[Keys.token] = settings.token.trim()
            preferences[Keys.gistId] = settings.gistId.trim()
            preferences[Keys.fileName] = settings.fileName.trim().ifBlank { "url-keeper-onetab.txt" }
        }
    }

    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val token = stringPreferencesKey("token")
        val gistId = stringPreferencesKey("gist_id")
        val fileName = stringPreferencesKey("file_name")
    }
}
