package com.example.urlkeeper.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UrlDao {
    @Query("SELECT * FROM url_entries ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<UrlEntry>>

    @Query("SELECT * FROM url_entries ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<UrlEntry>

    @Insert
    suspend fun insert(entry: UrlEntry): Long

    @Delete
    suspend fun delete(entries: List<UrlEntry>)
}
