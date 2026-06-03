package com.example.urlkeeper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "url_entries")
data class UrlEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val domain: String,
    val createdAt: Long
)
