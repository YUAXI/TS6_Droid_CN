package dev.tsdroid.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tsdroid.bridge.MAX_NICKNAME_COLLISION_ATTEMPTS
import dev.tsdroid.bridge.hasNicknameCollision
import dev.tsdroid.bridge.nicknameWithCollisionSuffix
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.ServerBookmark
import dev.tsdroid.service.TsConnectionService
import dev.tslib.Channel
import dev.tslib.ChannelTree
import dev.tslib.Client
import dev.tslib.ConnectionState
import dev.tslib.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ConnectionViewModel"
        /** Survit aux recréations du ViewModel dans le même processus. */
        private var autoReconnectAttempted = false
    }

    private val bookmarkStore = BookmarkStore(application)

    val bookmarks: StateFlow<List<ServerBookmark>> = bookmarkStore.bookmarks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val autoReconnect: StateFlow<Boolean> = bookmarkStore.autoReconnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val address = MutableStateFlow("")
    val nickname = MutableStateFlow("")
    val password = MutableStateFlow("")
    val channel = MutableStateFlow("")

    /** Index du favori en cours d'édition, ou -1 si ajout. */
    private val _editingIndex = MutableStateFlow(-1)
    val editingIndex: StateFlow<Int> = _editingIndex.asStateFlow()

    val browsedChannels = MutableStateFlow<List<Channel>>(emptyList())
    val isBrowsing = MutableStateFlow(false)
    val showChannelPicker = MutableStateFlow(false)

    private val _bookmarkIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val bookmarkIcons: StateFlow<Map<Long, ImageBitmap>> = _bookmarkIcons.asStateFlow()

    init {
        // Load bookmark icons from disk cache
        viewModelScope.launch {
            bookmarkStore.bookmarks.collect { list ->
                loadBookmarkIcons(list)
            }
        }
    }

    private fun loadBookmarkIcons(bookmarks: List<ServerBookmark>) {
        val iconsDir = File(getApplication<Application>().cacheDir, "icons")
        if (!iconsDir.exists()) return
        val newIcons = mutableMapOf<Long, ImageBitmap>()
        for (b in bookmarks) {
            if (b.iconId == 0L) continue
            if (_bookmarkIcons.value.containsKey(b.iconId)) continue
            val file = File(iconsDir, b.iconId.toString())
            if (file.exists() && file.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    newIcons[b.iconId] = bitmap.asImageBitmap()
                }
            }
        }
        if (newIcons.isNotEmpty()) {
            _bookmarkIcons.value = _bookmarkIcons.value + newIcons
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var serviceConnection: ServiceConnection? = null
    private var connectJob: kotlinx.coroutines.Job? = null
    private var cloneBypassIdentity: Identity? = null

    fun connect(onConnected: () -> Unit) {
        val addr = address.value.trim()
        val nick = nickname.value.trim()
        if (addr.isEmpty() || nick.isEmpty()) {
            _error.value = getApplication<Application>().getString(R.string.error_address_nickname_required)
            return
        }

        val existingService = TsConnectionService.instance
        if (existingService?.hasActiveConnection(addr) == true) {
            _connectionState.value = ConnectionState.CONNECTED
            _error.value = null
            onConnected()
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        _error.value = null

        val context = getApplication<Application>()

        // Check for overlay permission before starting the service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _error.value = "Please grant the 'Display over other apps' permission to use the floating window."
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        try {
            val intent = Intent(context, TsConnectionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _error.value = e.message ?: getApplication<Application>().getString(R.string.connection_failed)
            return
        }

        // Cancel any previous connection attempt to avoid stale collectors
        connectJob?.cancel()

        // Wait for the service instance to be available
        connectJob = viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            val service = TsConnectionService.instance
            if (service == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _error.value = "Failed to start background service."
                return@launch
            }

            try {
                val identity = getOrCreateIdentity()
                val pw = password.value.trim().takeIf { it.isNotEmpty() }
                var connectionFailure = service.connect(addr, identity, nick, pw)
                if (connectionFailure?.isTooManyClonesFailure() == true) {
                    Log.w(TAG, "Too many clones for saved identity; retrying with a temporary identity")
                    connectionFailure = service.connect(addr, getCloneBypassIdentity(), nick, pw)
                }

                if (connectionFailure == null) {
                    _connectionState.value = ConnectionState.CONNECTED
                    onConnected()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _error.value = connectionFailure.message
                        ?: getApplication<Application>().getString(R.string.connection_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _error.value = e.message ?: getApplication<Application>().getString(R.string.connection_failed)
            }
        }
    }

    fun resumeExistingConnection(onConnected: () -> Unit): Boolean {
        val service = TsConnectionService.instance ?: return false
        if (!service.hasActiveConnection()) return false

        _connectionState.value = ConnectionState.CONNECTED
        _error.value = null
        onConnected()
        return true
    }

    fun connectBookmark(bookmark: ServerBookmark, onConnected: () -> Unit) {
        address.value = bookmark.address
        nickname.value = bookmark.nickname
        password.value = bookmark.password ?: ""
        channel.value = bookmark.channel ?: ""
        viewModelScope.launch {
            try {
                bookmarkStore.saveLastBookmarkAddress(bookmark.address)
            } catch (_: Exception) {
                // Ignore address save failure; connection may still proceed.
            }
        }
        connect(onConnected)
    }

    fun tryAutoReconnect(onConnected: () -> Unit) {
        if (autoReconnectAttempted) return
        if (resumeExistingConnection(onConnected)) return
        autoReconnectAttempted = true
        viewModelScope.launch {
            val lastAddr = bookmarkStore.lastBookmarkAddress.first()
            if (lastAddr.isEmpty()) return@launch
            val list = bookmarkStore.bookmarks.first()
            val bookmark = list.find { it.address == lastAddr } ?: return@launch
            connectBookmark(bookmark, onConnected)
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { bookmarkStore.setAutoReconnect(enabled) }
    }

    fun saveBookmark() {
        val addr = address.value.trim()
        val nick = nickname.value.trim()
        if (addr.isEmpty()) return
        val bookmark = ServerBookmark(
            name = addr.substringBefore(":"),
            address = addr,
            nickname = nick,
            password = password.value.trim().takeIf { it.isNotEmpty() },
            channel = channel.value.trim().takeIf { it.isNotEmpty() },
        )
        viewModelScope.launch {
            val idx = _editingIndex.value
            if (idx >= 0) {
                bookmarkStore.replace(idx, bookmark)
            } else {
                bookmarkStore.add(bookmark)
            }
            clearFields()
        }
    }

    fun removeBookmark(index: Int) {
        viewModelScope.launch { bookmarkStore.remove(index) }
    }

    fun editBookmark(bookmark: ServerBookmark, index: Int) {
        address.value = bookmark.address
        nickname.value = bookmark.nickname
        password.value = bookmark.password ?: ""
        channel.value = bookmark.channel ?: ""
        _editingIndex.value = index
    }

    fun cancelEdit() {
        _editingIndex.value = -1
        clearFields()
    }

    private fun clearFields() {
        _editingIndex.value = -1
        address.value = ""
        nickname.value = ""
        password.value = ""
        channel.value = ""
    }

    fun browseChannels() {
        val addr = address.value.trim()
        val nick = nickname.value.trim()
        if (addr.isEmpty() || nick.isEmpty()) {
            _error.value = getApplication<Application>().getString(R.string.error_address_nickname_required)
            return
        }

        isBrowsing.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val channels = withContext(Dispatchers.IO) {
                    val identity = getOrCreateIdentity()
                    val pw = password.value.trim().takeIf { it.isNotEmpty() }
                    var lastFailure: Throwable? = null

                    for (attempt in 0 until MAX_NICKNAME_COLLISION_ATTEMPTS) {
                        val candidateNick = nicknameWithCollisionSuffix(nick, attempt)
                        var client: Client? = null
                        try {
                            try {
                                identity.setNickname(candidateNick)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "Failed to update identity nickname before browsing", e)
                            }
                            val c = Client(addr, identity, candidateNick, pw, null)
                            client = c
                            c.waitConnected()
                            // Pump events until channels are available (or timeout)
                            val deadline = System.currentTimeMillis() + 5000
                            while (System.currentTimeMillis() < deadline) {
                                c.processEvents()
                                val raw = c.channels
                                if (raw != null && raw.isNotEmpty()) break
                                Thread.sleep(20)
                            }

                            if (hasNicknameCollision(c.users, c.clientId, candidateNick)) {
                                lastFailure = IllegalStateException("Nickname already in use: $candidateNick")
                                disconnectAndClose(c)
                                client = null
                                continue
                            }

                            val ch = c.channels?.filterNotNull() ?: emptyList()
                            disconnectAndClose(c)
                            client = null
                            return@withContext ch
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            lastFailure = e
                        } finally {
                            client?.let { closeQuietly(it) }
                        }
                    }

                    throw Exception(
                        "Browse failed after trying unique nicknames",
                        lastFailure,
                    )
                }
                browsedChannels.value = channels
                showChannelPicker.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: getApplication<Application>().getString(R.string.browse_failed)
            } finally {
                isBrowsing.value = false
            }
        }
    }

    fun selectChannel(channelId: Long) {
        val channels = browsedChannels.value
        val tree = ChannelTree.fromChannels(channels.toTypedArray())
        val path = tree.pathTo(channelId)
        tree.close()
        channel.value = path.joinToString("/") { it.name }
        showChannelPicker.value = false
    }

    fun clearError() {
        _error.value = null
    }

    private fun disconnectAndClose(client: Client) {
        try {
            client.disconnect()
            val flushEnd = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < flushEnd) {
                client.processEvents()
                Thread.sleep(20)
            }
        } catch (_: Throwable) {
        } finally {
            closeQuietly(client)
        }
    }

    private fun closeQuietly(client: Client) {
        try {
            client.close()
        } catch (_: Throwable) {
        }
    }

    private fun getOrCreateIdentity(): Identity {
        val context = getApplication<Application>()
        val identityFile = File(context.filesDir, "identity.ini")
        return if (identityFile.exists()) {
            Identity.load(identityFile.absolutePath)
        } else {
            val identity = Identity()
            identity.save(identityFile.absolutePath)
            identity
        }
    }

    private fun getCloneBypassIdentity(): Identity {
        return cloneBypassIdentity ?: Identity().also {
            cloneBypassIdentity = it
        }
    }

    private fun Throwable.isTooManyClonesFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if ("toomanyclones" in message || "too many clones" in message) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun showFloatingWindow() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "showFloatingWindow skipped: not connected (state=${_connectionState.value})")
            return
        }
        Log.d(TAG, "showFloatingWindow: connected, invoking overlay")
        TsConnectionService.instance?.showFloatingWindow() ?: run {
            Log.d(TAG, "showFloatingWindow: no instance, cannot show overlay")
        }
    }

    fun hideFloatingWindow() {
        Log.d(TAG, "hideFloatingWindow")
        TsConnectionService.instance?.hideFloatingWindow() ?: run {
            Log.d(TAG, "hideFloatingWindow: no instance, cannot hide overlay")
        }
    }

    override fun onCleared() {
        serviceConnection?.let {
            try {
                getApplication<Application>().unbindService(it)
            } catch (_: Exception) {}
        }
        super.onCleared()
    }
}
