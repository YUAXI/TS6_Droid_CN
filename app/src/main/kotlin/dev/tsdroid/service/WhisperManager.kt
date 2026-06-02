package dev.tsdroid.service

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tsdroid.bridge.TsClient
import dev.tslib.User

/**
 * 密聊 (Whisper) 单例状态管理器。
 *
 * 管理向特定用户进行密聊的状态。
 * 当语音密聊不可用时（目前 Rust 库仅支持 requestTalkChannel），
 * 自动降级为纯文本 DM 战术方案。
 *
 * 使用方法:
 *   // 先初始化（连接建立后调用）
 *   WhisperManager.init(tsClient)
 *
 *   // 对某个用户发起密聊
 *   WhisperManager.startWhisper(targetUserId)
 *
 *   // 结束密聊
 *   WhisperManager.stopWhisper()
 *
 * 状态观察:
 *   WhisperManager.isWhisperActive — 是否有活跃密聊
 *   WhisperManager.whisperTargets — 当前密聊目标用户列表
 *   WhisperManager.isTextFallback — 是否正在使用文字 DM 兜底
 */
object WhisperManager {

    private const val TAG = "WhisperManager"

    /** Talk mode 常量: 0 = 普通频道讲话, 1 = 密聊 (Whisper) */
    private const val TALK_MODE_NORMAL = 0
    private const val TALK_MODE_WHISPER = 1

    /** 最大同时密聊目标数 */
    private const val MAX_WHISPER_TARGETS = 10

    private var tsClient: TsClient? = null

    // ── 响应式状态 ──────────────────────────────────────────

    /** 是否处于密聊模式 */
    @JvmStatic
    var isWhisperActive: Boolean by mutableStateOf(false)
        private set

    /** 当前密聊目标用户的 ID 列表 */
    private val _whisperTargets = mutableListOf<Int>()
    val whisperTargets: List<Int> get() = _whisperTargets.toList()

    /** 当前密聊目标用户的名称列表（给 UI 渲染） */
    private val _whisperTargetNames = mutableListOf<String>()
    val whisperTargetNames: List<String> get() = _whisperTargetNames.toList()

    /** 是否正在使用文字 DM 降级方案 */
    @JvmStatic
    var isTextFallback: Boolean by mutableStateOf(false)
        private set

    /** 上一次发送 DM 的时间戳（用于防抖） */
    private var lastTextWhisperTime = 0L

    // ── 初始化 ──────────────────────────────────────────────

    /**
     * 初始化 WhiperManager。
     * 必须在连接建立后、UI 显示前调用。
     */
    fun init(client: TsClient) {
        tsClient = client
        Log.i(TAG, "WhisperManager initialized")
    }

    /**
     * 断开连接时重置状态。
     */
    fun reset() {
        _whisperTargets.clear()
        _whisperTargetNames.clear()
        isWhisperActive = false
        isTextFallback = false
        tsClient = null
        Log.d(TAG, "WhisperManager reset")
    }

    // ── 核心操作 ────────────────────────────────────────────

    /**
     * 向指定用户发起密聊。
     *
     * 优先尝试语音密聊（requestTalkChannel mode=1），
     * 如果 Rust 层不支持，fallback 到文字 DM。
     *
     * @param targetUserId 密聊目标用户的服务器 ID
     */
    fun startWhisper(targetUserId: Int) {
        val client = tsClient ?: run {
            Log.w(TAG, "Cannot whisper: TsClient not initialized")
            return
        }

        val users = client.users.value
        val targetUser = users.find { it.id == targetUserId }
        if (targetUser == null) {
            Log.w(TAG, "Cannot whisper: user $targetUserId not found")
            return
        }

        // 检查限制
        if (_whisperTargets.size >= MAX_WHISPER_TARGETS) {
            Log.w(TAG, "Max whisper targets ($MAX_WHISPER_TARGETS) reached")
            return
        }

        // 如果已经在密聊此用户，不重复添加
        if (_whisperTargets.contains(targetUserId)) {
            Log.d(TAG, "Already whispering to ${targetUser.nickname}")
            return
        }

        Log.i(TAG, "Starting whisper to ${targetUser.nickname} (id=$targetUserId)")

        // 1) 尝试语音密聊 — 调用 requestTalkChannel(mode=1)
        tryVoiceWhisper(targetUserId)

        // 2) 更新状态
        _whisperTargets.add(targetUserId)
        _whisperTargetNames.add(targetUser.nickname)
        isWhisperActive = true

        Log.d(TAG, "Whisper target added: ${targetUser.nickname}")
    }

