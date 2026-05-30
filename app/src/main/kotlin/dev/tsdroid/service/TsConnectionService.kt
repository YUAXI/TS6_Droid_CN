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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.tsdroid.MainActivity
import dev.tsdroid.han.R
import dev.tsdroid.TsDroidApp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.AvatarCache
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

class TsConnectionService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore get() = serviceViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"

        var instance: TsConnectionService? = null
            private set
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

    private var overlayConnected by mutableStateOf(false)
    private var overlayChannelName by mutableStateOf<String?>(null)
    private var overlayActiveSpeakerId by mutableStateOf<Int?>(null)
    private var overlayActiveSpeakerName by mutableStateOf<String?>(null)
    private var overlayActiveSpeakerAvatar by mutableStateOf<ImageBitmap?>(null)
    
    private lateinit var avatarCache: AvatarCache

    // Overlay state
    private var isOverlayExpanded by mutableStateOf(false)
    
    private var isIntentionalDisconnect = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Foreground Service Created")
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        avatarCache = AvatarCache(applicationContext.cacheDir)
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()

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
                        val speakerUser = tsClient.users.value.find { it.id == speakerId }
                        val uid = speakerUser?.uid
                        if (!uid.isNullOrEmpty()) {
                            serviceScope.launch(Dispatchers.IO) {
                                avatarCache.loadAvatar(uid, tsClient)
                                val avatar = avatarCache.getAvatar(uid)
                                withContext(Dispatchers.Main) {
                                    if (overlayActiveSpeakerId == speakerId) {
                                        overlayActiveSpeakerAvatar = avatar
                                    }
                                }
                            }
                        } else {
                            overlayActiveSpeakerAvatar = null
                        }
                    }
                }
                "talk_status_stop" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId == overlayActiveSpeakerId) {
                        overlayActiveSpeakerId = null
                        overlayActiveSpeakerName = null
                        overlayActiveSpeakerAvatar = null
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
        super.onStartCommand(intent, flags, startId)
        startServiceForeground()
        
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_TOGGLE_MUTE -> {
                audioBridge.toggleMute()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
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

    fun connect(address: String, identity: Identity, nickname: String, password: String?) {
        isIntentionalDisconnect = false
        serviceScope.launch {
            try {
                tsClient.connect(address, identity, nickname, password)
                audioBridge.startCapture(serviceScope)
                // Sync initial mute state with server
                if (audioBridge.isMuted.value) {
                    tsClient.setInputMuted(true)
                }
                // Start event loop
                tsClient.startEventLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "Connection failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                disconnect()
            }
        }
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        if (instance == this) {
            instance = null
        }
        hideFloatingWindow()
        audioBridge.stopCapture()
        serviceScope.launch(Dispatchers.IO) {
            try {
                tsClient.disconnect()
            } finally {
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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

        val displayMetrics = resources.displayMetrics
        val widthPx = (280 * displayMetrics.density).toInt()
        val heightPx = (350 * displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val screenWidthDp = configuration.screenWidthDp.dp
                val screenHeightDp = configuration.screenHeightDp.dp
                
                val channels by tsClient.channels.collectAsState()
                val users by tsClient.users.collectAsState()
                val isMicMuted by audioBridge.isMuted.collectAsState()
                val isOutputMuted by audioBridge.isOutputMuted.collectAsState()
                
                FloatingOverlayContent(
                    connected = overlayConnected,
                    channelName = overlayChannelName,
                    activeSpeakerName = overlayActiveSpeakerName,
                    activeSpeakerAvatar = overlayActiveSpeakerAvatar,
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
        instance = null
        serviceViewModelStore.clear()
        hideFloatingWindow()
        audioBridge.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    @Composable
    private fun FloatingOverlayContent(
        connected: Boolean,
        channelName: String?,
        activeSpeakerName: String?,
        activeSpeakerAvatar: ImageBitmap?,
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
        val CardBackgroundTransparent = Color(0x991A1A1A) // ~60% alpha dark glass base
        val SurfaceMutedTransparent = Color(0x33FFFFFF) // Subdued element backgrounds

        // Find current channel users
        val myId = tsClient.clientId
        val currentChannelId = users.find { it.id == myId }?.channelId
        val activeUsers = users.filter { it.channelId == currentChannelId }

        Box(
            modifier = Modifier
                .wrapContentSize()
                .background(Color.Transparent) // Force the root container token to be 100% transparent
        ) {
            if (!isExpanded) {
                // --- COLLAPSED AVATAR BUBBLE ---
                val isSpeaking = !activeSpeakerName.isNullOrEmpty()
                val borderColor = if (isSpeaking) Color(0xFF2196F3) else Color(0x4DFFFFFF)
                val borderWidth = if (isSpeaking) 2.dp else 1.dp
                
                Surface(
                    modifier = Modifier
                        .size(40.dp) // Make smaller
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .clickable { onToggleExpand() },
                    shape = CircleShape,
                    color = CardBackgroundTransparent, // Semitransparent ring
                    border = BorderStroke(borderWidth, borderColor)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Render Avatar Circle + Mini Speaker Waveform Indicator
                        if (isSpeaking) {
                            // Have speaker: Show user avatar
                            if (activeSpeakerAvatar != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = activeSpeakerAvatar,
                                    contentDescription = "Active Speaker Avatar",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                    alpha = 1.0f
                                )
                            } else {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Active Speaker",
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        } else {
                            // No speaker: Show software logo
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = "Open Panel",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxSize(0.8f),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            } else {
                // --- EXPANDED MINIMALIST PANEL ---
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .height(240.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackgroundTransparent), // Blends flawlessly over game/desktop backgrounds
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        // 1. Header Row (Title + Minimize Button)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onDrag(dragAmount.x, dragAmount.y)
                                    }
                                }
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (connected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = channelName ?: "Offline",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Minimize", tint = Color.White)
                            }
                        }

                        Divider(color = SurfaceMutedTransparent, thickness = 1.dp)

                        // 2. Simplified Channel User List (Scrollable, clean list items)
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            items(activeUsers) { user ->
                                val isSpeaking = user.nickname == activeSpeakerName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Use smaller avatar for the expanded list instead of green dot
                                    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                                    
                                    LaunchedEffect(user.uid) {
                                        if (!user.uid.isNullOrEmpty()) {
                                            if (avatarCache.getAvatar(user.uid) != null) {
                                                avatarBitmap = avatarCache.getAvatar(user.uid)
                                            } else if (!avatarCache.hasNoAvatar(user.uid)) {
                                                avatarCache.loadAvatar(user.uid, tsClient)
                                                avatarBitmap = avatarCache.getAvatar(user.uid)
                                            }
                                        }
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .border(
                                                width = if (isSpeaking) 2.dp else 0.dp,
                                                color = if (isSpeaking) Color(0xFF2196F3) else Color.Transparent,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (avatarBitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = avatarBitmap!!,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = user.nickname,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSpeaking) Color.White else Color(0xCCFFFFFF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Divider(color = SurfaceMutedTransparent, thickness = 1.dp)

                        // 4. Quick Actions Toolbar (Mute, Deafen, Disconnect) with alpha surfaces
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Mic Mute Toggle
                            IconButton(
                                onClick = onToggleMic,
                                modifier = Modifier.background(
                                    if (isMicMuted) Color(0x66F44336) else SurfaceMutedTransparent,
                                    CircleShape
                                )
                            ) {
                                Icon(
                                    imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Toggle Mic",
                                    tint = Color.White
                                )
                            }

                            // Output Mute Toggle
                            IconButton(
                                onClick = onToggleOutput,
                                modifier = Modifier.background(
                                    if (isOutputMuted) Color(0x66F44336) else SurfaceMutedTransparent,
                                    CircleShape
                                )
                            ) {
                                Icon(
                                    imageVector = if (isOutputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset,
                                    contentDescription = "Toggle Output",
                                    tint = Color.White
                                )
                            }

                            // Disconnect
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.background(SurfaceMutedTransparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Disconnect",
                                    tint = Color(0xFFFF5252)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
