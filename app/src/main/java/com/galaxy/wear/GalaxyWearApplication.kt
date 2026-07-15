package com.galaxy.wear

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.galaxy.wear.service.GalaxyWearService
import com.ufo.galaxy.shared.protocol.DeviceIdProvider
import com.galaxy.wear.data.AIPClient
import com.galaxy.wear.data.AIPConnectionState
import com.galaxy.wear.data.AIPMessage
import com.ufo.galaxy.shared.protocol.MsgType
import com.galaxy.wear.domain.DeviceRepository
import com.galaxy.wear.domain.model.Device
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.network.MdnsDiscovery
import com.galaxy.wear.network.TailscaleAdapter
import com.ufo.galaxy.transport.AipTransportManager
import com.galaxy.wear.tile.GalaxyTileService
import com.galaxy.wear.domain.parseDecisionOptions
import com.galaxy.wear.ui.components.IslandItem
import com.galaxy.wear.ui.HapticType
import com.galaxy.wear.ui.triggerHaptic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Galaxy Wear OS Application
 *
 * Singleton entry point. Manages the AIP v3 WebSocket connection
 * and global coroutine scope for the watch.
 *
 * Thread-safety: All mutable state accessed via Main dispatcher.
 */
class GalaxyWearApplication : Application() {

    companion object {
        const val TAG = "GalaxyWear"
        /** SECURITY-FIX: Shared preferences file name for encrypted credential storage. */
        private const val PREFS_FILE = "galaxy_config"
        /** SECURITY-FIX: Key name for auth token in encrypted preferences. */
        private const val KEY_AUTH_TOKEN = "auth_token"
        /** SECURITY-FIX: Key name for server URL in encrypted preferences. */
        private const val KEY_SERVER_URL = "server_url"
        /** P2-FIX: Maximum auto-reconnect attempts to prevent infinite reconnect loops. */
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    private val _phase = MutableStateFlow(Phase.SILENT)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _connectionState = MutableStateFlow(AIPConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AIPConnectionState> = _connectionState.asStateFlow()

    // LOW-FIX: DeviceRepository replaces hard-coded DevicesScreen items.
    // Provides clean architecture separation between data and presentation.
    val deviceRepository = DeviceRepository()
    /** Observable device list for UI — backed by DeviceRepository */
    val devices: StateFlow<List<Device>>
        get() = deviceRepository.devices as kotlinx.coroutines.flow.StateFlow<List<Device>>

    // LIQUID-ISLAND: real backing state for the DynamicIsland composable —
    // previously HomeScreen's `islandItems` parameter had no caller-supplied
    // value anywhere, so the fully-built Island/DecisionScreen UI was
    // permanently empty. Populated for real from state_event/decision_request
    // messages in handleStateEvent()/handleDecisionRequest() below.
    private val _islandItems = MutableStateFlow<List<IslandItem>>(emptyList())
    val islandItems: StateFlow<List<IslandItem>> = _islandItems.asStateFlow()

    /** Upsert by id (replaces an existing item with the same id) and cap history length. */
    private fun pushIslandItem(item: IslandItem) {
        _islandItems.value = (listOf(item) + _islandItems.value.filterNot { it.id == item.id }).take(5)
    }

    /** Remove an item, e.g. after its decision has been answered. */
    fun dismissIslandItem(id: String) {
        _islandItems.value = _islandItems.value.filterNot { it.id == id }
    }

    // WARNING-7: aipClient is initialized in onCreate with try-catch protection.
    // If initialization fails, aipClient remains null and isAipClientReady returns false.
    lateinit var aipClient: AIPClient
        private set

    /** Safe check before accessing [aipClient] to avoid UninitializedPropertyAccessException. */
    fun isAipClientReady(): Boolean = ::aipClient.isInitialized

    private val appScope = CoroutineScope(
        // CRITICAL-4: Use Dispatchers.IO for Application-level scope instead of Main.immediate
        // to avoid blocking the main thread with long-running operations.
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exc ->
            Log.e(TAG, "Uncaught coroutine error: ${exc.message}", exc)
        }
    )

    // CRITICAL-1: Hold a reference to the NetworkCallback so it can be unregistered in onTerminate().
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // P2-FIX: Auto-reconnect attempt counter to prevent infinite reconnect loops.
    private var reconnectAttempts = 0

    // WARNING-9: Reusable discovery components to avoid creating new instances on every call.
    private val mdnsDiscovery by lazy { MdnsDiscovery(this) }
    private val tailscaleAdapter by lazy { TailscaleAdapter(this) }

    // SECURITY-FIX: EncryptedSharedPreferences for secure credential storage.
    // Replaces plaintext SharedPreferences to protect auth_token from device-local attackers.
    val encryptedPrefs by lazy {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plaintext: ${e.message}")
            // Fallback to plaintext only on devices that don't support encrypted prefs (very rare)
            getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Galaxy Wear OS starting...")

        // WARNING-7: Wrap AIPClient initialization to prevent UninitializedPropertyAccessException
        // on subsequent accesses if the constructor throws.
        try {
            aipClient = AIPClient(
                context = this,
                scope = appScope
            )
            // PR-AIP-UNIFIED-WEAR: Register WebSocket adapter to unified transport manager.
            // AIPClient 实现的是本仓 com.galaxy.wear.network.GatewayClient,而
            // AipTransportManager.registerAdapter 需要共享模块的
            // com.ufo.galaxy.network.GatewayClient —— 两者方法签名一致但属不同类型,
            // 故用一个轻量适配器桥接,委托到 aipClient 的同名 isConnected()/sendJson()。
            val wsAdapter = object : com.ufo.galaxy.network.GatewayClient {
                override fun isConnected(): Boolean = aipClient.isConnected()
                override fun sendJson(json: String): Boolean = aipClient.sendJson(json)
            }
            AipTransportManager.getInstance().registerAdapter("websocket", wsAdapter)
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: AIPClient initialization failed: ${e.message}", e)
            // aipClient remains uninitialized; isAipClientReady() will return false.
        }

        // WARNING-7: Guard observation with isAipClientReady() to avoid crash if init failed.
        if (!isAipClientReady()) {
            Log.e(TAG, "AIPClient not initialized — skipping connection state observation")
            return
        }

        // Observe connection state → phase mapping
        appScope.launch {
            try {
                aipClient.connectionState.collect { state ->
                    _connectionState.value = state
                    // P2-FIX: Reset reconnect counter on successful connection
                    if (state == AIPConnectionState.CONNECTED || state == AIPConnectionState.AUTHENTICATED) {
                        if (reconnectAttempts > 0) {
                            Log.i(TAG, "Connection established — resetting reconnect attempts (was $reconnectAttempts)")
                            reconnectAttempts = 0
                        }
                    }
                    val newPhase = when (state) {
                        AIPConnectionState.CONNECTED -> Phase.LIMINAL
                        AIPConnectionState.AUTHENTICATED -> Phase.MANIFEST
                        else -> {
                            // Only fall back to SILENT if we were previously active
                            if (_phase.value == Phase.MANIFEST || _phase.value == Phase.LIMINAL) {
                                Log.i(TAG, "AIP disconnected — falling back to SILENT")
                                Phase.SILENT
                            } else {
                                _phase.value // Keep current
                            }
                        }
                    }
                    if (newPhase != _phase.value) {
                        _phase.value = newPhase
                        // CRITICAL-3: Redacted logging — avoid printing raw state objects that may
                        // contain sensitive connection details. Log phase name only.
                        Log.i(TAG, "Phase transition: $newPhase")
                        // W16-FIX: Refresh tile widget on phase change for up-to-date display
                        GalaxyTileService.requestRefresh(this@GalaxyWearApplication)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Phase observer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Phase observer crashed: ${e.message}")
            }
        }

        // W17-FIX: Register network callback for auto-reconnect on network availability
        // CRITICAL-1: Save callback reference so it can be unregistered in onTerminate().
        // CRITICAL-FIX: Prevent duplicate registration leaks.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { oldCb ->
                try { cm.unregisterNetworkCallback(oldCb) } catch (e: Exception) { Log.d(TAG, "unregisterNetworkCallback failed: ${e.message}") }
                networkCallback = null
            }
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    if (hasInternet && _connectionState.value == AIPConnectionState.DISCONNECTED) {
                        // P2-FIX: Enforce maximum reconnect attempts to prevent infinite loops
                        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up auto-reconnect")
                            return
                        }
                        reconnectAttempts++
                        Log.i(TAG, "Network available — triggering auto-reconnect (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
                        appScope.launch {
                            try {
                                // Only reconnect if we have stored credentials
                                // SECURITY-FIX: auth_token now stored in EncryptedSharedPreferences.
                                // Protected with AES256-GCM encryption via AndroidX Security library.
                                val savedUrl = encryptedPrefs.getString(KEY_SERVER_URL, "") ?: ""
                                val savedToken = encryptedPrefs.getString(KEY_AUTH_TOKEN, "") ?: ""
                                if (savedUrl.isNotEmpty() && savedToken.isNotEmpty() && isAipClientReady()) {
                                    // 此处位于 appScope.launch 内,裸 this 指向 CoroutineScope;
                                    // getOrCreateDeviceId 需要 Context,故显式限定为 Application。
                                    aipClient.connect(savedUrl, savedToken, DeviceIdProvider.getOrCreateDeviceId(this@GalaxyWearApplication))
                                }
                            } catch (e: CancellationException) {
                                Log.d(TAG, "Auto-reconnect cancelled")
                            } catch (e: Exception) {
                                Log.w(TAG, "Auto-reconnect failed: ${e.message}")
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                }
            }
            networkCallback?.let { cm.registerDefaultNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }

        // LIQUID-ISLAND: 收集灵动岛消息
        // CRITICAL-FIX: Wrap in retry loop with exponential backoff. If aipClient
        // gets disposed and restarted, the observer crashes and would never resume
        // without this outer retry loop.
        appScope.launch {
            var retryDelayMs = 1000L
            val maxRetryDelayMs = 30000L
            while (isActive) {
                try {
                    // PR-STATE-SYNC: Map liquid_event to STATE_EVENT for unified handling.
                    // Both LIQUID_EVENT and STATE_EVENT carry the same semantic payload
                    // (cross-device state synchronization); we normalize to the common handler.
                    aipClient.messages.collect { msg ->
                        when (msg.type) {
                            MsgType.LIQUID_EVENT,
                            MsgType.STATE_EVENT -> handleStateEvent(msg)
                            MsgType.DECISION_REQUEST -> handleDecisionRequest(msg)
                            else -> {} // Ignore other types
                        }
                    }
                    // Normal completion ( Flow completed ) — exit loop
                    break
                } catch (e: CancellationException) {
                    Log.d(TAG, "Liquid event observer cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Liquid event observer crashed: ${e.message}, retrying in ${retryDelayMs}ms")
                    kotlinx.coroutines.delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
                }
            }
        }
    }

    // LIQUID-ISLAND: pulse trigger counter (incremented to trigger halo pulse)
    private val _pulseTrigger = MutableStateFlow(0)
    val pulseTrigger = _pulseTrigger.asStateFlow()

    // PR-STATE-SYNC: Unified handler for cross-device state synchronization events.
    // Handles both LIQUID_EVENT (legacy) and STATE_EVENT (normalized) message types.
    // Event payload is extracted as JsonObject per AIP v3 spec.
    private fun handleStateEvent(event: AIPMessage) {
        // FIX: Wrap entire handler in try-catch to prevent Flow collection from terminating
        try {
            // X-DATA-CR1: Extract liquid event fields from payload JsonObject
            val payload = event.payload as? kotlinx.serialization.json.JsonObject
                ?: return
            val content = payload["content"]?.jsonObject
            if (content == null) {
                // PR-WEAR-PHASE-SYNC: V2 跨设备 state_event 格式 —— payload 直接带
                // to_phase（{from_phase,to_phase,source,sync_type}），无嵌套 content。
                // 映射为相位变更以驱动手表三态环（与桌面 silent/liminal/manifest 同步）。
                val toPhase = payload["to_phase"]?.jsonPrimitive?.content
                if (toPhase != null) applyPhaseChange(toPhase)
                return
            }
            val msgType = event.msgType

            // CRITICAL-3: Log only the message type, never the raw content which may contain
            // user-sensitive data (text results, device names, etc.).
            Log.i(TAG, "LiquidIsland: msg=$msgType received")
            when (msgType) {
                "phase_change" -> {
                    val toPhase = content["to_phase"]?.jsonPrimitive?.content
                    if (toPhase != null) applyPhaseChange(toPhase)
                }
                "task_done" -> {
                    // LIQUID-ISLAND: halo pulse + double-tap haptic
                    val deviceName = content["device_name"]?.jsonPrimitive?.content ?: "设备"
                    Log.i(TAG, "LiquidIsland: task done on $deviceName")
                    triggerHaptic(this, HapticType.TASK_DONE)
                    _pulseTrigger.value += 1
                    pushIslandItem(
                        IslandItem(
                            id = "task_done_${System.currentTimeMillis()}",
                            title = "任务完成",
                            summary = "设备 $deviceName 已完成任务",
                            source = "系统",
                            priority = "normal",
                        )
                    )
                }
                "task_progress" -> {
                    // LIQUID-ISLAND: progress update (no haptic, just visual)
                    val completed = content["completed"]?.jsonPrimitive?.int ?: 0
                    val total = content["total"]?.jsonPrimitive?.int ?: 0
                    Log.i(TAG, "LiquidIsland: progress $completed/$total")
                    // Fixed id so repeated progress ticks update the same island
                    // item in place instead of piling up a new one per tick.
                    if (total > 0) {
                        pushIslandItem(
                            IslandItem(
                                id = "task_progress",
                                title = "任务进行中",
                                summary = "$completed / $total",
                                source = "系统",
                                priority = "normal",
                            )
                        )
                    }
                    if (completed >= total && total > 0) {
                        dismissIslandItem("task_progress")
                    }
                }
                "text_result" -> {
                    // LIQUID-ISLAND: short haptic tap + halo pulse
                    val text = content["text"]?.jsonPrimitive?.content ?: ""
                    Log.i(TAG, "LiquidIsland: text result received")
                    triggerHaptic(this, HapticType.MESSAGE_ARRIVAL)
                    if (text.isNotBlank()) {
                        pushIslandItem(
                            IslandItem(
                                id = "text_result_${System.currentTimeMillis()}",
                                title = "OpenClawd",
                                summary = text,
                                source = "OpenClawd",
                                priority = "normal",
                            )
                        )
                    }
                    _pulseTrigger.value += 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiquidIsland: handleLiquidEvent error: ${e.message}")
        }
    }

    // PR-WEAR-PHASE-SYNC: apply a tri-state phase transition to the watch UI.
    // Shared by the liquid_event "phase_change" branch and the V2 cross-device
    // state_event fallback, so both wire formats drive the same SILENT/LIMINAL/
    // MANIFEST ring + haptic + halo pulse.
    private fun applyPhaseChange(toPhase: String) {
        val oldPhase = _phase.value
        _phase.value = when (toPhase.lowercase()) {
            "manifest" -> Phase.MANIFEST
            "liminal" -> Phase.LIMINAL
            else -> Phase.SILENT
        }
        if (oldPhase != _phase.value) {
            triggerHaptic(this, HapticType.PHASE_CHANGE)
            _pulseTrigger.value += 1 // trigger halo pulse
        }
    }

    // HITL: V2 sent a decision_request. Raise a decision notification via the
    // foreground service (it owns the notification channel); option/voice reply
    // returns through ReplyReceiver → sendCommand("human_input") → V2 registry.
    //
    // LIQUID-ISLAND: also surface the same decision in-app via the Dynamic
    // Island (islandItems), so a user who has the watch face open can answer
    // directly without first dismissing to the notification shade. Both paths
    // reply through the same real human_input command — answering in one place
    // resolves the other (dismissIslandItem removes the in-app copy once sent).
    private fun handleDecisionRequest(event: AIPMessage) {
        try {
            val payload = event.payload as? JsonObject ?: return
            val decisionId = payload["decision_id"]?.jsonPrimitive?.content ?: return
            val title = payload["title"]?.jsonPrimitive?.content ?: "需要你的决定"
            val summary = payload["summary"]?.jsonPrimitive?.content ?: ""
            // options: [{"id":..,"label":..}] — pass ids back so V2 can match the
            // registered option ids; show the label when present, else the id.
            val optionsArr = payload["options"] as? JsonArray
            val (optionIds, decisionOptions) = parseDecisionOptions(optionsArr)
            Log.i(TAG, "DecisionRequest: id=$decisionId options=${optionIds.size}")
            val intent = android.content.Intent(this, GalaxyWearService::class.java).apply {
                action = GalaxyWearService.ACTION_SHOW_DECISION
                putExtra(GalaxyWearService.EXTRA_DECISION_ID, decisionId)
                putExtra(GalaxyWearService.EXTRA_DECISION_TITLE, title)
                putExtra(GalaxyWearService.EXTRA_DECISION_SUMMARY, summary)
                putStringArrayListExtra(GalaxyWearService.EXTRA_DECISION_OPTIONS, ArrayList(optionIds))
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)

            pushIslandItem(
                IslandItem(
                    id = "decision_$decisionId",
                    title = title,
                    summary = summary,
                    source = "OpenClawd",
                    priority = "high",
                    options = decisionOptions,
                    onOptionSelected = { optionId -> replyToDecision(decisionId, selectedOption = optionId) },
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleDecisionRequest error: ${e.message}")
        }
    }

    /**
     * Send a human_input reply for an in-app (DynamicIsland) decision response.
     * Mirrors ReplyReceiver's notification-action reply path exactly, so both
     * entry points produce the same wire message.
     */
    private fun replyToDecision(decisionId: String, selectedOption: String? = null, voiceInput: String? = null) {
        appScope.launch {
            try {
                val payload = buildJsonObject {
                    put("decision_id", decisionId)
                    selectedOption?.let { put("selected_option", it) }
                    voiceInput?.let { put("voice_input", it) }
                    put("device", "wear_os")
                    put("timestamp", System.currentTimeMillis())
                }
                aipClient.sendCommand("human_input", payload)
                Log.i(TAG, "Human input sent to mesh (in-app): decision=$decisionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send human input (in-app): ${e.message}")
            } finally {
                dismissIslandItem("decision_$decisionId")
            }
        }
    }

    // WARNING-8: connect() now enforces a 15-second timeout to prevent indefinite hangs.
    fun connect(serverUrl: String, token: String, deviceId: String? = null) {
        // Prevent concurrent connect calls
        if (_connectionState.value == AIPConnectionState.CONNECTING) {
            Log.w(TAG, "Connection already in progress")
            return
        }
        // Guard against uninitialized aipClient (WARNING-7)
        if (!isAipClientReady()) {
            Log.e(TAG, "Cannot connect — AIPClient not initialized")
            return
        }
        // PR-DEVICE-ID-UNIFIED: Use DeviceIdProvider instead of ANDROID_ID.
        // Generates a stable UUID v4 on first access, persisted securely.
        val devId = deviceId ?: DeviceIdProvider.getOrCreateDeviceId(this)
        appScope.launch {
            try {
                withTimeout(15_000) {
                    aipClient.connect(serverUrl, token, devId)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection timed out after 15s")
                _connectionState.value = AIPConnectionState.DISCONNECTED
            } catch (e: CancellationException) {
                Log.d(TAG, "Connect cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
            }
        }
    }

    /**
     * STAGE-2b: 设备流(RFC 8628)登录成功后的统一桥接入口。
     *
     * 此前断裂:DeviceFlowManager 把访问令牌写进 galaxy_auth/access_token,而 connect()
     * 走的是 galaxy_config/auth_token —— 两套存储不同文件、不同键,从不相交,于是"登录成功"
     * 也永远连不上。这里作为唯一桥接点,把令牌与服务器地址落到 connect 路径真正读取的
     * galaxy_config,再立即连接。自动重连回调(onAvailable)之后也能凭这份凭据复连。
     */
    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "loginWithToken skipped — blank serverUrl or token")
            return
        }
        try {
            encryptedPrefs.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_AUTH_TOKEN, token)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Persisting login credentials failed: ${e.message}")
        }
        connect(serverUrl, token)
    }

    fun disconnect() {
        // LOW-FIX: Use isTerminal to avoid redundant disconnect calls
        if (_connectionState.value.isTerminal) {
            Log.d(TAG, "Already disconnected — skipping redundant disconnect()")
            return
        }
        if (!isAipClientReady()) {
            Log.w(TAG, "Cannot disconnect — AIPClient not initialized")
            return
        }
        appScope.launch {
            try {
                aipClient.disconnect()
                markDevicesOffline()
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect error: ${e.message}")
            }
        }
    }

    // -----------------------------------------------------------------
    // NETWORK DISCOVERY: Tailscale + mDNS LAN auto-discovery
    // -----------------------------------------------------------------

    /**
     * Auto-discover gateway using mDNS (same Wi-Fi) or Tailscale (VPN).
     * Priority: mDNS LAN → Tailscale scan → null
     */
    suspend fun discoverGateway(): String? {
        // WARNING-9: Reuse cached discovery instances instead of creating new ones each call.
        // 1. Try mDNS LAN discovery first (zero-config, fastest)
        // CRITICAL-6: isWifiAvailable() relies on ConnectivityManager APIs that may behave
        // differently on Android 10+; ensure proper permissions (ACCESS_NETWORK_STATE).
        if (mdnsDiscovery.isWifiAvailable()) {
            Log.i(TAG, "Discovery: trying mDNS LAN...")
            val lanUrl = mdnsDiscovery.discoverGateway()
            if (lanUrl != null) {
                Log.i(TAG, "Discovery: found via mDNS: $lanUrl")
                return lanUrl
            }
        }

        // 2. Try Tailscale network discovery
        if (tailscaleAdapter.isInTailscaleNetwork()) {
            Log.i(TAG, "Discovery: trying Tailscale...")
            val tsIp = tailscaleAdapter.autoDiscoverGateway()
            if (tsIp != null) {
                val url = tailscaleAdapter.buildWsUrl(tsIp)
                Log.i(TAG, "Discovery: found via Tailscale: $url")
                return url
            }
        }

        Log.w(TAG, "Discovery: no gateway found (mDNS and Tailscale both failed)")
        return null
    }

    /**
     * Quick check: what network types are available?
     */
    fun getNetworkStatus(): String {
        // WARNING-9: Reuse cached discovery instances.
        val wifi = mdnsDiscovery.isWifiAvailable()
        val ts = tailscaleAdapter.isInTailscaleNetwork()
        val tsIp = tailscaleAdapter.getLocalTailscaleIp()
        return when {
            wifi && ts -> "Wi-Fi + Tailscale ($tsIp)"
            wifi -> "Wi-Fi"
            ts -> "Tailscale ($tsIp)"
            else -> "No network"
        }
    }

    // CRITICAL-FIX: onTerminate() is NEVER called on real Android devices — it only
    // runs in emulator. We MUST also unregister in onTrimMemory() to prevent the
    // NetworkCallback from leaking for the entire app lifecycle.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            networkCallback?.let { cb ->
                try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(cb)
                    Log.d(TAG, "NetworkCallback unregistered in onTrimMemory")
                } catch (e: Exception) {
                    Log.d(TAG, "NetworkCallback unregister in onTrimMemory failed: ${e.message}")
                }
            }
            networkCallback = null
        }
    }

    override fun onTerminate() {
        // CRITICAL-1: Unregister the NetworkCallback to prevent system-level leak.
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
                Log.d(TAG, "NetworkCallback unregistered")
            } catch (e: Exception) {
                Log.d(TAG, "NetworkCallback unregister in onTerminate failed: ${e.message}")
            }
        }
        networkCallback = null
        // LOW-FIX: Guard disconnect with isTerminal check to avoid redundant disconnects
        if (!_connectionState.value.isTerminal) {
            disconnect()
        }
        appScope.cancel()
        super.onTerminate()
    }

    // W3-FIX: Always disconnect on low memory regardless of phase
    // to prevent Activity leaks and resource exhaustion.
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory — system under memory pressure")
        // CRITICAL-5: Guard with isActive to avoid silent failure when scope is cancelled.
        if (!appScope.isActive) {
            Log.w(TAG, "appScope is cancelled — skipping low-memory disconnect")
            return
        }
        // Also guard aipClient access to prevent UninitializedPropertyAccessException.
        if (!isAipClientReady()) {
            Log.w(TAG, "AIPClient not initialized — skipping low-memory disconnect")
            return
        }
        // Force disconnect to free resources and prevent leaks
        appScope.launch {
            try {
                aipClient.disconnect()
                Log.i(TAG, "Auto-disconnected on low memory")
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect on low memory failed: ${e.message}")
            }
        }
    }

    /** Update mesh device list from gateway state-sync payload */
    fun updateDeviceList(newDevices: List<Device>) {
        deviceRepository.updateDeviceList(newDevices)
    }

    /** Mark all remote devices as offline (called on disconnect) */
    fun markDevicesOffline() {
        deviceRepository.markAllOffline()
    }
}