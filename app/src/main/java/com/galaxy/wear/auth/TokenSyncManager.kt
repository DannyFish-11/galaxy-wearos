package com.galaxy.wear.auth

import android.content.Context
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.galaxy.wear.auth.DeviceFlowManager.DeviceToken
import com.galaxy.wear.transport.BleGatewayClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Token 同步管理器
 *
 * 通过 BLE（蓝牙低功耗）与已连接的 Android 手机同步认证令牌。
 * 适用于以下场景：
 * 1. 手表没有直接网络连接，需要通过手机代理获取令牌
 * 2. 手机已完成 OAuth 授权，手表通过 BLE 获取已授权的令牌
 * 3. 令牌过期时，手表请求手机刷新令牌
 *
 * BLE 通信协议：
 * - 手表发送 {"action": "request_token"} 请求令牌
 * - 手机回复 {"action": "send_token", "token": {...}}
 * - 手表发送 {"action": "refresh_token", "refresh_token": "..."} 请求刷新
 * - 手机回复 {"action": "token_refreshed", "token": {...}}
 */
class TokenSyncManager(
    private val context: Context,
    private val deviceFlowManager: DeviceFlowManager,
    private val bleClient: BleGatewayClient
) {
    companion object {
        private const val TAG = "TokenSyncManager"
        /** BLE 消息中标识 Token 同步的 action 字段值 */
        private const val ACTION_REQUEST_TOKEN = "request_token"
        private const val ACTION_SEND_TOKEN = "send_token"
        private const val ACTION_REFRESH_TOKEN = "refresh_token"
        private const val ACTION_TOKEN_REFRESHED = "token_refreshed"
        private const val ACTION_TOKEN_EXPIRED = "token_expired"
        private const val ACTION_ERROR = "error"
        /** 自动刷新检查间隔（毫秒） */
        private const val AUTO_REFRESH_INTERVAL_MS = 300_000L // 5 分钟
        /** 请求超时（毫秒） */
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    /** BLE 令牌消息格式 */
    @Serializable
    data class TokenBleMessage(
        val action: String,
        val token: DeviceToken? = null,
        val refreshToken: String? = null,
        val error: String? = null
    )

    /** 同步状态 */
    sealed class SyncState {
        /** 空闲 */
        data object Idle : SyncState()
        /** 正在请求令牌 */
        data object Requesting : SyncState()
        /** 正在刷新令牌 */
        data object Refreshing : SyncState()
        /** 同步成功 */
        data class Success(val token: DeviceToken) : SyncState()
        /** 同步失败 */
        data class Error(val message: String) : SyncState()
    }

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 是否有正在进行的请求 */
    private val isRequesting = AtomicBoolean(false)
    /** 等待响应的 Continuation */
    // ROUND-4-FIX(跨线程可见性导致回包丢失): 该字段在 IO 协程线程写入
    // (suspendCancellableCoroutine 内),而 receiveTokenFromPhone/handleBleMessage
    // 从 BLE Binder 回调线程读取 —— 无 @Volatile 时回调线程可能读到过期的 null,
    // 手机明明回了令牌却无人恢复挂起点,请求"莫名"等满 30s 超时。
    @Volatile
    private var pendingContinuation: CancellableContinuation<DeviceToken?>? = null
    /** 自动刷新任务 */
    private var autoRefreshJob: Job? = null
    /** 主协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 状态监听 ----

    init {
        // 启动 BLE 消息监听
        startBleMessageListener()
    }

    /**
     * 启动 BLE 消息监听器
     * 监听来自手机的 Token 相关消息
     */
    private fun startBleMessageListener() {
        scope.launch {
            try {
                // 注意：实际项目中需要通过 BLE 的回调机制监听消息
                // 这里提供一个可扩展的框架，实际消息解析在 receiveTokenFromPhone() 中处理
                Log.d(TAG, "BLE token message listener started")
            } catch (e: Exception) {
                Log.e(TAG, "BLE listener error: ${e.message}")
            }
        }
    }

    // ---- 公共 API ----

    /**
     * 从已连接的 Android 手机请求 Token
     *
     * 通过 BLE 发送 token 请求消息，等待手机回复。
     * 手机端应已完成 OAuth 授权并保存了有效令牌。
     *
     * @return DeviceToken? 成功返回令牌，失败返回 null
     */
    suspend fun requestTokenFromPhone(): DeviceToken? {
        if (!isRequesting.compareAndSet(false, true)) {
            Log.w(TAG, "Token request already in progress")
            return null
        }

        _syncState.value = SyncState.Requesting
        Log.i(TAG, "Requesting token from phone via BLE...")

        return try {
            // 构建请求消息
            val requestMsg = TokenBleMessage(action = ACTION_REQUEST_TOKEN)
            val requestJson = jsonFormat.encodeToString(requestMsg)

            // 通过 BLE 发送请求
            val sent = bleClient.sendJson(requestJson)
            if (!sent) {
                Log.e(TAG, "Failed to send token request via BLE")
                _syncState.value = SyncState.Error("BLE 发送失败")
                return null
            }

            // 等待响应（带超时）
            val token = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation = continuation
                    continuation.invokeOnCancellation {
                        pendingContinuation = null
                    }
                }
            }

            if (token != null) {
                Log.i(TAG, "Token received from phone: ${token.userEmail}")
                deviceFlowManager.saveToken(token)
                _syncState.value = SyncState.Success(token)
            } else {
                Log.w(TAG, "Token request timed out")
                _syncState.value = SyncState.Error("请求超时")
            }

            token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Token request failed: ${e.message}", e)
            _syncState.value = SyncState.Error(e.message ?: "请求失败")
            null
        } finally {
            isRequesting.set(false)
            pendingContinuation = null
        }
    }

    /**
     * 接收来自手机的 Token
     *
     * 当手机通过 BLE 发送令牌时调用此方法。
     * 通常在 BleGatewayClient 的消息回调中调用。
     *
     * @param token 从手机接收到的设备令牌
     */
    fun receiveTokenFromPhone(token: DeviceToken) {
        Log.i(TAG, "Token received from phone via BLE for user: ${token.userEmail}")

        // 保存令牌
        deviceFlowManager.saveToken(token)

        // 如果有等待中的 continuation，恢复它
        pendingContinuation?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(token) {}
            }
            pendingContinuation = null
        }

        _syncState.value = SyncState.Success(token)
    }

    /**
     * 解析 BLE 消息并处理 Token 相关操作
     *
     * 在 BleGatewayClient 收到消息时调用此方法进行解析。
     *
     * @param json BLE 消息的 JSON 字符串
     */
    fun handleBleMessage(json: String) {
        try {
            val message = jsonFormat.decodeFromString(TokenBleMessage.serializer(), json)
            when (message.action) {
                ACTION_SEND_TOKEN -> {
                    message.token?.let { receiveTokenFromPhone(it) }
                }
                ACTION_TOKEN_REFRESHED -> {
                    message.token?.let { receiveTokenFromPhone(it) }
                }
                ACTION_TOKEN_EXPIRED -> {
                    Log.w(TAG, "Phone reported token expired")
                    _syncState.value = SyncState.Error("令牌已过期")
                }
                ACTION_ERROR -> {
                    val errorMsg = message.error ?: "未知错误"
                    Log.e(TAG, "Phone returned error: $errorMsg")
                    _syncState.value = SyncState.Error(errorMsg)
                    // 如果有等待中的 continuation，以 null 恢复
                    pendingContinuation?.let { cont ->
                        if (cont.isActive) {
                            cont.resume(null) {}
                        }
                        pendingContinuation = null
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown BLE action: ${message.action}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse BLE message: ${e.message}")
        }
    }

    /**
     * 请求手机刷新 Token
     *
     * 当手表检测到令牌过期时，通过 BLE 请求手机刷新令牌。
     * 手机使用 refresh_token 向 OAuth 服务器申请新令牌，然后回复给手表。
     *
     * @return DeviceToken? 成功返回新令牌，失败返回 null
     */
    suspend fun requestRefreshFromPhone(): DeviceToken? {
        val currentToken = deviceFlowManager.getToken() ?: run {
            Log.w(TAG, "No token available to refresh")
            return null
        }

        if (!isRequesting.compareAndSet(false, true)) {
            Log.w(TAG, "Refresh request already in progress")
            return null
        }

        _syncState.value = SyncState.Refreshing
        Log.i(TAG, "Requesting token refresh from phone...")

        return try {
            // 构建刷新请求消息
            val refreshMsg = TokenBleMessage(
                action = ACTION_REFRESH_TOKEN,
                refreshToken = currentToken.refreshToken
            )
            val requestJson = jsonFormat.encodeToString(refreshMsg)

            // 通过 BLE 发送刷新请求
            val sent = bleClient.sendJson(requestJson)
            if (!sent) {
                Log.e(TAG, "Failed to send refresh request via BLE")
                _syncState.value = SyncState.Error("BLE 发送失败")
                return null
            }

            // 等待响应（带超时）
            val token = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation = continuation
                    continuation.invokeOnCancellation {
                        pendingContinuation = null
                    }
                }
            }

            if (token != null) {
                Log.i(TAG, "Token refreshed successfully")
                deviceFlowManager.saveToken(token)
                _syncState.value = SyncState.Success(token)
            } else {
                Log.w(TAG, "Token refresh timed out")
                _syncState.value = SyncState.Error("刷新超时")
            }

            token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed: ${e.message}", e)
            _syncState.value = SyncState.Error(e.message ?: "刷新失败")
            null
        } finally {
            isRequesting.set(false)
            pendingContinuation = null
        }
    }

    // ---- 自动刷新 ----

    /**
     * 检查当前保存的 Token 是否过期
     * @return Boolean true 表示已过期或没有 Token
     */
    fun isTokenExpired(): Boolean {
        return deviceFlowManager.isTokenExpired()
    }

    /**
     * 启动自动刷新定时任务
     *
     * 定期检查令牌是否过期，过期时自动通过 BLE 请求刷新。
     * 应在应用启动时调用。
     */
    fun startAutoRefresh() {
        stopAutoRefresh() // 先停止已有的任务

        autoRefreshJob = scope.launch {
            Log.i(TAG, "Auto refresh started")
            while (isActive) {
                try {
                    delay(AUTO_REFRESH_INTERVAL_MS)

                    if (!isActive) break

                    if (isTokenExpired()) {
                        Log.i(TAG, "Token expired, auto refreshing...")
                        val result = requestRefreshFromPhone()
                        if (result == null) {
                            Log.w(TAG, "Auto refresh failed, will retry next cycle")
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Auto refresh error: ${e.message}")
                }
            }
        }
    }

    /**
     * 停止自动刷新定时任务
     */
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        Log.d(TAG, "Auto refresh stopped")
    }

    /**
     * 自动刷新 — 如果令牌过期则立即刷新
     *
     * 适用于在检测到 401 错误后立即调用。
     * @return DeviceToken? 刷新成功返回新令牌，否则返回 null
     */
    suspend fun autoRefresh(): DeviceToken? {
        if (!isTokenExpired()) {
            return deviceFlowManager.getToken()
        }
        return requestRefreshFromPhone()
    }

    // ---- BLE 辅助方法 ----

    /**
     * 检查 BLE 是否已连接
     */
    fun isBleConnected(): Boolean {
        return bleClient.isConnected()
    }

    // ---- 生命周期 ----

    /**
     * 释放资源，取消所有挂起的操作
     */
    fun dispose() {
        stopAutoRefresh()
        pendingContinuation?.let { cont ->
            if (cont.isActive) {
                cont.cancel()
            }
            pendingContinuation = null
        }
        scope.cancel()
        Log.d(TAG, "TokenSyncManager disposed")
    }
}