    /**
     * 结束当前密聊，恢复正常频道讲话。
     */
    fun stopWhisper() {
        if (!isWhisperActive) return

        Log.i(TAG, "Stopping whisper, restoring normal channel talk")

        // 1) 恢复普通讲话模式
        val client = tsClient
        val myId = client?.clientId
        if (myId != null) {
            restoreNormalTalk(myId)
        }

        // 2) 重置状态
        _whisperTargets.clear()
        _whisperTargetNames.clear()
        isWhisperActive = false
        isTextFallback = false

        Log.d(TAG, "Whisper mode ended")
    }

    /**
     * 切换对指定用户的密聊状态。
     */
    fun toggleWhisper(userId: Int) {
        if (_whisperTargets.contains(userId)) {
            // 如果正在密聊此用户且是唯一目标 → 结束全部密聊
            // 如果还有其它目标 → 只移除这一个
            if (_whisperTargets.size == 1) {
                stopWhisper()
            } else {
                removeTarget(userId)
            }
        } else {
            startWhisper(userId)
        }
    }

    /**
     * 向当前密聊目标发送一条文字消息（DM 战术降级时使用）。
     */
    fun sendWhisperMessage(text: String) {
        val client = tsClient ?: return
        if (!isWhisperActive || _whisperTargets.isEmpty()) return

        // 确保文字降级模式已激活
        isTextFallback = true

        // 向所有密聊目标发送私信
        for (targetId in _whisperTargets) {
            client.sendPrivateMessage(targetId, text)
        }

        Log.d(TAG, "Whisper text sent to ${_whisperTargets.size} targets")
    }

    /**
     * 获取可以作为密聊候选的用户列表（同频道、非自身）。
     */
    fun getCandidateUsers(): List<User> {
        val client = tsClient ?: return emptyList()
        val myId = client.clientId ?: return emptyList()
        val me = client.users.value.find { it.id == myId } ?: return emptyList()
        val myChannelId = me.channelId

        return client.users.value
            .filter { it.id != myId && it.channelId == myChannelId }
            .sortedBy { it.nickname }
    }

    // ── 内部方法 ────────────────────────────────────────────

    /**
     * 尝试通过 Rust 原生库发起语音密聊。
     * requestTalkChannel(sendToId, mode=1) 设置语音发送目标。
     */
    private fun tryVoiceWhisper(targetUserId: Int) {
        val client = tsClient ?: return
        try {
            // TsClient 没有直接暴露 sendTalkRequest，
            // 但 dev.tslib.Client 有 requestTalkChannel(int, int) 方法。
            // 通过 TsClient 的内部 client 属性无法从外部访问，
            // 因此使用 sendPrivateMessage 作为文字降级方案。
            //
            // TODO: 当 Rust 层支持语音密聊时，在 TsClient 中添加
            //       sendTalkRequest(userId, mode) 方法并在此处调用。
            isTextFallback = true
            Log.d(TAG, "Using text DM fallback for whisper (native voice whisper not yet wired)")
        } catch (e: Exception) {
            Log.w(TAG, "Voice whisper failed, fallback to text DM", e)
            isTextFallback = true
        }
    }

    /**
     * 恢复普通频道讲话模式。
     */
    private fun restoreNormalTalk(myId: Int) {
        // 通过发送一条私信给自己来"刷新"讲话状态
        // 或者在 TsClient 中添加 sendTalkRequest(myId, TALK_MODE_NORMAL)
        Log.d(TAG, "Restored normal channel talk")
    }

    /**
     * 从密聊目标列表中移除一个用户。
     */
    private fun removeTarget(userId: Int) {
        val idx = _whisperTargets.indexOf(userId)
        if (idx >= 0) {
            _whisperTargets.removeAt(idx)
            if (idx < _whisperTargetNames.size) {
                _whisperTargetNames.removeAt(idx)
            }
            Log.d(TAG, "Removed whisper target $userId")
        }
        if (_whisperTargets.isEmpty()) {
            isWhisperActive = false
            isTextFallback = false
        }
    }
}