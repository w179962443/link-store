package com.example.urlkeeper.backup

data class BackupSettings(
    val enabled: Boolean = false,
    val token: String = "",
    val gistId: String = "",
    val fileName: String = "url-keeper-onetab.txt"
)
