package dev.tsdroid.service

import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.app.Notification
import android.app.PendingIntent
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.tsdroid.MainActivity
import dev.tsdroid.R
import dev.tsdroid.TsDroidApp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.TsClient
import dev.tslib.Identity
import dev.tslib.Channel
import dev.tslib.User
import dev.tsdroid.ui.component.ChannelTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TsConnectionService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub required by AccessibilityService
    }

    override fun onInterrupt() {
        // Stub required by AccessibilityService
    }

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"
        private const val ACTION_SHOW_OVERLAY = "com.flammedemon.ts6droid.SHOW_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.flammedemon.ts6droid.HIDE_OVERLAY"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, TsConnectionService::class.java))
            } else {
                context.startService(Intent(context, TsConnectionService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TsConnectionService::class.java))
        }

        fun showOverlay(context: Context) {
            val intent = Intent(context, TsConnectionService::class.java).apply { action = ACTION_SHOW_OVERLAY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, TsConnectionService::class.java).apply { action = ACTION_HIDE_OVERLAY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val tsClient: TsClient get() = this@TsConnectionService.tsClient
        val audioBridge: AudioBridge get() = this@TsConnectionService.audioBridge
        val service: TsConnectionService get() = this@TsConnectionService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val tsClient = TsClient()
    lateinit var audioBridge: AudioBridge
        private set

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = serviceViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var overlayConnected by mutableStateOf(false)
    private var overlayChannelName by mutableStateOf<String?>(null)
    private var overlayActiveSpeakerId by mutableStateOf<Int?>(null)
    private var overlayActiveSpeakerName by mutableStateOf<String?>(null)
    
    // Overlay state
    private var isOverlayExpanded by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Listen for audio events, talk status, and play per-user mixing
        tsClient.events.onEach { event ->
            when (event.type) {
                "audio_received" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return@onEach
                    val data = event.data["data"]
                    if (data is ByteArray) {
                        audioBridge.playAudio(userId, data)
                    } else if (data is Array<*>) {
                        val bytes = ByteArray(data.size) { (data[it] as? Number)?.toByte() ?: 0 }
                        audioBridge.playAudio(userId, bytes)
                    }
                }
                "talk_status_start" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId != tsClient.clientId) {
                        overlayActiveSpeakerId = speakerId
                        overlayActiveSpeakerName = findUserNickname(speakerId)
                    }
                }
                "talk_status_stop" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId == overlayActiveSpeakerId) {
                        overlayActiveSpeakerId = null
                        overlayActiveSpeakerName = null
                    }
                }
            }
        }.launchIn(serviceScope)

        tsClient.state.onEach { state ->
            overlayConnected = state == dev.tslib.ConnectionState.CONNECTED
            updateOverlayChannelName()
            updateNotification()
        }.launchIn(serviceScope)

        tsClient.users.onEach {
            updateOverlayChannelName()
            refreshActiveSpeakerName()
        }.launchIn(serviceScope)

        tsClient.channels.onEach {
            updateOverlayChannelName()
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                audioBridge.toggleMute()
                updateNotification()
                return START_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                showFloatingWindow()
                return START_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                hideFloatingWindow()
                return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun connect(address: String, identity: Identity, nickname: String, password: String?) {
        serviceScope.launch {
            tsClient.connect(address, identity, nickname, password)
            audioBridge.startCapture(serviceScope)
            // Sync initial mute state with server
            if (audioBridge.isMuted.value) {
                tsClient.setInputMuted(true)
            }
            updateNotification()
            // Start event loop
            launch { tsClient.eventLoop() }
        }
    }

    fun disconnect() {
        hideFloatingWindow()
        audioBridge.stopCapture()
        serviceScope.launch(Dispatchers.IO) {
            tsClient.disconnect()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    fun showFloatingWindow() {
        Log.d(TAG, "showFloatingWindow called")
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show floating window because overlay permission is missing")
            return
        }
        if (overlayView != null) {
            Log.d(TAG, "showFloatingWindow skipped: already visible")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TsConnectionService)
            setViewTreeViewModelStoreOwner(this@TsConnectionService)
            setViewTreeSavedStateRegistryOwner(this@TsConnectionService)
            
            setContent {
                val channels by tsClient.channels.collectAsState()
                val users by tsClient.users.collectAsState()
                val isMicMuted by audioBridge.isMuted.collectAsState()
                val isOutputMuted by audioBridge.isOutputMuted.collectAsState()
                
                FloatingOverlayContent(
                    connected = overlayConnected,
                    channelName = overlayChannelName,
                    activeSpeakerName = overlayActiveSpeakerName,
                    isExpanded = isOverlayExpanded,
                    onToggleExpand = { isOverlayExpanded = !isOverlayExpanded },
                    onDrag = { dx, dy ->
                        overlayLayoutParams?.let { layout ->
                            layout.x += dx.toInt()
                            layout.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(this, layout)
                            } catch (_: Exception) {}
                        }
                    },
                    channels = channels,
                    users = users,
                    isMicMuted = isMicMuted,
                    isOutputMuted = isOutputMuted,
                    onToggleMic = { audioBridge.toggleMute() },
                    onToggleOutput = { audioBridge.toggleOutputMute() },
                    onChannelClick = { channelId -> tsClient.moveToChannel(channelId) },
                    onClose = { hideFloatingWindow() }
                )
            }
        }

        overlayView = composeView
        overlayLayoutParams = params
        windowManager.addView(composeView, params)
    }

    private fun updateOverlayChannelName() {
        val myId = tsClient.clientId ?: return
        val currentChannelId = tsClient.users.value.find { it.id == myId }?.channelId
        overlayChannelName = currentChannelId?.let { channelId ->
            tsClient.channels.value.find { it.id == channelId }?.name
        }
    }

    private fun refreshActiveSpeakerName() {
        overlayActiveSpeakerName = overlayActiveSpeakerId?.let { findUserNickname(it) }
    }

    private fun findUserNickname(userId: Int): String? {
        return tsClient.users.value.firstOrNull { it.id == userId }?.nickname
    }

    fun hideFloatingWindow() {
        Log.d(TAG, "hideFloatingWindow called")
        overlayView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayLayoutParams = null
        isOverlayExpanded = false
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceViewModelStore.clear()
        hideFloatingWindow()
        audioBridge.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val serverName = tsClient.serverInfo.value?.name ?: getString(R.string.connecting)
        val muteLabel = getString(if (audioBridge.isMuted.value) R.string.notif_unmute else R.string.notif_mute)

        return NotificationCompat.Builder(this, TsDroidApp.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serverName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, muteLabel, muteIntent)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }

    @Composable
    private fun FloatingOverlayContent(
        connected: Boolean,
        channelName: String?,
        activeSpeakerName: String?,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onDrag: (Float, Float) -> Unit,
        channels: List<Channel>,
        users: List<User>,
        isMicMuted: Boolean,
        isOutputMuted: Boolean,
        onToggleMic: () -> Unit,
        onToggleOutput: () -> Unit,
        onChannelClick: (Long) -> Unit,
        onClose: () -> Unit
    ) {
        if (isExpanded) {
            // Expanded Control Panel
            Card(
                modifier = Modifier
                    .width(280.dp)
                    .height(350.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header (Draggable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.x, dragAmount.y)
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (connected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = channelName ?: "Offline",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Collapse", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }

                    // Channel List
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (channels.isNotEmpty()) {
                            ChannelTree(
                                channels = channels,
                                users = users,
                                onChannelClick = onChannelClick,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "No channels available",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Bottom Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Output Mute Toggle
                        IconButton(
                            onClick = onToggleOutput,
                            modifier = Modifier.background(
                                if (isOutputMuted) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                                CircleShape
                            )
                        ) {
                            Icon(
                                imageVector = if (isOutputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset,
                                contentDescription = "Toggle Output",
                                tint = if (isOutputMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Mic Mute Toggle
                        IconButton(
                            onClick = onToggleMic,
                            modifier = Modifier.background(
                                if (isMicMuted) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                                CircleShape
                            )
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Toggle Mic",
                                tint = if (isMicMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Collapsed Bubble
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .clickable { onToggleExpand() },
                contentAlignment = Alignment.Center
            ) {
                if (!activeSpeakerName.isNullOrEmpty()) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Active Speaker",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Default.ChatBubble,
                        contentDescription = "Open Panel",
                        tint = if (connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
