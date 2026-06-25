package dev.tsdroid.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.tsdroid.data.SettingsStore
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.han.R
import dev.tslib.ConnectionState
import dev.tsdroid.ui.component.AnimeBackground
import dev.tsdroid.ui.component.ChannelTree
import dev.tsdroid.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: ConnectionViewModel = viewModel(),
) {
    val address by viewModel.address.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val password by viewModel.password.collectAsState()
    val channel by viewModel.channel.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkIcons by viewModel.bookmarkIcons.collectAsState()
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val editingIndex by viewModel.editingIndex.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val error by viewModel.error.collectAsState()
    val browsedChannels by viewModel.browsedChannels.collectAsState()
    val isBrowsing by viewModel.isBrowsing.collectAsState()
    val showChannelPicker by viewModel.showChannelPicker.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmIndex by remember { mutableStateOf<Int?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val isConnecting = connectionState == ConnectionState.CONNECTING
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val settingsStore = remember { SettingsStore(context) }
    val animeBackground by settingsStore.animeBackground.collectAsState(initial = true)
    val languageOptions = listOf(
        "zh" to stringResource(R.string.language_simplified_chinese),
        "en" to stringResource(R.string.language_english),
        "fr" to stringResource(R.string.language_french),
    )
    val selectedLanguageTag by settingsStore.language.collectAsState(initial = "zh")
    val selectedLanguage = languageOptions.firstOrNull { it.first == selectedLanguageTag }?.second
        ?: stringResource(R.string.language_simplified_chinese)
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }
    var languageMenuExpanded by rememberSaveable { mutableStateOf(false) }

    // Request permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results ignored, checked at usage time */ }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
        viewModel.resumeExistingConnection(onConnected)
    }

    // Auto-reconnect on launch
    LaunchedEffect(autoReconnect) {
        if (autoReconnect) {
            viewModel.tryAutoReconnect(onConnected)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = {
                    Box {
                        TextButton(onClick = { languageMenuExpanded = true }) {
                            Text(selectedLanguage)
                        }
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false },
                        ) {
                            languageOptions.forEach { (languageTag, languageLabel) ->
                                DropdownMenuItem(
                                    text = { Text(languageLabel) },
                                    onClick = {
                                        pendingLanguageTag = languageTag
                                        languageMenuExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_software)) },
                                onClick = {
                                    languageMenuExpanded = false
                                    onNavigateToAbout()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.anime_background)) },
                                onClick = {
                                    languageMenuExpanded = false
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = animeBackground,
                                        onCheckedChange = {
                                            kotlinx.coroutines.MainScope().launch {
                                                settingsStore.setAnimeBackground(it)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showBottomSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.manual_connection))
            }
        },
    ) { padding ->
        pendingLanguageTag?.let { languageTag ->
            val languageLabel = languageOptions.firstOrNull { it.first == languageTag }?.second ?: languageTag
            AlertDialog(
                onDismissRequest = { pendingLanguageTag = null },
                title = { Text(stringResource(R.string.language_change_title)) },
                text = { Text(stringResource(R.string.language_change_message, languageLabel)) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            settingsStore.setLanguage(languageTag)
                            activity?.recreate()
                        }
                        pendingLanguageTag = null
                    }) {
                        Text(stringResource(R.string.restart))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingLanguageTag = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimeBackground(enabled = animeBackground)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                // Auto-reconnect switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.auto_reconnect),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { viewModel.setAutoReconnect(it) },
                    )
                }

                // Bookmarks section
                Text(
                    stringResource(R.string.bookmarks),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (bookmarks.isEmpty()) {
                    Text(
                        stringResource(R.string.no_bookmarks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    bookmarks.forEachIndexed { index, bookmark ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val icon = if (bookmark.iconId != 0L) bookmarkIcons[bookmark.iconId] else null
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        bookmark.serverName ?: bookmark.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        bookmark.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.connectBookmark(bookmark, onConnected) },
                                    enabled = !isConnecting,
                                ) {
                                    Text(stringResource(R.string.connect))
                                }
                                Box {
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = null,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.edit)) },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.editBookmark(bookmark, index)
                                                showBottomSheet = true
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.remove)) },
                                            onClick = {
                                                menuExpanded = false
                                                deleteConfirmIndex = index
                                            },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // Delete confirmation dialog
        deleteConfirmIndex?.let { idx ->
            val bookmarkName = bookmarks.getOrNull(idx)?.let { it.serverName ?: it.name } ?: ""
            AlertDialog(
                onDismissRequest = { deleteConfirmIndex = null },
                title = { Text(stringResource(R.string.remove)) },
                text = { Text(stringResource(R.string.confirm_delete, bookmarkName)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeBookmark(idx)
                        deleteConfirmIndex = null
                    }) {
                        Text(stringResource(R.string.remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmIndex = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Channel picker dialog
        if (showChannelPicker) {
            Dialog(onDismissRequest = { viewModel.showChannelPicker.value = false }) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            stringResource(R.string.select_channel),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(16.dp))
                        ChannelTree(
                            channels = browsedChannels,
                            users = emptyList(),
                            onChannelClick = { viewModel.selectChannel(it) },
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(
                            onClick = { viewModel.showChannelPicker.value = false },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }

        // Manual connection bottom sheet
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showBottomSheet = false
                    viewModel.cancelEdit()
                },
                sheetState = sheetState,
            ) {
                val isEditing = editingIndex >= 0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(if (isEditing) R.string.edit_bookmark else R.string.manual_connection),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { viewModel.address.value = it },
                        label = { Text(stringResource(R.string.server_address)) },
                        placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        enabled = !isConnecting,
                    )

                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { viewModel.nickname.value = it },
                        label = { Text(stringResource(R.string.nickname)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        enabled = !isConnecting,
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { viewModel.password.value = it },
                        label = { Text(stringResource(R.string.password_optional)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        enabled = !isConnecting,
                        visualTransformation = PasswordVisualTransformation(),
                    )

                    OutlinedTextField(
                        value = channel,
                        onValueChange = { viewModel.channel.value = it },
                        label = { Text(stringResource(R.string.channel_optional)) },
                        placeholder = { Text(stringResource(R.string.default_channel)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        enabled = !isConnecting,
                        trailingIcon = {
                            if (isBrowsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(24.dp).width(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(onClick = { viewModel.browseChannels() }, enabled = !isConnecting) {
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.browse_channels))
                                }
                            }
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    showBottomSheet = false
                                }
                                viewModel.connect(onConnected)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isConnecting,
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp).width(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(if (isConnecting) R.string.connecting else R.string.connect))
                        }

                        FilledTonalButton(onClick = {
                            viewModel.saveBookmark()
                            scope.launch {
                                sheetState.hide()
                                showBottomSheet = false
                            }
                        }) {
                            Icon(Icons.Default.Star, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (isEditing) R.string.save else R.string.add_bookmark))
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
