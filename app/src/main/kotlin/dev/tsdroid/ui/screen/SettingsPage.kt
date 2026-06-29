package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.pm.PackageManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.han.R
import dev.tsdroid.ui.component.SettingsCacheSizeHelper
import dev.tsdroid.ui.component.WallpaperCacheManager
import kotlinx.coroutines.launch

@Composable
fun SettingsPage(
    onNavigateToAbout: () -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val showLinkThumbnails by settingsStore.showLinkThumbnails.collectAsStateWithLifecycle(initialValue = false)
    val autoLoadImages by settingsStore.autoLoadImages.collectAsStateWithLifecycle(initialValue = true)
    val enableFloatingWindow by settingsStore.enableFloatingWindow.collectAsStateWithLifecycle(initialValue = false)
    val animeBackground by settingsStore.animeBackground.collectAsStateWithLifecycle(initialValue = true)
    val noiseSuppression by settingsStore.noiseSuppression.collectAsStateWithLifecycle(initialValue = true)
    val audioGain by settingsStore.audioGain.collectAsStateWithLifecycle(initialValue = 1.0f)

    val languageOptions = listOf(
        "zh" to stringResource(R.string.language_simplified_chinese),
        "en" to stringResource(R.string.language_english),
        "fr" to stringResource(R.string.language_french),
    )
    val selectedLanguageTag by settingsStore.language.collectAsStateWithLifecycle(initialValue = "zh")
    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == selectedLanguageTag }?.second
        ?: stringResource(R.string.language_simplified_chinese)
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }
    val activity = context as? Activity

    pendingLanguageTag?.let { languageTag ->
        val label = languageOptions.firstOrNull { it.first == languageTag }?.second ?: languageTag
        AlertDialog(
            onDismissRequest = { pendingLanguageTag = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.language_change_title)) },
            text = { Text(stringResource(R.string.language_change_message, label)) },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Language
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { languageMenuExpanded = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.language_change_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    text = selectedLanguageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    languageOptions.forEach { (tag, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                pendingLanguageTag = tag
                                languageMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Auto reconnect
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auto_reconnect),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoReconnect,
                onCheckedChange = onAutoReconnectChange,
            )
        }

        // Audio gain
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "${stringResource(R.string.audio_gain)} : ${stringResource(R.string.audio_gain_value, audioGain)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = audioGain,
                onValueChange = { scope.launch { settingsStore.setAudioGain(it) } },
                valueRange = 1.0f..8.0f,
                steps = 13,
            )
        }

        // Show link thumbnails
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_link_thumbnails),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showLinkThumbnails,
                onCheckedChange = { scope.launch { settingsStore.setShowLinkThumbnails(it) } },
            )
        }

        // Auto load images
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auto_load_images),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoLoadImages,
                onCheckedChange = { scope.launch { settingsStore.setAutoLoadImages(it) } },
            )
        }

        // Enable floating window
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.enable_floating_window),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enableFloatingWindow,
                onCheckedChange = { scope.launch { settingsStore.setEnableFloatingWindow(it) } },
            )
        }

        // Anime background
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.anime_background),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = animeBackground,
                onCheckedChange = { scope.launch { settingsStore.setAnimeBackground(it) } },
            )
        }

        // Wallpaper cache settings (only when anime background enabled)
        if (animeBackground) {
            val cacheSizeMB = remember { mutableFloatStateOf(WallpaperCacheManager.getCacheSizeMB()) }
            val cacheCount = remember { mutableIntStateOf(WallpaperCacheManager.getCachedFilesCount()) }
            val maxSize = remember { mutableLongStateOf(SettingsCacheSizeHelper.getMaxCacheSize(context)) }
            var showCacheViewer by remember { mutableStateOf(false) }
            var showClearConfirm by remember { mutableStateOf(false) }

            // Clear cache confirmation dialog
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    title = { Text(stringResource(R.string.wallpaper_clear_cache)) },
                    text = { Text(stringResource(R.string.wallpaper_clear_cache_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            WallpaperCacheManager.clearCache()
                            cacheSizeMB.floatValue = 0f
                            cacheCount.intValue = 0
                            showClearConfirm = false
                        }) {
                            Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            // Cache viewer dialog
            if (showCacheViewer) {
                val cachedFiles = remember { mutableStateListOf(*WallpaperCacheManager.getCachedFiles().toTypedArray()) }
                AlertDialog(
                    onDismissRequest = { showCacheViewer = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    title = {
                        Text("${stringResource(R.string.wallpaper_view_cache)} (${cachedFiles.size})")
                    },
                    text = {
                        if (cachedFiles.isEmpty()) {
                            Text(stringResource(R.string.wallpaper_cache_empty))
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(cachedFiles.size, key = { cachedFiles[it].name }) { index ->
                                    val file = cachedFiles[index]
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(MaterialTheme.shapes.medium)
                                            .clickable {
                                                WallpaperCacheManager.deleteFile(file)
                                                cachedFiles.removeAt(index)
                                                cacheSizeMB.floatValue = WallpaperCacheManager.getCacheSizeMB()
                                                cacheCount.intValue = WallpaperCacheManager.getCachedFilesCount()
                                            },
                                    ) {
                                        AsyncImage(
                                            model = file,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(16.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                    CircleShape
                                                )
                                                .padding(2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCacheViewer = false }) {
                            Text(stringResource(R.string.close))
                        }
                    },
                )
            }

            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.wallpaper_cache_size, String.format("%.1f", cacheSizeMB.floatValue), cacheCount.intValue),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.wallpaper_max_cache, maxSize.longValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = maxSize.longValue.toFloat(),
                    onValueChange = { v ->
                        maxSize.longValue = v.toLong()
                    },
                    onValueChangeFinished = {
                        SettingsCacheSizeHelper.setMaxCacheSize(context, maxSize.longValue)
                    },
                    valueRange = 10f..500f,
                    steps = 48,
                )
            }

            // View cache
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCacheViewer = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_view_cache),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${cacheCount.intValue} ${stringResource(R.string.wallpaper_cache_images)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Clear cache
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearConfirm = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_clear_cache),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Noise suppression
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.noise_suppression),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = noiseSuppression,
                onCheckedChange = { scope.launch { settingsStore.setNoiseSuppression(it) } },
            )
        }

        // About
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToAbout() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.about_software),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))

        // Version info + update check
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateInfo by remember { mutableStateOf<dev.tsdroid.update.UpdateInfo?>(null) }
        var updateError by remember { mutableStateOf<String?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var isLatestVersion by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var downloadError by remember { mutableStateOf<String?>(null) }

        if (showUpdateDialog && updateInfo != null) {
            AlertDialog(
                onDismissRequest = { if (!isDownloading) { showUpdateDialog = false } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text(stringResource(R.string.update_available, updateInfo!!.versionName)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (isDownloading) {
                            Text(
                                text = "${stringResource(R.string.update_downloading)} ${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (downloadError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = downloadError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            val changelog = updateInfo!!.changelog
                            val displayText = changelog.take(2000)
                            Text(
                                text = displayText.ifBlank { stringResource(R.string.update_no_changelog) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (changelog.length > 2000) {
                                Text("...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!isDownloading) {
                        FilledTonalButton(onClick = {
                            isDownloading = true
                            downloadError = null
                            downloadProgress = 0f
                            scope.launch {
                                val success = dev.tsdroid.update.InAppUpdater.downloadAndInstall(
                                    context = context,
                                    downloadUrl = updateInfo!!.downloadUrl,
                                    onProgress = { progress ->
                                        downloadProgress = progress.progress
                                        if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.FAILED) {
                                            downloadError = progress.error
                                            isDownloading = false
                                        } else if (progress.state == dev.tsdroid.update.InAppUpdater.DownloadState.DONE) {
                                            showUpdateDialog = false
                                        }
                                    }
                                )
                                isDownloading = false
                            }
                        }) {
                            Text(stringResource(R.string.update_download))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(stringResource(R.string.update_later))
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        isLatestVersion = false
                        updateInfo = null
                        updateError = null
                        scope.launch {
                            val result = dev.tsdroid.update.UpdateChecker.checkForUpdate(versionName)
                            updateInfo = result.update
                            updateError = result.error
                            if (result.update != null) {
                                showUpdateDialog = true
                            } else if (result.error != null) {
                                // API failed — keep isLatestVersion false, show error via text
                            } else {
                                isLatestVersion = true
                            }
                            isCheckingUpdate = false
                        }
                    }
                }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TS6 Droid v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isCheckingUpdate) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else if (updateInfo != null) {
                Text(
                    text = stringResource(R.string.update_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (updateError != null) {
                Text(
                    text = updateError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (isLatestVersion) {
                Text(
                    text = stringResource(R.string.update_already_latest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.update_check),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
