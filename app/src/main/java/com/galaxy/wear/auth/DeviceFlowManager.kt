package com.galaxy.wear.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.galaxy.wear.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OAuth 2.0 Device Flow (RFC 8628) 管理器
 *
 * 负责整个设备授权码流程：
 * 1. 调用后端 /auth/oauth/device/start 获取用户码和设备码
 * 2. 轮询 /auth/oauth/device/poll 直到用户授权完成
 * 3. 安全保存令牌到 EncryptedSharedPreferences
 *
 *  Wear OS 屏幕太小无法完成网页 OAuth，此流程让手表显示授权码和 URL，
 *  用户在手机/电脑上打开 URL 输入授权码完成授权。
 */
class DeviceFlowManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceFlowManager"
        /** EncryptedSharedPreferences 文件名 */
        private const val PREFS_FILE = "galaxy_auth"
        /** 设备令牌存储键名 */
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        /** 默认轮询间隔（毫秒）—— RFC 8628 §3.3 未下发 interval 时按 5s */
        private const val POLL_INTERVAL_MS = 5000L
        /** 轮询超时（毫秒） */
        private const val POLL_TIMEOUT_MS = 1800_000L // 30 分钟
    }

    /** 设备令牌数据类 */
    @Serializable
    data class DeviceToken(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("refresh_token")
        val refreshToken: String,
        @SerialName("expires_at")
        val expiresAt: Long,
        @SerialName("user_email")
        val userEmail: String,
        @SerialName("user_name")
        val userName: String
    ) {
        /** 检查令牌是否已过期（预留 60 秒缓冲） */
        fun isExpired(): Boolean = System.currentTimeMillis() >= (expiresAt - 60000)
    }

    /** 设备授权启动响应 */
    @Serializable
    data class DeviceAuthResponse(
        @SerialName("user_code")
        val userCode: String,
        @SerialName("verification_uri")
        val verificationUri: String,
        @SerialName("verification_uri_complete")
        val verificationUriComplete: String? = null,
        @SerialName("device_code")
        val deviceCode: String,
        @SerialName("expires_in")
        val expiresIn: Int,
        @SerialName("interval")
        val interval: Int = 5
    )

    /** 轮询响应 */
    @Serializable
    data class PollResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null,
        @SerialName("user_email")
        val userEmail: String? = null,
        @SerialName("user_name")
        val userName: String? = null,
        /** 错误码：authorization_pending / slow_down / expired_token / access_denied */
        @SerialName("error")
        val error: String? = null,
        @SerialName("error_description")
        val errorDescription: String? = null
    )

    /** 流程结果回调 */
    sealed class DeviceFlowResult {
        /** 授权成功 */
        data class Success(val token: DeviceToken) : DeviceFlowResult()
        /** 授权失败 */
        data class Error(val code: String, val message: String) : DeviceFlowResult()
        /** 超时 */
        data object Timeout : DeviceFlowResult()
        /** 用户取消 */
        data object Cancelled : DeviceFlowResult()
    }

    /** 流程状态 */
    sealed class FlowState {
        /** 空闲 */
        data object Idle : FlowState()
        /** 已获取用户码，等待用户授权 */
        data class AwaitingAuth(
            val userCode: String,
            val verificationUri: String,
            val deviceCode: String,
            val expiresIn: Int,
            val elapsedSeconds: Int = 0
        ) : FlowState()
        /** 轮询中 */
        data class Polling(
            val userCode: String,
            val deviceCode: String,
            val remainingSeconds: Int
        ) : FlowState()
        /** 授权成功 */
        data class Success(val token: DeviceToken) : FlowState()
        /** 出错 */
        data class Error(val message: String) : FlowState()
    }

    /** 状态监听器 */
    interface FlowStateListener {
        fun onStateChanged(state: FlowState)
    }

    // ---- HTTP 客户端 ----

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(jsonFormat) }
            install(Logging) {
                logger = Logger.DEFAULT
                level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
            }
        }
    }

    // ---- EncryptedSharedPreferences ----

    /**
     * 获取加密 SharedPreferences 实例
     * 使用 AES256-GCM 加密保护令牌数据
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // PR-CR2: NEVER fall back to plaintext — token storage must be encrypted
            Log.e(TAG, "EncryptedSharedPreferences init failed: ${e.message}")
            throw SecurityException("无法安全存储认证令牌，设备不满足安全要求", e)
        }
    }

    // ---- 状态监听 ----

    private var stateListener: FlowStateListener? = null
    // ROUND-4-FIX(取消标志跨线程可见性): cancelFlow() 在主线程写,pollForToken
    // 的 while 循环在 IO 协程线程读 —— 无 @Volatile 时 JMM 不保证写入对轮询线程
    // 可见,用户点了"取消"后轮询仍可能一直跑到 30 分钟超时(期间持续打点后端)。
    @Volatile
    private var isCancelled = false

    fun setStateListener(listener: FlowStateListener?) {
        this.stateListener = listener
    }

    private fun emitState(state: FlowState) {
        Log.d(TAG, "State: ${state::class.simpleName}")
        stateListener?.onStateChanged(state)
    }

    // ---- 核心流程 ----

    /**
     * 启动设备授权流程（OAuth 2.0 Device Flow）
     *
     * 1. 调用后端 POST /auth/oauth/device/start 获取用户码和设备码
     * 2. 进入轮询状态，等待用户在手机/电脑上完成授权
     * 3. 授权成功后返回令牌
     *
     * @param provider OAuth 提供商，默认 "google"
     * @param serverUrl 后端服务器地址
     * @return DeviceFlowResult 授权结果
     */
    suspend fun startDeviceAuth(
        provider: String = "google",
        serverUrl: String
    ): DeviceFlowResult {
        isCancelled = false
        emitState(FlowState.Idle)

        return try {
            // 1. 调用后端启动 Device Flow
            val authResponse = requestDeviceCode(provider, serverUrl)
            if (authResponse == null) {
                emitState(FlowState.Error("无法获取授权码"))
                return DeviceFlowResult.Error("network_error", "无法连接到授权服务器")
            }

            Log.i(TAG, "Device code received: ${authResponse.deviceCode}")
            Log.i(TAG, "User code: ${authResponse.userCode}")
            Log.i(TAG, "Verification URI: ${authResponse.verificationUri}")

            // 2. 进入等待授权状态（UI 显示用户码和 URL）
            emitState(
                FlowState.AwaitingAuth(
                    userCode = authResponse.userCode,
                    verificationUri = authResponse.verificationUri,
                    deviceCode = authResponse.deviceCode,
                    expiresIn = authResponse.expiresIn
                )
            )

            // 3. 开始轮询令牌
            val pollResult = pollForToken(
                deviceCode = authResponse.deviceCode,
                serverUrl = serverUrl,
                expiresIn = authResponse.expiresIn,
                userCode = authResponse.userCode,
                intervalSeconds = authResponse.interval
            )

            when {
                isCancelled -> {
                    emitState(FlowState.Idle)
                    DeviceFlowResult.Cancelled
                }
                pollResult == null -> {
                    // FlowState 无 Timeout 变体（仅 DeviceFlowResult 有），
                    // 超时以 Error 状态呈现给 UI，结果仍返回 DeviceFlowResult.Timeout。
                    emitState(FlowState.Error("授权超时"))
                    DeviceFlowResult.Timeout
                }
                pollResult.error != null -> {
                    val msg = pollResult.errorDescription ?: pollResult.error
                    emitState(FlowState.Error(msg))
                    DeviceFlowResult.Error(pollResult.error, msg)
                }
                pollResult.accessToken != null -> {
                    val expiresAt = System.currentTimeMillis() + (pollResult.expiresIn ?: 3600) * 1000
                    val token = DeviceToken(
                        accessToken = pollResult.accessToken,
                        refreshToken = pollResult.refreshToken ?: "",
                        expiresAt = expiresAt,
                        userEmail = pollResult.userEmail ?: "",
                        userName = pollResult.userName ?: ""
                    )
                    saveToken(token)
                    emitState(FlowState.Success(token))
                    DeviceFlowResult.Success(token)
                }
                else -> {
                    emitState(FlowState.Error("未知错误"))
                    DeviceFlowResult.Error("unknown", "未知错误")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Device auth failed: ${e.message}", e)
            val msg = e.message ?: "未知错误"
            emitState(FlowState.Error(msg))
            DeviceFlowResult.Error("exception", msg)
        }
    }

    /**
     * 取消当前授权流程
     */
    fun cancelFlow() {
        isCancelled = true
        emitState(FlowState.Idle)
    }

    /**
     * 调用后端获取设备码
     *
     * POST /auth/oauth/device/start
     * Body: {"provider": "google"}
     * Response: {"user_code": "...", "verification_uri": "...", "device_code": "...", "expires_in": 1800}
     */
    private suspend fun requestDeviceCode(
        provider: String,
        serverUrl: String
    ): DeviceAuthResponse? {
        val url = buildApiUrl(serverUrl, "/auth/oauth/device/start")
        Log.d(TAG, "Requesting device code from: $url")

        return try {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("provider", provider)
                })
            }

            if (response.status == HttpStatusCode.OK) {
                response.body<DeviceAuthResponse>()
            } else {
                Log.e(TAG, "Device code request failed: ${response.status}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Device code request error: ${e.message}")
            null
        }
    }

    /**
     * 轮询令牌
     *
     * POST /auth/oauth/device/poll
     * Body: {"device_code": "..."}
     * Response:
     *   - 授权成功: {"access_token": "...", "refresh_token": "...", "expires_in": 3600, ...}
     *   - 等待中: {"error": "authorization_pending"}
     *   - 已过期: {"error": "expired_token"}
     *   - 已拒绝: {"error": "access_denied"}
     *
     * @param deviceCode 设备码
     * @param serverUrl 后端服务器地址
     * @param expiresIn 设备码有效期（秒）
     * @param userCode 用户码（仅用于状态回调展示，不参与请求）
     * @param intervalSeconds RFC 8628 §3.3 服务端返回的最小轮询间隔（秒），缺省 5 秒
     * @return PollResponse? 成功返回响应，超时返回 null
     */
    suspend fun pollForToken(
        deviceCode: String,
        serverUrl: String,
        expiresIn: Int = 1800,
        userCode: String = "",
        intervalSeconds: Int = (POLL_INTERVAL_MS / 1000L).toInt()
    ): PollResponse? {
        val url = buildApiUrl(serverUrl, "/auth/oauth/device/poll")
        val startTime = System.currentTimeMillis()
        val timeoutMs = (expiresIn * 1000L).coerceAtMost(POLL_TIMEOUT_MS)
        // ROUND-2-FIX (RFC 8628 §3.5): 轮询间隔必须采用服务端下发的 interval,
        // 而不是写死的 5s —— 服务端配 interval=10 时旧实现仍每 5s 轮询,
        // 触发 slow_down 限流甚至封禁;且 slow_down 后间隔须对后续所有请求
        // 永久 +5s,旧实现只延迟一次、continue 还跳过当次正常间隔。
        var pollIntervalMs = (intervalSeconds.coerceAtLeast(1)) * 1000L

        Log.d(TAG, "Starting token polling, timeout: ${timeoutMs}ms, interval: ${pollIntervalMs}ms")

        while (currentCoroutineContext().isActive && !isCancelled) {
            val elapsed = System.currentTimeMillis() - startTime
            val remainingSeconds = ((timeoutMs - elapsed) / 1000).toInt().coerceAtLeast(0)

            if (elapsed >= timeoutMs) {
                Log.w(TAG, "Token polling timed out")
                return null
            }

            // ROUND-2-FIX: Polling 状态带上真实 userCode —— 轮询立即开始,
            // 若 Polling 不带 userCode,UI 的授权码展示态会被轮询态瞬间覆盖,
            // 用户根本来不及看到授权码。
            emitState(FlowState.Polling(userCode, deviceCode, remainingSeconds))

            try {
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("device_code", deviceCode)
                    })
                }

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val body: PollResponse = response.body()
                        // 检查是否有错误
                        when (body.error) {
                            "authorization_pending" -> {
                                Log.d(TAG, "Authorization pending, retry in ${pollIntervalMs}ms")
                            }
                            "slow_down" -> {
                                // RFC 8628 §3.5: on slow_down the interval MUST be
                                // increased by 5 seconds for this AND ALL SUBSEQUENT
                                // requests (then fall through to the normal delay).
                                pollIntervalMs += 5000L
                                Log.d(TAG, "Slow down requested — interval increased to ${pollIntervalMs}ms")
                            }
                            "expired_token" -> {
                                Log.w(TAG, "Device code expired")
                                return PollResponse(error = "expired_token", errorDescription = "授权码已过期，请重试")
                            }
                            "access_denied" -> {
                                Log.w(TAG, "Access denied by user")
                                return PollResponse(error = "access_denied", errorDescription = "用户已拒绝授权")
                            }
                            null -> {
                                // 没有错误，授权成功
                                if (body.accessToken != null) {
                                    Log.i(TAG, "Token received successfully")
                                    return body
                                }
                            }
                            else -> {
                                Log.w(TAG, "Unknown error: ${body.error}")
                                return body
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "Poll request failed: ${response.status}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Poll request error: ${e.message}")
            }

            delay(pollIntervalMs)
        }

        // 被取消或协程不再活跃
        return null
    }

    // ---- 令牌持久化 ----

    /**
     * 保存令牌到 EncryptedSharedPreferences
     * 使用 AES256-GCM 加密保护敏感数据
     */
    fun saveToken(token: DeviceToken) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_ACCESS_TOKEN, token.accessToken)
                .putString(KEY_REFRESH_TOKEN, token.refreshToken)
                .putLong(KEY_EXPIRES_AT, token.expiresAt)
                .putString(KEY_USER_EMAIL, token.userEmail)
                .putString(KEY_USER_NAME, token.userName)
                .apply()
            Log.i(TAG, "Token saved for user: ${token.userEmail}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save token: ${e.message}", e)
        }
    }

    /**
     * 从 EncryptedSharedPreferences 读取令牌
     * @return DeviceToken? 如果没有保存的令牌则返回 null
     */
    fun getToken(): DeviceToken? {
        return try {
            val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
            val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null) ?: ""
            val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
            val userEmail = encryptedPrefs.getString(KEY_USER_EMAIL, null) ?: ""
            val userName = encryptedPrefs.getString(KEY_USER_NAME, null) ?: ""

            if (accessToken.isNullOrEmpty() || expiresAt == 0L) {
                null
            } else {
                DeviceToken(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    userEmail = userEmail,
                    userName = userName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read token: ${e.message}")
            null
        }
    }

    /**
     * 清除保存的令牌
     */
    fun clearToken() {
        try {
            encryptedPrefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_USER_NAME)
                .apply()
            Log.i(TAG, "Token cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear token: ${e.message}")
        }
    }

    /**
     * 检查是否有已保存的令牌
     */
    fun hasToken(): Boolean = getToken() != null

    /**
     * 检查当前令牌是否过期
     */
    fun isTokenExpired(): Boolean {
        val token = getToken() ?: return true
        return token.isExpired()
    }

    // ---- 辅助方法 ----

    /**
     * 构建完整的 API URL
     *
     * ROUND-2-FIX: 存储的 server_url 是 WebSocket 地址(mDNS/Tailscale 发现
     * 产出 ws:// / wss://),而设备流两个端点是普通 HTTP POST —— OkHttp 直接
     * 拒绝 ws/wss scheme,导致"自动发现网关 → 设备登录"链路永远失败。
     * 这里把 ws→http、wss→https 归一化后再拼接。
     */
    private fun buildApiUrl(baseUrl: String, path: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val url = when {
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            else -> trimmed
        }
        val cleanPath = path.trimStart('/')
        return "$url/$cleanPath"
    }

    /**
     * 释放资源
     */
    fun dispose() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            Log.w(TAG, "Dispose error: ${e.message}")
        }
    }
}
