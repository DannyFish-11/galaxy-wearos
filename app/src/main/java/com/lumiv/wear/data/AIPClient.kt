package com.lumiv.wear.data

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.lumiv.wear.BuildConfig
import com.lumiv.wear.LumivWearApplication
import com.ufo.lumiv.shared.protocol.ReconnectionConfig
import com.ufo.lumiv.shared.protocol.AuthMessage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
) : com.lumiv.wear.network.GatewayClient {
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
            Log.w(LumivWearApplication.TAG, "SSL pinning disabled: no valid pins configured")
            null
        } else {
            // Round-4 HIGH: pin production domain, not the placeholder
            val pinDomain = "lumiv.ufo.ai"
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

    // Reconnect: exponential backoff state (unified with Android)
    private var reconnectDelayMs = ReconnectionConfig.INITIAL_DELAY_MS // start at 5s
    private val maxReconnectDelayMs = ReconnectionConfig.MAX_DELAY_MS // cap at 30s
    private val reconnectJitterMs = ReconnectionConfig.JITTER_MS // +/- 1s jitter (unified)

    // PR-CR1: AtomicBoolean (not ThreadLocal) for coroutine-safe routing loop detection.
    // ThreadLocal is unreliable in coroutines ( Dispatchers.IO thread migration ).
    private val _isRouting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(AIPConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AIPConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<AIPMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<AIPMessage> = _messages.asSharedFlow()

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
                Log.w(LumivWearApplication.TAG, "AIPClient already disposed")
                return
            }

            // Guard against concurrent connect
            if (_connectionState.value == AIPConnectionState.CONNECTED ||
                _connectionState.value == AIPConnectionState.AUTHENTICATED ||
                _connectionState.value == AIPConnectionState.CONNECTING
            ) {
                Log.d(LumivWearApplication.TAG, "Connection already in progress or established")
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

                Log.i(LumivWearApplication.TAG, "WebSocket connected: $wsUrl")

                // Reset reconnect delay on successful connection (unified initial delay)
                reconnectDelayMs = ReconnectionConfig.INITIAL_DELAY_MS

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
                    Log.e(LumivWearApplication.TAG, "Auth send failed: ${e.message}")
                    return@webSocket
                }

                // Listen for messages
                try {
                    for (frame in incoming) {
                        if (isDisposed) break
                        when (frame) {
                            is Frame.Text -> {
                                // P2-FIX: Enforce maximum message size for text frames too
                                val textBytes = frame.data.size
                                if (textBytes > MAX_MESSAGE_SIZE) {
                                    Log.w(LumivWearApplication.TAG, "Text message too large: $textBytes bytes, max=$MAX_MESSAGE_SIZE")
                                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Message exceeds maximum size"))
                                    break
                                }
                                handleMessage(frame.readText())
                            }
                            is Frame.Binary -> {
                                // P2-FIX: Enforce maximum message size to prevent OOM on malicious payloads
                                if (frame.data.size > MAX_MESSAGE_SIZE) {
                                    Log.w(LumivWearApplication.TAG, "Message too large: ${frame.data.size} bytes, max=$MAX_MESSAGE_SIZE")
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
                                        val rawText = frame.readText()
                                        handleMessage(rawText)
                                    } catch (e: Exception) {
                                        Log.w(LumivWearApplication.TAG, "Failed to parse binary frame: ${e.message}")
                                    }
                                }
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()
                                Log.w(LumivWearApplication.TAG, "WebSocket closed: ${reason?.message}")
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
                    Log.d(LumivWearApplication.TAG, "WebSocket receive cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(LumivWearApplication.TAG, "WebSocket receive error: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            Log.d(LumivWearApplication.TAG, "Connection cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(LumivWearApplication.TAG, "WebSocket error: ${e.message}")
            val current = _connectionState.value
            if (current != AIPConnectionState.DISCONNECTED) {
                _connectionState.value = AIPConnectionState.ERROR
            }
            scheduleReconnect()
        } finally {
            heartbeatJob?.cancel()
            connectMutex.withLock {
                _session = null
                val current = _connectionState.value
                if (current == AIPConnectionState.CONNECTED ||
                    current == AIPConnectionState.AUTHENTICATED ||
                    current == AIPConnectionState.CONNECTING
                ) {
                    _connectionState.value = AIPConnectionState.DISCONNECTED
                }
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
                            Log.w(LumivWearApplication.TAG, "Direct WS send failed in routing break: ${ex.message}")
                        }
                }
                true
            } catch (e: Exception) {
                Log.w(LumivWearApplication.TAG, "Routing-break send failed: ${e.message}")
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
                        Log.w(LumivWearApplication.TAG, "GatewayClient.sendJson send failed: ${ex.message}")
                    }
                }
                true
            } finally {
                _isRouting.set(false)
            }
        } catch (e: CancellationException) {
            Log.w(LumivWearApplication.TAG, "GatewayClient.sendJson cancelled: scope inactive")
            false
        } catch (e: Exception) {
            Log.w(LumivWearApplication.TAG, "GatewayClient.sendJson failed: ${e.message}")
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
                _session?.close()
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
     * HUMAN-INPUT: 发送人类决策回复到 Galaxy Mesh 网络。
     *
     * 当用户在决策通知上选择选项、语音输入或在 DecisionScreen 中操作时，
     * 调用此方法将人类输入发送到后端。
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
                    reconnectDelayMs = 5000L
                    startHeartbeat()
                }
                "auth_failed" -> {
                    Log.e(LumivWearApplication.TAG, "Authentication failed")
                    _connectionState.value = AIPConnectionState.ERROR
                }
                "auth_invalid" -> {
                    Log.e(LumivWearApplication.TAG, "Auth token invalid")
                    _connectionState.value = AIPConnectionState.ERROR
                }
                "command_result" -> {
                    // X-DATA-CR1: Emit unified AIPMessage data class with COMMAND_RESULT type
                    val resultPayload = buildJsonObject {
                        put("id", json["id"] ?: JsonPrimitive(0))
                        put("success", json["success"] ?: JsonPrimitive(false))
                        put("data", json["data"] ?: JsonNull)
                    }
                    _messages.tryEmit(AIPMessage(
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
                    _messages.tryEmit(AIPMessage(
                        type = MsgType.EVENT,
                        payload = eventPayload,
                        deviceId = deviceId
                    ))
                }
                "pong" -> {
                    // Heartbeat: record pong receipt time for timeout detection
                    lastPongTimeMs.set(System.currentTimeMillis())
                }
                // LOW-FIX (Cross-repo): Minimal-compat handling for advanced message types.
                // Wear OS acknowledges receipt of advanced types to maintain protocol
                // compatibility with Android/Gateway without full processing.
                "device_register", "capability_report", "heartbeat",
                "diagnostics_payload", "device_state_snapshot",
                "takeover_request", "takeover_response",
                "handoff_envelope_v2", "operator_action_request",
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
                            Log.w(LumivWearApplication.TAG, "Failed to send ack for $typeStr: ${e.message}")
                        }
                    }
                    // Emit the message for upstream observers (e.g., logging)
                    if (msgType != null) {
                        _messages.tryEmit(AIPMessage(
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
                        _messages.tryEmit(AIPMessage(
                            type = MsgType.LIQUID_EVENT,
                            payload = liquidPayload,
                            deviceId = deviceId
                        ))
                    }
                }
                else -> {
                    // X-API-CR1: Handle any other known MsgType enum values
                    if (msgType != null) {
                        _messages.tryEmit(AIPMessage(
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
            Log.w(LumivWearApplication.TAG, "Failed to parse message: ${e.message}")
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
                        Log.w(LumivWearApplication.TAG, "Heartbeat: session closed, stopping")
                        break
                    }

                    // Check pong timeout before sending next ping
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTimeMs.get()
                    if (timeSinceLastPong > heartbeatIntervalMs + pongTimeoutMs) { // interval + timeout grace
                        Log.w(LumivWearApplication.TAG, "Heartbeat: pong timeout (${timeSinceLastPong}ms), reconnecting")
                        _connectionState.value = AIPConnectionState.ERROR
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
                    Log.w(LumivWearApplication.TAG, "Heartbeat failed: ${e.message}")
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        // Don't schedule if disposed or already reconnecting
        if (isDisposed) return
        reconnectJob?.cancel()

        // W14-FIX: Acquire partial wake lock during reconnect to prevent CPU sleep
        val wakeLock = try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumiv:Reconnect")
            wl.setReferenceCounted(false)
            wl.acquire(10 * 1000L) // 10s timeout — enough for reconnect attempt
            wl
        } catch (e: Exception) {
            Log.w(LumivWearApplication.TAG, "Failed to acquire wake lock: ${e.message}")
            null
        }

        reconnectJob = scope.launch {
            try {
                // PR-RECONNECT-UNIFIED: Use shared ReconnectionConfig for exponential backoff.
                val actualDelay = ReconnectionConfig.computeDelay(
                    (reconnectDelayMs / ReconnectionConfig.INITIAL_DELAY_MS).toInt().coerceAtLeast(0)
                )
                Log.i(LumivWearApplication.TAG, "Reconnect scheduled in ${actualDelay}ms")
                delay(actualDelay)
                if (isDisposed) return@launch
                val current = _connectionState.value
                if (current != AIPConnectionState.AUTHENTICATED && current != AIPConnectionState.CONNECTING) {
                    Log.i(LumivWearApplication.TAG, "Attempting reconnect...")
                    try {
                        connect(serverUrl, token, deviceId)
                    } catch (e: CancellationException) {
                        // Normal shutdown
                    } catch (e: Exception) {
                        Log.e(LumivWearApplication.TAG, "Reconnect failed: ${e.message}")
                        // Increase backoff for next attempt (unified multiplier)
                        reconnectDelayMs = (reconnectDelayMs * ReconnectionConfig.MULTIPLIER)
                            .toLong().coerceAtMost(ReconnectionConfig.MAX_DELAY_MS)
                    }
                }
            } finally {
                // W14-FIX: Always release wake lock
                wakeLock?.let {
                    try { it.release() } catch (e: Exception) { Log.w(LumivWearApplication.TAG, "WakeLock release failed: ${e.message}") }
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
                    Log.w(LumivWearApplication.TAG, "Msgpack send failed, falling back to JSON: ${e.message}")
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
                Log.d(LumivWearApplication.TAG, "TransportManager unavailable: ${e.message}")
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

/**
 * X-DATA-CR1: Unified AIP v3 message envelope compatible with Android's AipMessage.
 *
 * Changed from sealed interface (6 subtypes) to data class with MsgType enum
 * to match Android's message format. All metadata fields are now explicit
 * (correlationId, protocol, version, timestamp, deviceId, traceId, sessionId)
 * so that Wear OS messages can be parsed correctly by Android/Gateway.
 *
 * @param type           Message type identifier (MsgType enum).
 * @param payload        Typed payload as JsonElement (default JsonNull).
 * @param correlationId  Request/response correlation identifier.
 * @param protocol       Wire-protocol identifier; always "AIP/1.0" for AIP v3.
 * @param version        Protocol version; always "3.0".
 * @param timestamp      Unix epoch millis auto-set at construction.
 * @param deviceId       Device identifier for origin tracking.
 * @param traceId        End-to-end trace identifier propagated across all hops.
 * @param sessionId      Optional session identifier.
 */
@Serializable
data class AIPMessage(
    val type: MsgType,
    val payload: JsonElement = JsonNull,
    // X-DATA-CR1: Field names use @SerialName with snake_case to match Android's AipMessage wire format
    @SerialName("correlation_id") val correlationId: String = "",
    val protocol: String = "AIP/1.0",
    val version: String = "3.0",
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("trace_id") val traceId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    // X-OBS-CR1: Observability fields required for cross-repo message compatibility
    @SerialName("route_mode") val routeMode: String = "",
    @SerialName("runtime_session_id") val runtimeSessionId: String = "",
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    @SerialName("source_runtime_posture") val sourceRuntimePosture: String = "",
    @SerialName("dispatch_trace_id") val dispatchTraceId: String = "",
    @SerialName("session_correlation_id") val sessionCorrelationId: String = ""
) {
    /**
     * Convenience accessor for payload as JsonObject.
     * Returns empty JsonObject if payload is not a JsonObject.
     */
    val payloadObject: JsonObject
        get() = payload as? JsonObject ?: JsonObject(emptyMap())

    /**
     * X-API-CR1: Backward-compatible payload field accessors for migration
     * from the old sealed interface pattern.
     */
    val token: String
        get() = (payload as? JsonObject)?.get("token")?.jsonPrimitive?.content ?: ""

    val id: Int
        get() = (payload as? JsonObject)?.get("id")?.jsonPrimitive?.int ?: 0

    val command: String
        get() = (payload as? JsonObject)?.get("command")?.jsonPrimitive?.content ?: ""

    val success: Boolean
        get() = (payload as? JsonObject)?.get("success")?.jsonPrimitive?.boolean ?: false

    val event: String
        get() = (payload as? JsonObject)?.get("event")?.jsonPrimitive?.content ?: ""

    val msgType: String
        get() = (payload as? JsonObject)?.get("msg_type")?.jsonPrimitive?.content ?: ""
}
