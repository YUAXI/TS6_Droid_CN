package dev.tsdroid.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.FileCache
import dev.tsdroid.bridge.IconCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.bridge.TsFileEntry
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.MessageStore
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.service.TsConnectionService
import dev.tslib.Channel
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileAttachment(
    val fileName: String,
    val fileSize: Long,
    val fileId: String,
    val isImage: Boolean,
    val channelId: Long = 0L,
)

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean = false,
    val isPrivate: Boolean = false,
    val senderId: Int = 0,
    val fileAttachment: FileAttachment? = null,
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data class Done(val image: ImageBitmap?, val fileUri: android.net.Uri? = null) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ServerViewModel"
    }

    private val messageStore = MessageStore(application)
    private val bookmarkStore = BookmarkStore(application)
    private val settingsStore = SettingsStore(application)
    private val iconCache = IconCache(application.cacheDir)
    private val avatarCache = AvatarCache(application.cacheDir)
    private val fileCache = FileCache(application)
    private var serverAddress: String? = null
    private var saveJob: Job? = null
    // In-memory cache: avoids re-reading from disk + re-decoding for the same image
    private val downloadCache = mutableMapOf<String, StateFlow<DownloadState>>()

    private var tsClient: TsClient? = null
    private var audioBridge: AudioBridge? = null
    private var connectionService: TsConnectionService? = null

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    // Raw users from the server (isTalking always false in snapshots)
    private val _rawUsers = MutableStateFlow<List<User>>(emptyList())

    // Set of currently talking user IDs (tracked via talk_status events)
    private val _talkingUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val talkingUserIds: StateFlow<Set<Int>> = _talkingUserIds.asStateFlow()
    
    // Track local mic state for local user talking highlight
    private val _isLocalTalking = MutableStateFlow(false)

    private val _mutedUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val mutedUserIds: StateFlow<Set<Int>> = _mutedUserIds.asStateFlow()

    // Users with isTalking patched from talk status events
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _channelIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val channelIcons: StateFlow<Map<Long, ImageBitmap>> = _channelIcons.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()

    private val _channelMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val channelMessages: StateFlow<List<ChatMessage>> = _channelMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<Map<Int, List<ChatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<Int, List<ChatMessage>>> = _privateMessages.asStateFlow()

    // Separate PTT mode from actual mute state
    private val _isPttMode = MutableStateFlow(true) // true = PTT, false = voice activity
    val isPttMode: StateFlow<Boolean> = _isPttMode.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    val isLocalVoiceActive: StateFlow<Boolean> get() = audioBridge?.isLocalVoiceActive ?: MutableStateFlow(false)

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    // Unread message counters
    private val _unreadChannel = MutableStateFlow(0)
    val unreadChannel: StateFlow<Int> = _unreadChannel.asStateFlow()
    private val _unreadPrivate = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val unreadPrivate: StateFlow<Map<Int, Int>> = _unreadPrivate.asStateFlow()

    val audioGain: StateFlow<Float> = settingsStore.audioGain
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val showLinkThumbnails: StateFlow<Boolean> = settingsStore.showLinkThumbnails
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoLoadImages: StateFlow<Boolean> = settingsStore.autoLoadImages
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val enableFloatingWindow: StateFlow<Boolean> = settingsStore.enableFloatingWindow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // File manager state
    private val _fileManagerOpen = MutableStateFlow(false)
    val fileManagerOpen: StateFlow<Boolean> = _fileManagerOpen.asStateFlow()

    private val _fileList = MutableStateFlow<List<TsFileEntry>>(emptyList())
    val fileList: StateFlow<List<TsFileEntry>> = _fileList.asStateFlow()

    private val _currentFilePath = MutableStateFlow("/")
    val currentFilePath: StateFlow<String> = _currentFilePath.asStateFlow()

    private val _fileManagerLoading = MutableStateFlow(false)
    val fileManagerLoading: StateFlow<Boolean> = _fileManagerLoading.asStateFlow()

    /** Permission hints for the current channel (bitflags from Channel.PERM_*) */
    val currentChannelPermissions: StateFlow<Long> = combine(_channels, _rawUsers) { channels, users ->
        val myId = tsClient?.clientId ?: return@combine 0L
        val channelId = users.find { it.id == myId }?.channelId ?: return@combine 0L
        channels.find { it.id == channelId }?.permissionHints ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // Track which channels we've already queried permissions for
    private val queriedPermChannels = mutableSetOf<Long>()

    // Track chat visibility to avoid incrementing unread for visible tab
    var isChatOpen = false
    var activeChatTab = 0
    var activePmUserId: Int? = null

    private var bound = false

    init {
        // Combine raw users + talking set to produce patched user list
        viewModelScope.launch {
            combine(_rawUsers, _talkingUserIds, _isLocalTalking) { users, talking, localTalking ->
                val myId = tsClient?.clientId
                
                users.map { user ->
                    val isLocallyTalking = (user.id == myId && localTalking)
                    val isRemoteTalking = user.id in talking
                    val shouldBeTalking = isLocallyTalking || isRemoteTalking
                    
                    if (shouldBeTalking && !user.isTalking) user.withTalking(true)
                    else if (!shouldBeTalking && user.isTalking) user.withTalking(false)
                    else user
                }
            }.collect { _users.value = it }
        }
        // When current channel changes, query permissions if not already known
        viewModelScope.launch {
            combine(_channels, _rawUsers) { channels, users ->
                val myId = tsClient?.clientId
                Log.d(TAG, "PermCheck: tsClient=${tsClient != null}, clientId=$myId, channels=${channels.size}, users=${users.size}")
                if (myId == null) return@combine null
                val channelId = users.find { it.id == myId }?.channelId
                if (channelId == null) { Log.d(TAG, "PermCheck: user not found in users list"); return@combine null }
                val hints = channels.find { it.id == channelId }?.permissionHints ?: 0L
                Log.d(TAG, "PermCheck: channelId=$channelId, hints=$hints")
                channelId to hints
            }.collect { pair ->
                val (channelId, hints) = pair ?: return@collect
                Log.d(TAG, "PermCheck collect: channelId=$channelId, hints=$hints, queried=${queriedPermChannels}")
                if (hints == 0L && channelId !in queriedPermChannels) {
                    queriedPermChannels.add(channelId)
                    Log.i(TAG, "No permission hints for channel $channelId, querying permoverview...")
                    try {
                        tsClient?.queryChannelPermissions(channelId)
                    } catch (e: Exception) {
                        Log.e(TAG, "queryChannelPermissions failed", e)
                    }
                }
            }
        }
    }

    fun bindToService() {
        if (bound) return
        
        viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            val service = TsConnectionService.instance
            if (service == null) {
                Log.e(TAG, "Failed to bind to TsConnectionService: instance is null")
                return@launch
            }

            tsClient = service.tsClient
            audioBridge = service.audioBridge
            audioBridge?.setMutedUserIds(_mutedUserIds.value)
            connectionService = service
            queriedPermChannels.clear()

            viewModelScope.launch {
                service.tsClient.channels.collect { channels ->
                    Log.d(TAG, "Channels updated: ${channels.size}")
                    _channels.value = channels
                    loadChannelIcons(channels)
                }
            }
            viewModelScope.launch {
                service.tsClient.users.collect {
                    _rawUsers.value = it
                    loadAvatars(it)
                }
            }
            viewModelScope.launch {
                var bookmarkUpdated = false
                service.tsClient.serverInfo.collect { info ->
                    _serverInfo.value = info
                    if (info != null && !bookmarkUpdated) {
                        bookmarkUpdated = true
                        val addr = serverAddress ?: service.tsClient.serverAddress ?: ""
                        if (addr.isNotEmpty()) {
                            bookmarkStore.updateServerInfo(addr, info.name, info.iconId)
                            // Download server icon if needed
                            if (info.iconId != 0L) {
                                iconCache.loadIcon(info.iconId, service.tsClient)
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                service.tsClient.state.collect { _connectionState.value = it }
            }
            viewModelScope.launch {
                service.tsClient.events.collect { handleEvent(it) }
            }
            viewModelScope.launch {
                service.tsClient.commandErrors.collect { message ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // Load persisted messages
            viewModelScope.launch {
                val addr = service.tsClient.serverAddress
                if (!addr.isNullOrEmpty()) {
                    serverAddress = addr
                    val (channelMsgs, privateMsgs) = messageStore.load(addr)
                    if (channelMsgs.isNotEmpty()) _channelMessages.value = channelMsgs.map { migrateMessage(it) }
                    if (privateMsgs.isNotEmpty()) _privateMessages.value = privateMsgs.mapValues { (_, msgs) -> msgs.map { migrateMessage(it) } }
                }
            }
            // Start audio capture if not already running
            if (!service.audioBridge.isCapturing.value) {
                service.audioBridge.startCapture(viewModelScope)
            }
            // Apply persisted audio gain
            service.audioBridge.gainFactor = audioGain.value
            // Observe audio state for local talking status
            viewModelScope.launch {
                service.audioBridge.isLocalVoiceActive.collect { _isLocalTalking.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.isOutputMuted.collect { _isOutputMuted.value = it }
            }

            // Start event loop (guarded by AtomicBoolean — safe if already running)
            service.tsClient.startEventLoop()
            
            bound = true
        }
    }

    private fun loadChannelIcons(channels: List<Channel>) {
        val client = tsClient ?: return
        val iconIds = channels.mapNotNull { ch ->
            if (ch.iconId != 0L) ch.iconId else null
        }.distinct()

        for (iconId in iconIds) {
            if (iconCache.getIcon(iconId) != null) {
                // Already in memory, make sure it's in the flow
                continue
            }
            viewModelScope.launch {
                iconCache.loadIcon(iconId, client)
                val icon = iconCache.getIcon(iconId)
                if (icon != null) {
                    _channelIcons.value = _channelIcons.value + (iconId to icon)
                }
            }
        }
        // Also emit icons already cached in memory
        val cached = mutableMapOf<Long, ImageBitmap>()
        for (iconId in iconIds) {
            iconCache.getIcon(iconId)?.let { cached[iconId] = it }
        }
        if (cached.isNotEmpty() && cached != _channelIcons.value) {
            _channelIcons.value = _channelIcons.value + cached
        }
    }

    private fun loadAvatars(users: List<User>) {
        val client = tsClient ?: return
        for (user in users) {
            val uid = user.uid ?: continue
            if (uid.isEmpty()) continue
            if (user.isQuery) continue
            if (user.avatarId.isNullOrEmpty()) continue  // no avatar on server
            if (avatarCache.getAvatar(uid) != null) continue
            if (avatarCache.hasNoAvatar(uid)) continue
            Log.d(TAG, "loadAvatars: launching download for ${user.nickname}")
            viewModelScope.launch {
                avatarCache.loadAvatar(uid, client)
                val avatar = avatarCache.getAvatar(uid)
                if (avatar != null) {
                    _avatars.value = _avatars.value + (uid to avatar)
                    Log.i(TAG, "loadAvatars: avatar loaded for ${user.nickname}")
                }
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event.type) {
            "talk_status_start" -> {
                val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                _talkingUserIds.value = _talkingUserIds.value + userId
            }
            "talk_status_stop" -> {
                val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                _talkingUserIds.value = _talkingUserIds.value - userId
            }
            "text_message" -> {
                val target = event.data["target"] as? String ?: ""
                val sender = event.data["sender_name"] as? String ?: getApplication<Application>().getString(R.string.unknown_sender)
                val senderId = (event.data["sender_id"] as? Number)?.toInt()
                val text = event.data["message"] as? String ?: ""

                Log.d(TAG, "Text message: target=$target sender=$sender text=${text.take(50)}")

                // Skip our own messages — we already added them locally
                val myId = tsClient?.clientId
                if (myId != null && senderId == myId) return

                val attachment = parseFileAttachment(text)
                val displayText = if (attachment != null) attachment.fileName else text

                when (target) {
                    "private" -> {
                        val id = senderId ?: return
                        val msg = ChatMessage(sender = sender, text = displayText, isPrivate = true, senderId = id, fileAttachment = attachment)
                        val current = _privateMessages.value.toMutableMap()
                        current[id] = (current[id] ?: emptyList()) + msg
                        _privateMessages.value = current
                        scheduleSave()
                        // Only increment if chat is closed or not on this user's PM
                        if (!isChatOpen || activeChatTab != 1 || activePmUserId != id) {
                            val unread = _unreadPrivate.value.toMutableMap()
                            unread[id] = (unread[id] ?: 0) + 1
                            _unreadPrivate.value = unread
                        }
                    }
                    "channel" -> {
                        val msg = ChatMessage(sender = sender, text = displayText, fileAttachment = attachment)
                        _channelMessages.value = _channelMessages.value + msg
                        scheduleSave()
                        // Only increment if chat is closed or not on channel tab
                        if (!isChatOpen || activeChatTab != 0) {
                            _unreadChannel.value++
                        }
                    }
                }
            }
        }
    }

    private fun parseFileAttachment(text: String): FileAttachment? {
        // TS5 MyTeamSpeak JSON format
        if (text.startsWith("{\"msg_type\":")) {
            return try {
                val json = org.json.JSONObject(text)
                if (json.optString("msg_type") != "ts.file.myts") return null
                val fileName = json.optString("file_name", "")
                val fileSize = json.optLong("file_size", 0)
                val fileId = json.optString("file_id", "")
                if (fileName.isEmpty() || fileId.isEmpty()) return null
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                FileAttachment(fileName, fileSize, fileId, isImage)
            } catch (_: Exception) { null }
        }
        // TS3 file transfer URL format: ts3file://host?port=...&channel=...&filename=...&size=...
        val ts3Start = text.indexOf("ts3file://")
        if (ts3Start >= 0) {
            return try {
                // Extract the ts3file:// URL (until whitespace or end of string)
                val urlStr = text.substring(ts3Start).takeWhile { !it.isWhitespace() }
                val uri = android.net.Uri.parse(urlStr)
                val fileName = uri.getQueryParameter("filename") ?: return null
                val fileSize = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
                val channelId = uri.getQueryParameter("channel")?.toLongOrNull() ?: 0L
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                FileAttachment(fileName, fileSize, fileId = "", isImage, channelId = channelId)
            } catch (_: Exception) { null }
        }
        return null
    }

    /** Re-parse old saved messages that contain ts3file:// but have no fileAttachment. */
    private fun migrateMessage(msg: ChatMessage): ChatMessage {
        if (msg.fileAttachment != null) return msg
        val attachment = parseFileAttachment(msg.text) ?: return msg
        return msg.copy(text = attachment.fileName, fileAttachment = attachment)
    }

    fun setChatState(open: Boolean, tab: Int, pmUserId: Int? = null) {
        isChatOpen = open
        activeChatTab = tab
        activePmUserId = pmUserId
        // Clear unread for the now-visible tab
        if (open) {
            if (tab == 0) clearUnreadChannel()
            else if (tab == 1 && pmUserId != null) clearUnreadPrivateUser(pmUserId)
        }
    }

    fun clearUnreadChannel() { _unreadChannel.value = 0 }
    fun clearUnreadPrivateUser(userId: Int) {
        val unread = _unreadPrivate.value.toMutableMap()
        unread.remove(userId)
        _unreadPrivate.value = unread
    }

    fun sendChannelMessage(text: String) {
        if (text.isBlank()) return
        tsClient?.sendChannelMessage(text)
        _channelMessages.value = _channelMessages.value + ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true,
        )
        scheduleSave()
    }

    fun sendPrivateMessage(userId: Int, text: String) {
        if (text.isBlank()) return
        tsClient?.sendPrivateMessage(userId, text)
        val msg = ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true, isPrivate = true, senderId = userId,
        )
        val current = _privateMessages.value.toMutableMap()
        current[userId] = (current[userId] ?: emptyList()) + msg
        _privateMessages.value = current
        scheduleSave()
    }

    fun moveToChannel(channelId: Long) {
        tsClient?.moveToChannel(channelId)
    }

    fun setAudioGain(gain: Float) {
        audioBridge?.gainFactor = gain
        viewModelScope.launch { settingsStore.setAudioGain(gain) }
    }

    fun setShowLinkThumbnails(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLinkThumbnails(enabled) }
    }

    fun setAutoLoadImages(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoLoadImages(enabled) }
    }

    fun setEnableFloatingWindow(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setEnableFloatingWindow(enabled) }
    }

    fun toggleVoiceMode() {
        val newPttMode = !_isPttMode.value
        _isPttMode.value = newPttMode
        // When switching to PTT mode, mute. When switching to VA, unmute.
        audioBridge?.setMuted(newPttMode)
    }

    fun toggleOutputMute() {
        audioBridge?.toggleOutputMute()
    }

    fun toggleMuteUser(clientId: Int) {
        val updated = if (clientId in _mutedUserIds.value) {
            _mutedUserIds.value - clientId
        } else {
            _mutedUserIds.value + clientId
        }
        _mutedUserIds.value = updated
        audioBridge?.setMutedUserIds(updated)
    }

    fun setPushToTalk(pressed: Boolean) {
        // Only changes mute state, NOT isPttMode — avoids UI recomposition swap
        audioBridge?.setMuted(!pressed)
    }

    private fun currentChannelId(): Long {
        val myId = tsClient?.clientId ?: return 0
        return _rawUsers.value.find { it.id == myId }?.channelId ?: 0
    }

    fun uploadAndSendFile(fileName: String, data: ByteArray, isPrivate: Boolean, targetId: Int?) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        if (channelId == 0L) return
        viewModelScope.launch {
            val path = "/$fileName"
            val success = client.uploadFile(channelId, path, data, overwrite = true)
            if (success) {
                val addr = serverAddress ?: "localhost"
                val host = addr.substringBefore(':')
                val port = addr.substringAfter(':', "9987")
                val fileDateTime = System.currentTimeMillis() / 1000
                val ts3Url = "ts3file://${host}?port=${port}&channel=${channelId}" +
                    "&path=/&filename=${fileName}&isDir=0&size=${data.size}&fileDateTime=${fileDateTime}"

                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                val attachment = FileAttachment(fileName, data.size.toLong(), fileId = "", isImage, channelId = channelId)
                val meSender = getApplication<Application>().getString(R.string.me_sender)

                if (isPrivate && targetId != null) {
                    tsClient?.sendPrivateMessage(targetId, ts3Url)
                    val msg = ChatMessage(sender = meSender, text = fileName, isMe = true, isPrivate = true, senderId = targetId, fileAttachment = attachment)
                    val current = _privateMessages.value.toMutableMap()
                    current[targetId] = (current[targetId] ?: emptyList()) + msg
                    _privateMessages.value = current
                } else {
                    tsClient?.sendChannelMessage(ts3Url)
                    val msg = ChatMessage(sender = meSender, text = fileName, isMe = true, fileAttachment = attachment)
                    _channelMessages.value = _channelMessages.value + msg
                }
                scheduleSave()
            }
        }
    }

    fun downloadAttachment(attachment: FileAttachment): StateFlow<DownloadState> {
        val host = serverAddress?.substringBefore(':') ?: "unknown"
        val cachePath = attachment.fileName.trimStart('/')
        val cacheKey = "$host/$cachePath"

        // Return existing in-memory result if already loaded
        downloadCache[cacheKey]?.let { existing ->
            if (existing.value is DownloadState.Done) return existing
        }

        val state = MutableStateFlow<DownloadState>(DownloadState.Downloading)
        val client = tsClient ?: run {
            state.value = DownloadState.Error("Pas connecté")
            return state
        }
        val channelId = if (attachment.channelId != 0L) attachment.channelId else currentChannelId()

        downloadCache[cacheKey] = state

        viewModelScope.launch(Dispatchers.IO) {
            // Check disk cache first
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, "/${attachment.fileName}")

            if (bytes != null) {
                // Save to disk cache if this was a fresh download
                if (cached == null) {
                    fileCache.put(host, cachePath, bytes)
                }

                if (attachment.isImage) {
                    val bmp = decodeSampledBitmap(bytes, 1280)
                    val uri = fileCache.getUri(host, cachePath)
                    state.value = DownloadState.Done(bmp?.asImageBitmap(), uri)
                } else {
                    val dlUri = saveToDownloads(attachment.fileName.substringAfterLast('/'), bytes)
                    state.value = DownloadState.Done(null, dlUri)
                }
            } else {
                downloadCache.remove(cacheKey)
                state.value = DownloadState.Error("Échec du téléchargement")
            }
        }
        return state
    }

    private suspend fun saveToDownloads(fileName: String, data: ByteArray): android.net.Uri? {
        val context = getApplication<Application>()
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.file_saved, fileName), Toast.LENGTH_SHORT).show()
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "saveToDownloads failed for $fileName", e)
            null
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
        // First pass: decode only bounds
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        // Calculate sample size (power of 2)
        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        if (width > maxDimension || height > maxDimension) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / sampleSize >= maxDimension || halfHeight / sampleSize >= maxDimension) {
                sampleSize *= 2
            }
        }

        // Second pass: decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
    }

    fun toggleFileManager() {
        if (_fileManagerOpen.value) {
            closeFileManager()
        } else {
            _fileManagerOpen.value = true
            refreshFileList()
        }
    }

    fun closeFileManager() {
        _fileManagerOpen.value = false
        _currentFilePath.value = "/"
        _fileList.value = emptyList()
    }

    fun refreshFileList() {
        val client = tsClient ?: run { Log.w(TAG, "refreshFileList: tsClient is null"); return }
        val channelId = currentChannelId()
        if (channelId == 0L) { Log.w(TAG, "refreshFileList: channelId is 0"); return }
        Log.d(TAG, "refreshFileList: channelId=$channelId path=${_currentFilePath.value}")
        _fileManagerLoading.value = true
        viewModelScope.launch {
            val files = client.listFiles(channelId, _currentFilePath.value)
            Log.d(TAG, "refreshFileList: got ${files?.size ?: "null"} files")
            _fileList.value = files ?: emptyList()
            _fileManagerLoading.value = false
        }
    }

    fun navigateToFolder(folderName: String) {
        val current = _currentFilePath.value
        _currentFilePath.value = if (current.endsWith("/")) "$current$folderName/" else "$current/$folderName/"
        refreshFileList()
    }

    fun navigateUp() {
        val current = _currentFilePath.value.trimEnd('/')
        if (current == "" || current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        _currentFilePath.value = if (parent.endsWith("/")) parent else "$parent/"
        refreshFileList()
    }

    fun deleteFileInChannel(name: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val fullPath = _currentFilePath.value + name
        client.deleteFile(channelId, fullPath)
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun renameFileInChannel(oldName: String, newName: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val currentPath = _currentFilePath.value
        client.renameFile(channelId, currentPath + oldName, currentPath + newName)
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun createDirectoryInChannel(dirName: String) {
        val client = tsClient ?: run { Log.w(TAG, "createDirectory: tsClient is null"); return }
        val channelId = currentChannelId()
        if (channelId == 0L) { Log.w(TAG, "createDirectory: channelId is 0"); return }
        val fullPath = _currentFilePath.value + dirName
        Log.d(TAG, "createDirectory: channelId=$channelId path=$fullPath")
        try {
            client.createDirectory(channelId, fullPath)
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed", e)
        }
        viewModelScope.launch { delay(500); refreshFileList() }
    }

    fun shareFile(targetUserId: Int?, fileName: String, fileSize: Long) {
        val channelId = currentChannelId()
        if (channelId == 0L) return
        val addr = serverAddress ?: return
        val host = addr.substringBefore(':')
        val port = addr.substringAfter(':', "9987")
        val currentPath = _currentFilePath.value
        val ts3Url = "ts3file://${host}?port=${port}&channel=${channelId}" +
            "&path=${currentPath}&filename=${fileName}&isDir=0&size=${fileSize}"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val attachment = FileAttachment(fileName, fileSize, fileId = "", isImage, channelId = channelId)
        val meSender = getApplication<Application>().getString(R.string.me_sender)

        if (targetUserId != null) {
            tsClient?.sendPrivateMessage(targetUserId, ts3Url)
            val msg = ChatMessage(
                sender = meSender, text = fileName, isMe = true,
                isPrivate = true, senderId = targetUserId, fileAttachment = attachment,
            )
            val current = _privateMessages.value.toMutableMap()
            current[targetUserId] = (current[targetUserId] ?: emptyList()) + msg
            _privateMessages.value = current
        } else {
            tsClient?.sendChannelMessage(ts3Url)
            _channelMessages.value = _channelMessages.value + ChatMessage(
                sender = meSender, text = fileName, isMe = true, fileAttachment = attachment,
            )
        }
        scheduleSave()
    }

    fun downloadFileFromManager(fileName: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val currentPath = _currentFilePath.value
        val fullName = currentPath.trimStart('/') + fileName
        val host = serverAddress?.substringBefore(':') ?: "unknown"
        val cachePath = fullName.trimStart('/')

        viewModelScope.launch(Dispatchers.IO) {
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, "/$fullName")
            if (bytes != null) {
                if (cached == null) {
                    fileCache.put(host, cachePath, bytes)
                }
                // Save to Downloads + open the file
                val dlUri = saveToDownloads(fileName, bytes)
                if (dlUri != null) {
                    withContext(Dispatchers.Main) {
                        openFileUri(dlUri, fileName)
                    }
                }
            }
        }
    }

    private fun openFileUri(uri: android.net.Uri, fileName: String) {
        val context = getApplication<Application>()
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun uploadFileToChannel(fileName: String, data: ByteArray) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        if (channelId == 0L) return
        val path = _currentFilePath.value + fileName
        viewModelScope.launch {
            val success = client.uploadFile(channelId, path, data, overwrite = true)
            if (success) {
                delay(500)
                refreshFileList()
            }
        }
    }

    fun disconnect() {
        saveNow()
        queriedPermChannels.clear()
        connectionService?.disconnect()
    }

    override fun onCleared() {
        saveNow()
        bound = false
        tsClient = null
        audioBridge = null
        connectionService = null
        super.onCleared()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(2000)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val addr = serverAddress ?: return
        messageStore.save(addr, _channelMessages.value, _privateMessages.value)
    }
}

/** Create a copy of User with isTalking changed (User fields are final). */
private fun User.withTalking(talking: Boolean): User = User(
    id, uid, databaseId, channelId, nickname, clientType,
    talking, isInputMuted, isOutputMuted, hasInputHardware, hasOutputHardware,
    isAway, isRecording, isPrioritySpeaker, isChannelCommander, isTalker,
    talkPower, awayMessage, serverGroups, channelGroup,
    platform, version, country, description, avatarId, iconId,
)
