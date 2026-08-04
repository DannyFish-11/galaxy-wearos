package com.galaxy.wear.data

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.galaxy.wear.BuildConfig
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.sensing.InterruptibilityReport
import com.galaxy.wear.sensing.toWirePayload
import com.ufo.galaxy.shared.protocol.ReconnectionConfig
import com.ufo.galaxy.shared.protocol.AuthMessage
import com.ufo.galaxy.shared.protocol.MsgType
// K2-FIX(801): AipTransportManager 位于共享 transport 模块,补全其包路径导入
// (与 GalaxyWearApplication.kt 中的引用一致)。
import com.ufo.galaxy.transport.AipTransportManager
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
// K2-FIX(187/188/189): 请求构建器扩展 url(urlString: String) 与 header(...) 位于
// io.ktor.client.request 包。缺失该导入时 url("...") 只匹配到 URLBuilder 块重载,
// 导致 "expected Function2<URLBuilder,URLBuilder,Unit>" 参数类型不匹配。
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
// K2-FIX(1030): 显式导入 kotlinx.serialization.Serializable,避免 @Serializable
// 被通配符导入带入的内部 typealias / java.io.Serializable 遮蔽。显式导入优先级高于
// 通配符导入,恢复后编译器插件才会为 AIPMessage 生成 serializer() 伴生方法,
// 从而连带修复所有 .serializer() 未解析与 T 类型推断/重载歧义问题。
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import okhttp3.CertificatePinner
import kotlin.coroutines.cancellation.CancellationException

/**
 * W21-FIX: AIP v3 WebSocket Client (Wear OS variant)
 * Naming is unified to "AIP" across all project files.
 *
 * Fixes from audit round 2:
 * - C4: MessagePack JSON conversion with recursive Value-to-JsonElement
 * - H2: SSL certificate pinning for OkHttp engine
 * - Heartbeat: pong timeout detection (missing pong -> reconnect)
 * - Reconnect: exponential backoff with 30s cap and jitter
 * - Thread-safe connect/disconnect with Mutex
 * - Proper cancellation propagation
 * - Resource cleanup on all paths
 */
class AIPClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val useBinaryFormat: Boolean = false,
) : com.galaxy.wear.network.GatewayClient {
    // X-API-CR1: Simplified JSON format — polymorphic serialization removed
    // because AIPMessage is now a plain data class with MsgType enum.
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // W6-FIX: SSL certificate pinning with production domain pins.
    // SECURITY: Pins must be injected via BuildConfig from CI/CD. If not configured,
    // certificate pinning is disabled to prevent broken connections from invalid placeholders.
    private val certificatePinner: CertificatePinner? by lazy {
        val primaryPin = BuildConfig.CERT_PIN_PRIMARY
        val backupPin = BuildConfig.CERT_PIN_BACKUP
        if (primaryPin.isNullOrBlank() || primaryPin.contains("Placeholder")) {
            Log.w(GalaxyWearApplication.TAG, "SSL pinning disabled: no valid pins configured")
            null
        } else {
            // Round-4 HIGH: pin production domain, not the placeholder
            val pinDomain = "galaxy.ufo.ai"
            CertificatePinner.Builder()
                .add(pinDomain, primaryPin)
                .apply { if (!backupPin.isNullOrBlank()) add(pinDomain, backupPin) }
                .build()
        }
    }

    // CRITICAL-FIX: HttpClient is lazy-created so disconnect() can reset without
    // killing the client. Only dispose() permanently closes it.
    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                // H2: Apply certificate pinner to OkHttp if configured
                val okBuilder = okhttp3.OkHttpClient.Builder()
                certificatePinner?.let { okBuilder.certificatePinner(it) }
                preconfigured = okBuilder.build()
            }
            install(WebSockets)
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

    private var _session: DefaultClientWebSocketSession? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val messageId = AtomicInteger(0)
    private val connectMutex = Mutex()
    private var isDisposed = false

    // PR-RECONNECT-UNIFIED: Use shared ReconnectionConfig constants.
    // Heartbeat: track last pong time for timeout detection (thread-safe)
    private val lastPongTimeMs = AtomicLong(0L)
    private val pongTimeoutMs = ReconnectionConfig.HEARTBEAT_TIMEOUT_MS // 10s pong timeout (unified)

    // Reconnect: exponential backoff state (unified with Android).
    // ROUND-2-FIX: zero-based attempt counter instead of a delay field. The old
    // delay field only ever grew inside scheduleReconnect's inner catch — but
    // connect() swallows its own exceptions and reschedules internally, so that
    // catch never ran and every retry was pinned at a constant ~10s forever
    // (no exponential backoff, no cap). Reset to 0 on TCP connect and auth_ok.
    private var reconnectAttempt = 0

    // PR-CR1: AtomicBoolean (not ThreadLocal) for coroutine-safe routing loop detection.
    // ThreadLocal is unreliable in coroutines ( Dispatchers.IO thread migration ).
    private val _isRouting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(AIPConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AIPConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<AIPMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<AIPMessage> = _messages.asSharedFlow()

    /**
     * ROUND-3-FIX: guaranteed delivery to upstream observers.
     * `_messages` is a MutableSharedFlow(extraBufferCapacity = 64) and every producer
     * used tryEmit(), which DROPS the value (returns false) when the 64-slot buffer is
     * momentarily full — e.g. a burst of state_event/task_progress frames arriving while
     * the single collector is briefly blocked on a binder call (startForegroundService /
     * Vibrator). A dropped DECISION_REQUEST means the HITL loop waits on a human who was
     * never shown the decision. Fall back to a suspending emit so nothing is silently lost.
     */
    private fun emitMessage(msg: AIPMessage) {
        if (_messages.tryEmit(msg)) return
        scope.launch {
            try {
                _messages.emit(msg)
            } catch (e: CancellationException) {
                // scope disposed — nothing more to do
            } catch (e: Exception) {
                Log.w(GalaxyWearApplication.TAG, "emitMessage fallback failed: ${e.message}")
            }
        }
    }

    private var serverUrl: String = ""
    private var token: String = ""
    private var deviceId: String = ""

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    /**
     * Build WebSocket URL with auto-path attachment.
     * W2-FIX: Unified port 9000 across all configurations.
     * W13-FIX: If URL has no /ws path, appends /{API_VERSION}/ws/device/{deviceId}.
     */
    private fun buildWsUrl(baseUrl: String, devId: String): String {
        val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return when {
            url.contains("/ws") -> url  // Already has path, don't modify
            else -> {
                // R5-FIX: Use V2 gateway path without API_VERSION prefix
                "$url/ws/device/$devId"
            }
        }
    }

    suspend fun connect(url: String, authToken: String, devId: String) {
        connectMutex.withLock {
            if (isDisposed) {
                Log.w(GalaxyWearApplication.TAG, "AIPClient already disposed")
                return
            }

            // Guard against concurrent connect
            if (_connectionState.value == AIPConnectionState.CONNECTED ||
                _connectionState.value == AIPConnectionState.AUTHENTICATED ||
                _connectionState.value == AIPConnectionState.CONNECTING
            ) {
                Log.d(GalaxyWearApplication.TAG, "Connection already in progress or established")
                return
            }

            serverUrl = url
            token = authToken
            deviceId = devId
            _connectionState.value = AIPConnectionState.CONNECTING
        }

        try {
            var wsUrl = when {
                url.startsWith("ws://") || url.startsWith("wss://") -> url
                url.startsWith("https://") -> url.replace("https://", "wss://")
                url.startsWith("http://") -> url.replace("http://", "ws://")
                else -> "wss://$url" // Default to secure WebSocket if no scheme given
            }

            // Auto-append /ws/device/{deviceId} if path is missing
            wsUrl = buildWsUrl(wsUrl, devId)

            // DUAL-FORMAT: opt-in to MessagePack via query parameter
            if (useBinaryFormat) {
                wsUrl = wsUrl + if (wsUrl.contains("?")) "&format=msgpack" else "?format=msgpack"
            }

            client.webSocket({
                url(wsUrl)
                header("Authorization", "Bearer $authToken")  // HTTP Bearer token auth
                header("X-Device-ID", devId)                  // Device identification
            }) {
                connectMutex.withLock {
                    // FIX: Only guard against disposed state. When WebSocket connects
                    // successfully, always set CONNECTED even if previous state was ERROR.
                    if (isDisposed) return@withLock
                    _session = this
                    _connectionState.value = AIPConnectionState.CONNECTED
                }
                if (isDisposed) return@webSocket

                Log.i(GalaxyWearApplication.TAG, "WebSocket connected: $wsUrl")

                // Reset reconnect backoff on successful connection (unified initial delay)
                reconnectAttempt = 0

                // PR-AUTH-UNIFIED: Send canonical AuthMessage (shared with Android).
                // The HTTP Authorization header is retained as a compatibility fallback
                // for legacy gateways; the WebSocket auth message is the canonical auth path.
                try {
                    val authMsg = AuthMessage(
                        token = token,
                        deviceId = deviceId,
                        deviceType = AuthMessage.DEVICE_TYPE_WEAROS,
                        protocolVersion = "3.0"
                    )
                    sendJson(AIPMessage(
                        type = MsgType.AUTH,
                        payload = jsonFormat.encodeToJsonElement(AuthMessage.serializer(), authMsg),
                        deviceId = deviceId,
                        traceId = "auth_${System.currentTimeMillis()}"
                    ))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(GalaxyWearApplication.TAG, "Auth send failed: ${e.message}")
                    return@webSocket
                }

                // AUTH-WATCHDOG: if the gateway completes the WS upgrade but never
                // answers auth_ok/auth_failed, the heartbeat is not running yet and
                // the session would hang forever. Close it so the reconnect logic
                // (finally-block below) can take over.
                val authWatchdogJob = scope.launch {
                    delay(AUTH_TIMEOUT_MS)
                    if (!isDisposed && _connectionState.value != AIPConnectionState.AUTHENTICATED) {
                        Log.w(GalaxyWearApplication.TAG, "Auth timeout after ${AUTH_TIMEOUT_MS}ms — closing session")
                        runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "auth timeout")) }
                    }
                }

                // Listen for messages
                try {
                    for (frame in incoming) {
                        if (isDisposed) break
                        // ROUND-4-FIX(跨仓契约错读): 心跳活性不能只认 "pong"。
                        // 本端心跳发的是 AIP JSON {"type":"ping"},而 V2 网关把
                        // PING 委派给 android_bridge 后按"graceful no-op"处理
                        // (android_bridge: "ACK/PING need no handler"),整个网关
                        // 从不下发 "pong";"heartbeat_ack" 也只回给 heartbeat/
                        // agent_ping 类型。于是 lastPongTimeMs 自 startHeartbeat()
                        // 起从不更新,interval+timeout(默认 30s)后 pong-timeout
                        // 分支必然误判"链路已死"——关闭健康会话并重连,循环往复,
                        // 形成每 ~30s 一次的重连风暴。任何入站帧都证明链路活着,
                        // 故在此按"收到即活"更新;真正的死链(无任何帧到达)仍会
                        // 被 pong-timeout 正确捕获。
                        lastPongTimeMs.set(System.currentTimeMillis())
                        when (frame) {
                            is Frame.Text -> {
                                // P2-FIX: Enforce maximum message size for text frames too
                                val textBytes = frame.data.size
                                if (textBytes > MAX_MESSAGE_SIZE) {
                                    Log.w(GalaxyWearApplication.TAG, "Text message too large: $textBytes bytes, max=$MAX_MESSAGE_SIZE")
                                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Message exceeds maximum size"))
                                    break
                                }
                                handleMessage(frame.readText())
                            }
                            is Frame.Binary -> {
                                // P2-FIX: Enforce maximum message size to prevent OOM on malicious payloads
                                if (frame.data.size > MAX_MESSAGE_SIZE) {
                                    Log.w(GalaxyWearApplication.TAG, "Message too large: ${frame.data.size} bytes, max=$MAX_MESSAGE_SIZE")
                                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Message exceeds maximum size"))
                                    break
                                }
                                // DUAL-FORMAT: handle MessagePack binary frames
                                // C4: Try MessagePack first, then fall back to UTF-8 text
                                val unpacked = unpackMsgpack(frame.data)
                                if (unpacked != null) {
                                    handleMessage(unpacked)
                                } else {
                                    try {
                                        // K2-FIX(257): readText() 是 Frame.Text 的扩展,不能用于 Frame.Binary。
                                        // 此处是 MessagePack 解包失败后的 UTF-8 文本回退,直接对字节解码。
                                        val rawText = frame.data.decodeToString()
                                        handleMessage(rawText)
                                    } catch (e: Exception) {
                                        Log.w(GalaxyWearApplication.TAG, "Failed to parse binary frame: ${e.message}")
                                    }
                                }
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()
                                Log.w(GalaxyWearApplication.TAG, "WebSocket closed: ${reason?.message}")
                                break
                            }
                            is Frame.Ping -> outgoing.send(Frame.Pong(frame.data))
                            is Frame.Pong -> {
                                // Heartbeat: record pong receipt time
                                lastPongTimeMs.set(System.currentTimeMillis())
                            }
                            else -> {}
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(GalaxyWearApplication.TAG, "WebSocket receive cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(GalaxyWearApplication.TAG, "WebSocket receive error: ${e.message}")
                } finally {
                    authWatchdogJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            Log.d(GalaxyWearApplication.TAG, "Connection cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(GalaxyWearApplication.TAG, "WebSocket error: ${e.message}")
            val current = _connectionState.value
            if (current != AIPConnectionState.DISCONNECTED) {
                _connectionState.value = AIPConnectionState.ERROR
            }
            scheduleReconnect()
        } finally {
            heartbeatJob?.cancel()
            val endedHealthySession = connectMutex.withLock {
                _session = null
                val current = _connectionState.value
                if (current == AIPConnectionState.CONNECTED ||
                    current == AIPConnectionState.AUTHENTICATED ||
                    current == AIPConnectionState.CONNECTING
                ) {
                    _connectionState.value = AIPConnectionState.DISCONNECTED
                }
                // A session that reached CONNECTED/AUTHENTICATED and ends here
                // WITHOUT an exception (no catch ran) and WITHOUT a user
                // disconnect (those set DISCONNECTED first) means the server
                // closed a healthy connection cleanly.
                current == AIPConnectionState.CONNECTED || current == AIPConnectionState.AUTHENTICATED
            }
            // RECONNECT-FIX: auto-reconnect after a server-initiated close of a
            // healthy session; otherwise the watch silently stayed offline until
            // the next network change. user disconnect()/dispose() never reaches
            // this branch (state is DISCONNECTED / isDisposed by then).
            if (endedHealthySession && !isDisposed) {
                scheduleReconnect()
            }
        }
    }

    suspend fun disconnect() {
        connectMutex.withLock {
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            _session?.close()
            _session = null
            _connectionState.value = AIPConnectionState.DISCONNECTED
            // CRITICAL-FIX: Do NOT close HttpClient here — it's lazy-created and
            // must survive disconnect() so connect() can be called again.
            // Only dispose() permanently closes the client.
            // SECURITY: Clear sensitive credentials from memory
            serverUrl = ""
            token = ""
            deviceId = ""
        }
    }

    // PR-AIP-UNIFIED-WEAR: GatewayClient implementation for unified transport.
    override fun isConnected(): Boolean {
        return _connectionState.value == AIPConnectionState.CONNECTED ||
               _connectionState.value == AIPConnectionState.AUTHENTICATED
    }

    override fun sendJson(json: String): Boolean {
        // If already inside a TransportManager routing chain, break the loop by
        // sending directly via WebSocket instead of routing back to TransportManager.
        if (_isRouting.get()) {
            return try {
                val msg = jsonFormat.decodeFromString(AIPMessage.serializer(), json)
                // CRITICAL-FIX: Use scope.launch (not runBlocking) to avoid blocking IO thread.
                // GatewayClient.sendJson is a fire-and-forget API; Boolean indicates acceptance.
                scope.launch {
                    runCatching { sendJson(msg) }
                        .onFailure { ex ->
                            Log.w(GalaxyWearApplication.TAG, "Direct WS send failed in routing break: ${ex.message}")
                        }
                }
                true
            } catch (e: Exception) {
                Log.w(GalaxyWearApplication.TAG, "Routing-break send failed: ${e.message}")
                false
            }
        }

        return try {
            val msg = jsonFormat.decodeFromString(AIPMessage.serializer(), json)
            _isRouting.set(true)
            try {
                // CRITICAL-FIX: Use scope.launch instead of runBlocking(scope.coroutineContext)
                // to avoid blocking Dispatchers.IO threads. GatewayClient.sendJson is a
                // fire-and-forget API; the Boolean return indicates message was accepted
                // for delivery, not delivery confirmation.
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withTimeout(5000) {
                            sendJson(msg)
                        }
                    }.onFailure { ex ->
                        Log.w(GalaxyWearApplication.TAG, "GatewayClient.sendJson send failed: ${ex.message}")
                    }
                }
                true
            } finally {
                _isRouting.set(false)
            }
        } catch (e: CancellationException) {
            Log.w(GalaxyWearApplication.TAG, "GatewayClient.sendJson cancelled: scope inactive")
            false
        } catch (e: Exception) {
            Log.w(GalaxyWearApplication.TAG, "GatewayClient.sendJson failed: ${e.message}")
            false
        }
    }

    fun dispose() {
        // Non-blocking dispose for Activity.onDestroy
        isDisposed = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        if (scope.isActive) {
            scope.launch {
                disconnect()
                runCatching { client.close() }
            }
        } else {
            // Scope already cancelled, force cleanup synchronously
            runCatching {
                // K2-FIX(396): WebSocketSession.close() 是 suspend,此处 scope 已取消无法启动协程。
                // WebSocketSession 实现了 CoroutineScope,用非挂起的 cancel() 强制终止会话即可。
                _session?.cancel()
                _session = null
                serverUrl = ""
                token = ""
                deviceId = ""
                client.close()
            }
        }
    }

    // -----------------------------------------------------------------
    // Messaging
    // -----------------------------------------------------------------

    // X-API-CR1: Command now uses data class with MsgType + JsonObject payload
    suspend fun sendCommand(command: String, payload: JsonObject? = null) {
        val cmdPayload = buildJsonObject {
            put("id", messageId.incrementAndGet())
            put("command", command)
            if (payload != null) {
                put("payload", payload)
            }
        }
        val msg = AIPMessage(
            type = MsgType.COMMAND,
            payload = cmdPayload,
            deviceId = deviceId,
            correlationId = "cmd_${messageId.get()}"
        )
        sendJson(msg)
    }

    suspend fun sendVoiceQuery(transcript: String) {
        sendCommand("voice_query", buildJsonObject {
            put("text", transcript)
            put("source", "wear_os")
        })
    }

    suspend fun sendPhaseReport(phase: String) {
        sendCommand("phase_report", buildJsonObject {
            put("phase", phase)
            put("device", "wear_os")
        })
    }

    /**
     * 上报可打扰性 —— 手表回答"现在能不能打扰他",而**不**交出身体数据。
     *
     * 载荷完全由 [toWirePayload] 生成,其键集合被
     * `InterruptibilityWireContractTest` 钉死为 `INTERRUPTIBILITY_WIRE_KEYS`。
     * 心率/加速度/静息基线这些原始信号只在手表本地参与运算,不经过这条路。
     */
    suspend fun sendInterruptibility(
        report: InterruptibilityReport,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        sendCommand("interruptibility", report.toWirePayload(timestampMs = timestampMs))
    }

    /**
     * HUMAN-INPUT: 发送人类决策回复到 Galaxy Mesh 网络。
     *
     * 当用户在决策通知上选择选项、语音输入或在 DecisionScreen 中操作时，
     * 调用此方法将人类输入发送到后端 OpenClawd 认知闭环。
     *
     * @param decisionId 决策唯一标识
     * @param selectedOption 用户选择的选项 ID（可为 null）
     * @param voiceInput 语音输入文本（可为 null）
     */
    suspend fun sendHumanInput(
        decisionId: String,
        selectedOption: String? = null,
        voiceInput: String? = null,
    ) {
        sendCommand("human_input", buildJsonObject {
            put("decision_id", decisionId)
            selectedOption?.let { put("selected_option", it) }
            voiceInput?.let { put("voice_input", it) }
            put("device", "wear_os")
            put("timestamp", System.currentTimeMillis())
        })
    }


    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    // X-API-CR1 + X-DATA-CR1: Handle incoming messages using unified data class format.
    // Messages from Android/Gateway may carry string type values; map them to MsgType enum.
    private fun handleMessage(raw: String) {
        try {
            val json = jsonFormat.parseToJsonElement(raw).jsonObject
            // Try MsgType enum first (new unified format), fall back to raw string
            val typeStr = json["type"]?.jsonPrimitive?.content ?: "unknown"
            val msgType = MsgType.fromValue(typeStr)

            // Route by string type for maximum compatibility with both old and new formats
            when (typeStr) {
                "auth_ok" -> {
                    _connectionState.value = AIPConnectionState.AUTHENTICATED
                    // Reset backoff on successful auth
                    reconnectAttempt = 0
                    startHeartbeat()
                }
                "auth_failed" -> {
                    Log.e(GalaxyWearApplication.TAG, "Authentication failed")
                    _connectionState.value = AIPConnectionState.ERROR
                }
                "auth_invalid" -> {
                    Log.e(GalaxyWearApplication.TAG, "Auth token invalid")
                    _connectionState.value = AIPConnectionState.ERROR
                }
                "command_result" -> {
                    // X-DATA-CR1: Emit unified AIPMessage data class with COMMAND_RESULT type
                    val resultPayload = buildJsonObject {
                        put("id", json["id"] ?: JsonPrimitive(0))
                        put("success", json["success"] ?: JsonPrimitive(false))
                        put("data", json["data"] ?: JsonNull)
                    }
                    emitMessage(AIPMessage(
                        type = MsgType.COMMAND_RESULT,
                        payload = resultPayload,
                        deviceId = deviceId,
                        correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                    ))
                }
                "event" -> {
                    // X-DATA-CR1: Emit unified AIPMessage data class with EVENT type
                    val eventPayload = buildJsonObject {
                        put("event", json["event"]?.jsonPrimitive?.content ?: "")
                        put("data", json["data"] ?: JsonObject(emptyMap()))
                    }
                    emitMessage(AIPMessage(
                        type = MsgType.EVENT,
                        payload = eventPayload,
                        deviceId = deviceId
                    ))
                }
                "pong" -> {
                    // Heartbeat: record pong receipt time for timeout detection
                    lastPongTimeMs.set(System.currentTimeMillis())
                }
                "takeover_request" -> {
                    // 诚实拒绝(相对主体原则:设备如实申报自己做不到的事)。
                    // Wear 没有执行运行时,不能接管任务;裸 ack 只表示"收到",
                    // 中心的 takeover 协调器等的是带 accepted 裁决的
                    // takeover_response——不给裁决它只能挂到超时。
                    val reqPayload = json["payload"]?.jsonObject
                    val declinePayload = buildJsonObject {
                        put("takeover_id", reqPayload?.get("takeover_id") ?: JsonPrimitive(""))
                        put("session_id", reqPayload?.get("session_id") ?: JsonPrimitive(""))
                        put("task_id", reqPayload?.get("task_id") ?: JsonPrimitive(""))
                        put("trace_id", reqPayload?.get("trace_id") ?: (json["trace_id"] ?: JsonPrimitive("")))
                        put("accepted", JsonPrimitive(false))
                        put("reason", JsonPrimitive("wearos_no_execution_runtime"))
                        put("device", JsonPrimitive("wear_os"))
                    }
                    scope.launch {
                        sendJson(AIPMessage(
                            type = MsgType.TAKEOVER_RESPONSE,
                            payload = declinePayload,
                            deviceId = deviceId,
                            correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                        ))
                    }
                    Log.i(GalaxyWearApplication.TAG, "takeover_request declined honestly (no execution runtime)")
                }
                "handoff_envelope_v2" -> {
                    // 诚实终局失败:handoff 派发方等的是终局 result/failure。
                    // 带 PR-46 跨仓 schema 门要求的双版本字段,否则终局上行会
                    // 在中心的 canonical 真相链之前被 REJECT。
                    val hoPayload = json["payload"]?.jsonObject
                    val failurePayload = buildJsonObject {
                        put("handoff_id", hoPayload?.get("handoff_id") ?: JsonPrimitive(""))
                        put("task_id", hoPayload?.get("task_id") ?: JsonPrimitive(""))
                        put("trace_id", hoPayload?.get("trace_id") ?: (json["trace_id"] ?: JsonPrimitive("")))
                        put("response_kind", JsonPrimitive("failure"))
                        put("error", JsonPrimitive("wearos_no_execution_runtime"))
                        put("schema_version", JsonPrimitive("1"))
                        put("completion_closure_contract_version", JsonPrimitive("1"))
                    }
                    scope.launch {
                        sendJson(AIPMessage(
                            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
                            payload = failurePayload,
                            deviceId = deviceId,
                            correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                        ))
                    }
                    Log.i(GalaxyWearApplication.TAG, "handoff_envelope_v2 declined honestly (terminal failure sent)")
                }
                // LOW-FIX (Cross-repo): Minimal-compat handling for advanced message types.
                // Wear OS acknowledges receipt of advanced types to maintain protocol
                // compatibility with Android/Gateway without full processing.
                // (takeover_request / handoff_envelope_v2 已上移为诚实拒绝分支。)
                "device_register", "capability_report", "heartbeat",
                "diagnostics_payload", "device_state_snapshot",
                "takeover_response", "operator_action_request",
                "relay", "wake_event", "broadcast", "ack" -> {
                    if (msgType != null && MsgType.ACK_ON_RECEIPT_TYPES.contains(msgType)) {
                        // Send minimal ack for types that require acknowledgement
                        try {
                            val ackPayload = buildJsonObject {
                                put("ack_type", typeStr)
                                put("device", "wear_os")
                            }
                            scope.launch {
                                sendJson(AIPMessage(
                                    type = MsgType.ACK,
                                    payload = ackPayload,
                                    deviceId = deviceId,
                                    correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                                ))
                            }
                        } catch (e: Exception) {
                            Log.w(GalaxyWearApplication.TAG, "Failed to send ack for $typeStr: ${e.message}")
                        }
                    }
                    // Emit the message for upstream observers (e.g., logging)
                    if (msgType != null) {
                        emitMessage(AIPMessage(
                            type = msgType,
                            payload = json["payload"] ?: JsonObject(json.toMap()),
                            deviceId = deviceId,
                            correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                        ))
                    }
                }
                "liquid_event" -> {
                    // LIQUID-ISLAND: 灵动岛式消息
                    // X-DATA-CR1: Emit unified AIPMessage data class with LIQUID_EVENT type
                    val liquid = json["liquid"]?.jsonObject
                    if (liquid != null) {
                        val liquidPayload = buildJsonObject {
                            put("msg_type", liquid["msg_type"]?.jsonPrimitive?.content ?: "")
                            put("content", liquid)
                        }
                        emitMessage(AIPMessage(
                            type = MsgType.LIQUID_EVENT,
                            payload = liquidPayload,
                            deviceId = deviceId
                        ))
                    }
                }
                "decision_request" -> {
                    // HITL: V2 asks the human to choose. Emit the payload so the
                    // app observer can raise a decision notification; the reply
                    // returns via human_input (ReplyReceiver → sendCommand).
                    emitMessage(AIPMessage(
                        type = MsgType.DECISION_REQUEST,
                        payload = json["payload"]?.jsonObject ?: JsonObject(emptyMap()),
                        deviceId = deviceId,
                        correlationId = json["correlation_id"]?.jsonPrimitive?.content ?: ""
                    ))
                }
                else -> {
                    // X-API-CR1: Handle any other known MsgType enum values
                    if (msgType != null) {
                        emitMessage(AIPMessage(
                            type = msgType,
                            payload = json["payload"] ?: JsonObject(json.toMap()),
                            deviceId = deviceId,
                            correlationId = json["correlation_id"]?.jsonPrimitive?.content
                                ?: json["correlationId"]?.jsonPrimitive?.content ?: ""
                        ))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(GalaxyWearApplication.TAG, "Failed to parse message: ${e.message}")
        }
    }

    /**
     * P2-FIX: Dynamic heartbeat interval based on device power state.
     * PR-RECONNECT-UNIFIED: Normal interval aligned with Android via ReconnectionConfig.
     * Extends interval in Doze mode (60s) and Power Save mode (40s) to reduce battery drain.
     */
    private fun getHeartbeatIntervalMs(): Long {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return when {
            powerManager?.isDeviceIdleMode == true -> 60000L // Doze: 60s
            powerManager?.isPowerSaveMode == true -> 40000L  // Power Save: 40s
            else -> ReconnectionConfig.HEARTBEAT_INTERVAL_MS // Normal: 20s (unified)
        }
    }

    private fun startHeartbeat() {
        // Cancel existing first
        heartbeatJob?.cancel()
        lastPongTimeMs.set(System.currentTimeMillis())
        heartbeatJob = scope.launch {
            while (isActive && !isDisposed) {
                val heartbeatIntervalMs = getHeartbeatIntervalMs() // P2-FIX: dynamic interval
                delay(heartbeatIntervalMs)
                if (!isActive || isDisposed) break
                try {
                    val session = _session
                    if (session == null || session.outgoing.isClosedForSend) {
                        Log.w(GalaxyWearApplication.TAG, "Heartbeat: session closed, stopping")
                        break
                    }

                    // Check pong timeout before sending next ping
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTimeMs.get()
                    if (timeSinceLastPong > heartbeatIntervalMs + pongTimeoutMs) { // interval + timeout grace
                        Log.w(GalaxyWearApplication.TAG, "Heartbeat: pong timeout (${timeSinceLastPong}ms), reconnecting")
                        _connectionState.value = AIPConnectionState.ERROR
                        // ROUND-2-FIX: close the stale session BEFORE reconnecting.
                        // Previously the dead session's receive loop kept running while
                        // the reconnect opened a second WebSocket; when the old session
                        // finally ended, its finally-block nulled the NEW session's
                        // _session reference and clobbered its state to DISCONNECTED.
                        runCatching {
                            session.close(CloseReason(CloseReason.Codes.NORMAL, "pong timeout"))
                        }.onFailure { Log.w(GalaxyWearApplication.TAG, "Heartbeat: stale session close failed: ${it.message}") }
                        scheduleReconnect()
                        break
                    }

                    // X-API-CR1: Use data class with MsgType.PING instead of sealed subtype
                    val pingPayload = buildJsonObject {
                        put("id", messageId.incrementAndGet())
                    }
                    val pingMsg = AIPMessage(
                        type = MsgType.PING,
                        payload = pingPayload,
                        deviceId = deviceId,
                        traceId = "ping_${System.currentTimeMillis()}"
                    )
                    val pingJson = jsonFormat.encodeToString(AIPMessage.serializer(), pingMsg)
                    session.outgoing.send(Frame.Text(pingJson))
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(GalaxyWearApplication.TAG, "Heartbeat failed: ${e.message}")
                    // ROUND-2-FIX: close the broken session before reconnecting so
                    // its receive loop exits instead of running alongside the new
                    // connection (same double-session corruption as pong timeout).
                    // (session is scoped to the try-block; re-read _session here.)
                    runCatching {
                        _session?.close(CloseReason(CloseReason.Codes.NORMAL, "heartbeat failure"))
                    }.onFailure { Log.w(GalaxyWearApplication.TAG, "Heartbeat: session close failed: ${it.message}") }
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        // Don't schedule if disposed or already reconnecting
        if (isDisposed) return
        // ROUND-2-FIX: never reconnect with cleared credentials. disconnect()
        // blanks serverUrl/token; if an in-flight connect() fails right after a
        // user disconnect, the old code scheduled a reconnect to an empty URL
        // and looped on it forever.
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.w(GalaxyWearApplication.TAG, "Reconnect skipped — no stored credentials (disconnected)")
            return
        }
        reconnectJob?.cancel()

        // ROUND-2-FIX: true exponential backoff — grow the attempt counter on
        // every consecutive failure right here (the previous growth site in the
        // job's catch was unreachable, pinning retries at a constant delay).
        // computeDelay: 0→5s, 1→10s, 2→20s, ≥3→30s cap (+jitter).
        val attempt = reconnectAttempt
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_BACKOFF_ATTEMPT)
        val actualDelay = ReconnectionConfig.computeDelay(attempt)

        // W14-FIX: Acquire partial wake lock during reconnect to prevent CPU sleep
        val wakeLock = try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Galaxy:Reconnect")
            wl.setReferenceCounted(false)
            // ROUND-2-FIX: timeout must cover the backoff delay itself (was 10s,
            // shorter than capped backoff delays, though Android auto-releases
            // on timeout so this is belt-and-braces).
            wl.acquire(actualDelay + 15 * 1000L)
            wl
        } catch (e: Exception) {
            Log.w(GalaxyWearApplication.TAG, "Failed to acquire wake lock: ${e.message}")
            null
        }

        reconnectJob = scope.launch {
            try {
                Log.i(GalaxyWearApplication.TAG, "Reconnect scheduled in ${actualDelay}ms (attempt ${attempt + 1})")
                delay(actualDelay)
                if (isDisposed) return@launch
                val current = _connectionState.value
                if (current != AIPConnectionState.AUTHENTICATED && current != AIPConnectionState.CONNECTING) {
                    Log.i(GalaxyWearApplication.TAG, "Attempting reconnect...")
                    try {
                        connect(serverUrl, token, deviceId)
                    } catch (e: CancellationException) {
                        // Normal shutdown
                    } catch (e: Exception) {
                        // connect() normally handles its own failures and
                        // reschedules via scheduleReconnect(); reaching here is
                        // unexpected — backoff already advanced above.
                        Log.e(GalaxyWearApplication.TAG, "Reconnect failed: ${e.message}")
                    }
                }
            } finally {
                // W14-FIX: Always release wake lock
                wakeLock?.let {
                    try { it.release() } catch (e: Exception) { Log.w(GalaxyWearApplication.TAG, "WakeLock release failed: ${e.message}") }
                }
            }
        }
    }

    private suspend fun sendJson(msg: AIPMessage) {
        // CRITICAL-FIX: Send directly via WebSocket FIRST to break the infinite loop:
        // sendJson(msg) → transportManager.sendJson() → callback sendJson(json:String) →
        // runBlocking → sendJson(msg) → ... (was causing stack overflow / deadlock)
        // Only if WebSocket is unavailable do we attempt TransportManager as fallback.

        val session = _session
        if (session != null && !session.outgoing.isClosedForSend) {
            // WebSocket is available — send directly
            // DUAL-FORMAT: send as MessagePack binary if opted in
            if (useBinaryFormat) {
                try {
                    val json = jsonFormat.encodeToString(AIPMessage.serializer(), msg)
                    val packed = packMsgpack(json)
                    if (packed != null) {
                        session.outgoing.send(Frame.Binary(fin = true, data = packed))
                        return
                    }
                } catch (e: Exception) {
                    Log.w(GalaxyWearApplication.TAG, "Msgpack send failed, falling back to JSON: ${e.message}")
                }
            }
            val json = jsonFormat.encodeToString(AIPMessage.serializer(), msg)
            session.outgoing.send(Frame.Text(json))
            return
        }

        // WebSocket not connected — try TransportManager as fallback
        // Guard: if _isRouting is true, TransportManager called us; don't loop back.
        if (!_isRouting.get()) {
            try {
                val transportManager = AipTransportManager.getInstance()
                if (transportManager.isConnected()) {
                    val json = jsonFormat.encodeToString(AIPMessage.serializer(), msg)
                    val sent = transportManager.sendJson(json)
                    if (sent) return // Successfully routed through transportManager
                }
            } catch (e: Exception) {
                Log.d(GalaxyWearApplication.TAG, "TransportManager unavailable: ${e.message}")
            }
        }

        // Nothing worked — report error
        throw IllegalStateException("WebSocket not connected and TransportManager unavailable")
    }

    // -----------------------------------------------------------------
    // DUAL-FORMAT: MessagePack helpers (C4 fix)
    // -----------------------------------------------------------------

    companion object {
        /** P2-FIX: Maximum WebSocket frame size (512KB) to prevent OOM on malicious payloads. */
        const val MAX_MESSAGE_SIZE = 512 * 1024 // 512KB

        /** Max time to wait for auth_ok after the WS session opens before closing it. */
        const val AUTH_TIMEOUT_MS = 10_000L

        /** Cap on the backoff attempt counter; delay is already capped at 30s by attempt 3. */
        private const val MAX_BACKOFF_ATTEMPT = 8

        // C4: Dedicated Json instance for companion object (was referencing instance variable)
        private val companionJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * C4: Unpack a MessagePack byte array into a JSON string.
         * Uses recursive Value-to-JsonElement conversion for reliable JSON output.
         * Returns null if unpacking fails (caller falls back to JSON).
         */
        fun unpackMsgpack(data: ByteArray): String? {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)
            return try {
                val value = unpacker.unpackValue()
                // C4: Convert MessagePack Value to kotlinx JsonElement recursively
                val jsonElement = msgpackValueToJsonElement(value)
                companionJson.encodeToString(JsonElement.serializer(), jsonElement)
            } catch (e: Exception) {
                Log.w("AIPClient", "Msgpack unpack failed: ${e.message}")
                null
            } finally {
                runCatching { unpacker.close() }
            }
        }

        /**
         * C4: Recursively convert MessagePack Value to kotlinx.serialization JsonElement.
         * This ensures proper JSON type mapping (not just toString).
         */
        private fun msgpackValueToJsonElement(
            value: org.msgpack.value.Value
        ): JsonElement {
            return when {
                value.isNilValue -> JsonNull
                value.isBooleanValue -> JsonPrimitive(value.asBooleanValue().boolean)
                value.isIntegerValue -> {
                    val intVal = value.asIntegerValue()
                    when {
                        intVal.isInLongRange -> JsonPrimitive(intVal.toLong())
                        else -> JsonPrimitive(intVal.toBigInteger().toString())
                    }
                }
                value.isFloatValue -> JsonPrimitive(value.asFloatValue().toDouble())
                value.isStringValue -> JsonPrimitive(value.asStringValue().asString())
                value.isBinaryValue -> {
                    // Encode binary as Base64 string
                    val bytes = value.asBinaryValue().asByteArray()
                    JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes))
                }
                value.isArrayValue -> {
                    val array = value.asArrayValue()
                    JsonArray(array.map { msgpackValueToJsonElement(it) })
                }
                value.isMapValue -> {
                    val map = value.asMapValue().map()
                    JsonObject(map.mapKeys {
                        // CRITICAL-FIX: Handle non-string keys gracefully — if key is not
                        // a string (e.g., integer key), fall back to toString() instead
                        // of letting asStringValue() throw MessageTypeCastException.
                        try { it.key.asStringValue().asString() }
                        catch (_: Exception) { it.key.toString() }
                    }.mapValues { msgpackValueToJsonElement(it.value) })
                }
                value.isExtensionValue -> {
                    // Handle extension types as JSON object with type and data
                    val ext = value.asExtensionValue()
                    buildJsonObject {
                        put("ext_type", ext.type)
                        put("data", java.util.Base64.getEncoder().encodeToString(ext.data))
                    }
                }
                else -> JsonPrimitive(value.toString())
            }
        }

        /**
         * C4: Pack a JSON string into a MessagePack byte array.
         * Returns null if packing fails (caller falls back to JSON).
         */
        fun packMsgpack(json: String): ByteArray? {
            return try {
                val parsed = companionJson.parseToJsonElement(json)
                val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
                try {
                    packJsonElement(packer, parsed)
                    packer.toByteArray()
                } finally {
                    runCatching { packer.close() }
                }
            } catch (e: Exception) {
                Log.w("AIPClient", "Msgpack pack failed: ${e.message}")
                null
            }
        }

        private fun packJsonElement(packer: org.msgpack.core.MessagePacker, element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    packer.packMapHeader(element.size)
                    element.forEach { (key, value) ->
                        packer.packString(key)
                        packJsonElement(packer, value)
                    }
                }
                is JsonArray -> {
                    packer.packArrayHeader(element.size)
                    element.forEach { packJsonElement(packer, it) }
                }
                is JsonPrimitive -> {
                    val primitive = element
                    when {
                        primitive.isString -> packer.packString(primitive.content)
                        primitive.content == "true" -> packer.packBoolean(true)
                        primitive.content == "false" -> packer.packBoolean(false)
                        primitive.content == "null" -> packer.packNil()
                        else -> {
                            // Try int first, then float
                            primitive.content.toLongOrNull()?.let { packer.packLong(it) }
                                ?: primitive.content.toDoubleOrNull()?.let { packer.packDouble(it) }
                                ?: packer.packString(primitive.content)
                        }
                    }
                }
                JsonNull -> packer.packNil()
            }
        }
    }

    /**
     * DEVICE: 查询已连接的设备列表。（属于 AIPClient —— 用其 sendCommand/deviceId；
     * 此前被误放进 AIPMessage 数据类,引用 sendCommand 无法解析,是编译阻塞的真凶。）
     */
    suspend fun queryDeviceList(): DeviceListResult {
        return try {
            sendCommand("query_devices", buildJsonObject {
                put("request_id", "dev_${System.currentTimeMillis()}")
            })
            // 等待响应（简化版，实际应通过 SharedFlow 收集）
            DeviceListResult.Loading
        } catch (e: Exception) {
            DeviceListResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * DEVICE: 解析设备列表响应。
     */
    fun parseDeviceList(payload: JsonElement): List<DeviceInfo> {
        return try {
            val array = payload.jsonArray
            array.map { element ->
                val obj = element.jsonObject
                DeviceInfo(
                    deviceId = obj["device_id"]?.jsonPrimitive?.content ?: "unknown",
                    displayName = obj["display_name"]?.jsonPrimitive?.content ?: "Unknown Device",
                    deviceType = obj["device_type"]?.jsonPrimitive?.content ?: "unknown",
                    status = obj["status"]?.jsonPrimitive?.content ?: "unknown",
                    capabilities = obj["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 暴露 deviceId 给 DevicesScreen 使用。
     */
    fun getDeviceId(): String = deviceId
}

// -----------------------------------------------------------------
// Data types
// -----------------------------------------------------------------

enum class AIPConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR;

    val isTerminal: Boolean
        get() = this == DISCONNECTED || this == ERROR
}

// X-DATA-CR1 → PR-SHARED-ENVELOPE: 本地信封已删除。canonical 信封是
// shared-protocol 的 com.ufo.galaxy.shared.protocol.AipMessage(Android 与 Wear
// 的单一线格式真相源,payloadObject/token/command 等访问器齐备)。typealias 让
// 既有 13 处构造与 serializer() 调用零改动。
typealias AIPMessage = com.ufo.galaxy.shared.protocol.AipMessage
