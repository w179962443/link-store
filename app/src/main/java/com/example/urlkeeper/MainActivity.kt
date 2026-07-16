package com.example.urlkeeper

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.urlkeeper.backup.BackupSettings
import com.example.urlkeeper.data.UrlEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UrlKeeperTheme {
                val app = application as UrlKeeperApplication
                val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(app.repository))
                UrlKeeperApp(viewModel, initialSharedText = intent.extractSharedText())
            }
        }
    }
}

private fun Intent?.extractSharedText(): String? = this?.takeIf { it.action == Intent.ACTION_SEND }
    ?.getStringExtra(Intent.EXTRA_TEXT)

private class MainViewModelFactory(private val repository: UrlRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlKeeperApp(viewModel: MainViewModel, initialSharedText: String?) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showBackupDialog by remember { mutableStateOf(false) }
    var consumedSharedText by remember { mutableStateOf(false) }

    LaunchedEffect(initialSharedText) {
        if (!consumedSharedText && !initialSharedText.isNullOrBlank() && state.input.isBlank()) {
            viewModel.onInputChanged(initialSharedText)
            consumedSharedText = true
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.animateScrollToItem(state.entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("URL Keeper", fontWeight = FontWeight.SemiBold)
                        Text("${state.entries.size} 条未导出", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncBackup(state.backupSettings) },
                        enabled = !state.isBusy
                    ) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = "立即同步")
                    }
                    IconButton(onClick = { showBackupDialog = true }) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = "GitHub 备份设置")
                    }
                    IconButton(
                        onClick = viewModel::exportAndClear,
                        enabled = state.entries.isNotEmpty() && !state.isBusy
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = "导出 OneTab 文件")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFAF9F6))
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFFFAF9F6)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
        ) {
            if (state.entries.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        UrlBubble(entry = entry)
                    }
                }
            }

            Divider(color = Color(0xFFE3DED4))
            Composer(
                input = state.input,
                enabled = !state.isBusy,
                onInputChanged = viewModel::onInputChanged,
                onSend = viewModel::sendUrl
            )
        }
    }

    if (showBackupDialog) {
        BackupDialog(
            settings = state.backupSettings,
            isSaving = state.isSavingBackupSettings,
            isSyncing = state.isSyncingBackup,
            onDismiss = { showBackupDialog = false },
            onSave = { settings ->
                viewModel.saveBackupSettings(settings)
                showBackupDialog = false
            }
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("粘贴 URL，然后发送", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("未导出的链接会保存在本机数据库", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6F6A60))
        }
    }
}

@Composable
private fun UrlBubble(entry: UrlEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFDCF3E7),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(entry.domain, fontWeight = FontWeight.SemiBold, color = Color(0xFF164232))
                Spacer(Modifier.height(4.dp))
                Text(entry.url, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1D1C19))
                Spacer(Modifier.height(8.dp))
                Text(
                    TimeFormat.format(Date(entry.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF65756D),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    enabled: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFFFF))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("https://example.com/article") },
            shape = RoundedCornerShape(8.dp)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                focusManager.clearFocus()
                onSend()
            },
            enabled = enabled && input.isNotBlank(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(width = 56.dp, height = 56.dp)
        ) {
            Icon(Icons.Rounded.Send, contentDescription = "发送")
        }
    }
}

@Composable
private fun BackupDialog(
    settings: BackupSettings,
    isSaving: Boolean,
    isSyncing: Boolean,
    onDismiss: () -> Unit,
    onSave: (BackupSettings) -> Unit
) {
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var gistId by remember(settings) { mutableStateOf(settings.gistId) }
    var fileName by remember(settings) { mutableStateOf(settings.fileName) }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub Gist 备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用自动快照")
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    enabled = enabled,
                    label = { Text("GitHub personal access token") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation()
                )
                TextButton(onClick = { showToken = !showToken }, enabled = enabled) {
                    Text(if (showToken) "隐藏 token" else "显示 token")
                }
                OutlinedTextField(
                    value = gistId,
                    onValueChange = { gistId = it },
                    enabled = enabled,
                    label = { Text("Gist ID，可留空自动创建") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    enabled = enabled,
                    label = { Text("备份文件名") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(BackupSettings(enabled, token, gistId, fileName)) },
                enabled = !isSaving && !isSyncing,
                shape = RoundedCornerShape(8.dp)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving && !isSyncing) { Text("取消") }
        }
    )
}

@Composable
private fun UrlKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF24745A),
            secondary = Color(0xFF6F5E2D),
            surface = Color(0xFFFFFFFF),
            background = Color(0xFFFAF9F6),
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

private val TimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
