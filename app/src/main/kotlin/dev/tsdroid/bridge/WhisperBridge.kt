package dev.tsdroid.bridge

/**
 * JNI bridge to the official TeamSpeak SDK for whisper functionality.
 *
 * Uses libts_whisper_bridge.so (built from app/src/main/cpp/) which links
 * against the official libteamspeak_sdk_client.so.
 *
 * NOTE: The official SDK requires its own connection handler. This bridge
 * is prepared for future integration. Currently, whisper is handled via
 * the existing Rust JNI library (TsClient.requestTalkChannel).
 */
object WhisperBridge {

    private var loaded = false

    /**
     * Attempt to load the native whisper bridge library.
     * Returns true if loaded successfully, false if unavailable.
     */
    fun tryLoad(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("ts_whisper_bridge")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("WhisperBridge", "Native whisper bridge not available: ${e.message}")
            false
        }
    }

    /**
     * Set whisper targets using the official TS SDK.
     * @param serverConnectionHandlerID SDK connection handler ID
     * @param targetClientIDs array of target client IDs, or null/empty to cancel whisper
     * @param ownClientID our client ID on the server
     * @return 0 on success, SDK error code on failure
     */
    external fun nativeRequestClientSetWhisperList(
        serverConnectionHandlerID: Long,
        targetClientIDs: IntArray?,
        ownClientID: Int,
    ): Int

    /**
     * Cancel whisper mode and restore normal channel talk.
     */
    external fun nativeCancelWhisper(
        serverConnectionHandlerID: Long,
        ownClientID: Int,
    ): Int
}