package com.galaxy.wear

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.galaxy.wear.service.WatchNotifications
import com.ufo.galaxy.shared.protocol.DeviceIdProvider
import com.galaxy.wear.auth.PairClaimClient
import com.galaxy.wear.data.AIPClient
import com.galaxy.wear.data.AIPConnectionState
import com.galaxy.wear.data.AIPMessage
import com.ufo.galaxy.shared.protocol.MsgType
import com.galaxy.wear.domain.DecisionIslandIds
import com.galaxy.wear.domain.DeviceRepository
import com.galaxy.wear.domain.model.Device
import com.galaxy.wear.network.isWifiAvailable
import com.ufo.galaxy.network.GatewayDiscovery
import com.ufo.galaxy.transport.AipTransportManager
import com.galaxy.wear.tile.GalaxyTileService
import com.galaxy.wear.domain.AgentCommandExecutor
import com.galaxy.wear.domain.AgentCommandParser
import com.galaxy.wear.domain.WatchEffects
import com.galaxy.wear.domain.parseDecisionOptions
import com.galaxy.wear.ui.components.IslandItem
import com.galaxy.wear.ui.HapticType
import com.galaxy.wear.ui.playHaptic
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
        /**
         * 手表侧的 mDNS 发现窗口。
         *
         * 原先是 `MdnsDiscovery.DISCOVER_TIMEOUT_MS`，随那个类一起收敛到共享的
         * `GatewayDiscovery` 之后，取值留在调用方 —— 因为它是**本端的取舍**而不是
         * 发现协议的属性：手机默认 2.5 秒（首屏用户已经在等，多半秒换成功率划算），
         * 手表上多等半秒更肉眼可见，所以维持原来的 2 秒。
         */
        private const val MDNS_TIMEOUT_MS = 2000L
        /** SECURITY-FIX: Shared preferences file name for encrypted credential storage. */
        private const val PREFS_FILE = "galaxy_config"
        /** SECURITY-FIX: Key name for auth token in encrypted preferences. */
        private const val KEY_AUTH_TOKEN = "auth_token"
        /** SECURITY-FIX: Key name for server URL in encrypted preferences. */
        private const val KEY_SERVER_URL = "server_url"
        /** P2-FIX: Maximum auto-reconnect attempts to prevent infinite reconnect loops. */
        private const val MAX_RECONNECT_ATTEMPTS = 20
        /** 到达上限后，网络事件触发的自动重连降为最多这么久一次（而不是永久放弃）。 */
        private const val SLOW_RECONNECT_INTERVAL_MS = 10L * 60 * 1000
    }

    private val _connectionState = MutableStateFlow(AIPConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AIPConnectionState> = _connectionState.asStateFlow()

    // LOW-FIX: DeviceRepository replaces hard-coded DevicesScreen items.
    // Provides clean architecture separation between data and presentation.
    val deviceRepository = DeviceRepository()
    /** Observable device list for UI — backed by DeviceRepository */
    val devices: StateFlow<List<Device>>
        get() = deviceRepository.devices

    // LIQUID-ISLAND: real backing state for the DynamicIsland composable —
    // previously HomeScreen's `islandItems` parameter had no caller-supplied
    // value anywhere, so the fully-built Island/DecisionScreen UI was
    // permanently empty. Populated for real from state_event/decision_request
    // messages in handleLiquidEvent()/handleDecisionRequest() below.
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

    /**
     * 手表上的会话上下文。
     *
     * 公开出来,是因为界面(会话列表)与通知里的快捷回复都要读写它 ——
     * 它和 [aipClient] 一样是这个进程里的单一实例:两份实例会各存各的,
     * 于是通知里回的那句话在会话列表里看不到。
     */
    lateinit var conversationRecorder: com.galaxy.wear.conversation.ConversationRecorder
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
    private var lastNetworkReconnectMs = 0L

    /** 配对客户端 —— 令牌与候选路径都存在它那儿。 */
    private val pairClaimClient by lazy { PairClaimClient(this) }

    /** 下一次重连该用第几条候选。连上之后由 [rememberWorkingPath] 归位。 */
    private var candidateCursor = 0

    /**
     * 最近一次试的那条**候选**的原始地址 —— 连上之后用它反查"这次是哪条路通的"。
     *
     * 注意不是实际拨出去的地址:tailnet 那条实际拨的是本机转发口
     * (ws://127.0.0.1:<端口>/…),拿它去候选表里是查不到的。
     */
    private var lastAttemptedUrl: String? = null

    /**
     * 手表自己进 tailnet 的那个子进程 —— 出门在外、只带手表时**直连**电脑的路。
     * 见 [com.galaxy.wear.network.TailnetDaemon]。
     */
    private val tailnetDaemon by lazy { com.galaxy.wear.network.TailnetDaemon(this, appScope) }

    /** tailnet 转发的当前状态,给设置页看。 */
    val tailnetState get() = tailnetDaemon.state

    /**
     * 配过 tailnet(配对时网关给过 headscale 地址)且候选里有 tailnet 那条,就把转发
     * 进程拉起来;否则停掉。重复调用无害。
     */
    fun ensureTailnet() {
        val join = pairClaimClient.storedTailnetJoin()
        val target = pairClaimClient.storedCandidates()
            .firstNotNullOfOrNull { com.galaxy.wear.network.TailnetProtocol.targetOf(it.url) }
        if (join == null || target == null) {
            tailnetDaemon.stop()
            return
        }
        tailnetDaemon.onJoined = { pairClaimClient.clearTailnetAuthKey() }
        tailnetDaemon.ensureRunning(
            com.galaxy.wear.network.TailnetDaemon.Spec(
                controlUrl = join.controlUrl,
                target = target,
                hostname = com.galaxy.wear.network.TailnetProtocol.hostnameFor(
                    DeviceIdProvider.getOrCreateDeviceId(this)
                ),
                listenPort = pairClaimClient.tailnetListenPort {
                    com.galaxy.wear.network.TailnetProtocol.pickListenPort()
                },
                authKey = join.pendingAuthKey,
            )
        )
    }

    /**
     * 这一轮重连该连哪个地址。
     *
     * 为什么不能只认一个地址
     * ======================
     * 手表在家、在公司、带流量出门，能连通的是**不同**的那一条。只认存下来的
     * 那一个等于换个网就连不上，而表上只显示"连不上"——没有任何线索说该换哪条路，
     * 用户能做的只有反复重启。
     *
     * 顺序由 [com.ufo.galaxy.shared.protocol.ConnectionPathPlanner] 决定（与手机端
     * 同一个类），上次通的那条排最前；每次重连往后挪一格，走完一轮回到开头
     * （环境随时可能变回去，比如到家连上 WiFi）。
     *
     * 没配过对、或网关没给候选（老版本）→ 退回 [fallback] 那个单地址。
     */
    private fun nextConnectUrl(fallback: String): String {
        lastAttemptedUrl = fallback
        val stored = pairClaimClient.storedCandidates()
        if (stored.isEmpty()) return fallback
        val ordered = com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.planAttempts(
            stored.map {
                com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.Candidate(it.kind, it.url, it.priority)
            },
            pairClaimClient.lastGoodKind(),
        )
        if (ordered.isEmpty()) return fallback
        // tailnet 那条要经本机的转发进程;进程没就绪时跳过它,而不是去拨一个
        // 手表根本到不了的 100.x(手表没有 VPN)白等超时。
        val ready = tailnetDaemon.state.value as? com.galaxy.wear.network.TailnetDaemon.State.Ready
        repeat(ordered.size) {
            val pick = ordered[candidateCursor % ordered.size]
            candidateCursor = (candidateCursor + 1) % ordered.size
            val dial = com.galaxy.wear.network.TailnetProtocol.dialUrlFor(pick.url, ready?.listen, ready?.target)
            if (dial != null) {
                Log.i(TAG, "Reconnect will try candidate kind=${pick.kind}")
                lastAttemptedUrl = pick.url
                return dial
            }
        }
        return fallback
    }

    /**
     * 记下这次是哪条路通的 —— 下次先试它，省掉一整轮试探。
     *
     * 在外面走流量时，局域网那条每次都要白等一个超时才轮到能用的；不记的话
     * 每次断线都要重付这个代价。认不出来（老网关没给候选）就不记，
     * 而不是留一个猜的值。
     */
    private fun rememberWorkingPath(url: String) {
        val kind = pairClaimClient.storedCandidates().firstOrNull { it.url == url }?.kind ?: return
        pairClaimClient.rememberGoodKind(kind)
        candidateCursor = 0
    }

    // WARNING-9: Reusable discovery components to avoid creating new instances on every call.
    // 手表此前自带一份 MdnsDiscovery，与安卓仓 :shared-transport 的 GatewayDiscovery
    // 是同一件事的两份实现：同一个服务类型、同一个尾点坑、同一套 listener 清理。
    // 两份就意味着补丁只打在一边 —— 现在共用同一份。
    private val gatewayDiscovery by lazy { GatewayDiscovery(this) }

    /**
     * 凭据存储是否已降级为明文（[encryptedPrefs] 初始化失败）。
     *
     * 由 [encryptedPrefs] 的 lazy 初始化置位。**必须声明在 [encryptedPrefs] 之前** ——
     * 否则一旦将来有人在两者之间加一个会触碰 [encryptedPrefs] 的属性初始化器，
     * 这里的 `= false` 会在 lazy 块置位之后才执行，把降级标记悄悄抹掉。
     *
     * 外部请用 [isCredentialStorageDegraded] 读取，它保证 lazy 初始化已发生。
     */
    @Volatile
    var credentialStorageDegraded: Boolean = false
        private set

    /** 降级原因（异常消息），未降级时为 null。 */
    @Volatile
    var credentialStorageDegradedReason: String? = null
        private set

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
            // 降级到明文存储。此前这里只有一行 Log.e —— auth_token 会**静默**落进
            // 明文 SharedPreferences，用户看不见、V2 也无从知道这台设备的凭据
            // 实际上是裸存的。一次 logcat 里的 ERROR 不构成"用户知情"。
            //
            // 现在把这次降级记成进程级可查询的状态（credentialStorageDegraded），
            // 供 UI 与上行链路读取，让"这台表的 token 没有加密"成为一个
            // 可被看见、可被上报的事实，而不是一行没人看的日志。
            //
            // 仍然选择降级而不是直接崩：极少数机型确实不支持 AndroidKeyStore，
            // 崩掉等于这些设备完全不可用。取舍是"可用但被明确标记为降级"。
            Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plaintext: ${e.message}", e)
            credentialStorageDegraded = true
            credentialStorageDegradedReason = e.message ?: e::class.java.simpleName
            getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * 返回凭据存储是否处于明文降级状态。
     *
     * 会先触碰 [encryptedPrefs] 以确保 lazy 初始化已执行 —— 否则在任何人用过
     * 凭据存储之前调用本方法，永远返回 false，等于这个信号不存在。
     */
    fun isCredentialStorageDegraded(): Boolean {
        encryptedPrefs  // 触发 lazy 初始化
        return credentialStorageDegraded
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Galaxy Wear OS starting...")

        conversationRecorder = com.galaxy.wear.conversation.ConversationRecorder(
            com.galaxy.wear.conversation.ConversationStore(
                com.galaxy.wear.conversation.FileConversationStore.forContext(this)
            )
        )

        // WARNING-7: Wrap AIPClient initialization to prevent UninitializedPropertyAccessException
        // on subsequent accesses if the constructor throws.
        try {
            aipClient = AIPClient(
                context = this,
                scope = appScope,
                // 会话上下文:发出去的语音提问与收回来的回复都记进手表本地。
                // 此前这两个方向都在线上跑,但**回复那条没有任何代码读它** ——
                // 问出去的答案到了手表就被丢掉,于是手表上一条会话记录都没有。
                conversationRecorder = conversationRecorder,
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

        // Observe connection state (drives the home status line, the tile and the foreground notification)
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
                        lastAttemptedUrl?.let { rememberWorkingPath(it) }
                    }
                    // 认证通过后顺手把令牌养新：之后的重连要用它，而过期了就只能重新配对。
                    if (state == AIPConnectionState.AUTHENTICATED) {
                        appScope.launch {
                            val saved = encryptedPrefs.getString(KEY_AUTH_TOKEN, "").orEmpty()
                            if (saved.isNotEmpty()) freshToken(saved)
                        }
                    }
                    // 连接态变了，Tile 上的「已连接 / 未连接」要跟着变。
                    GalaxyTileService.requestRefresh(this@GalaxyWearApplication)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connection observer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Connection observer crashed: ${e.message}")
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
                    // 看 INTERNET 而不是 VALIDATED：onAvailable 的这一刻新网络多半还没验证完；
                    // 而网关常常就在没有外网出口的家庭局域网里，那种网络永远不会被判定为 VALIDATED，
                    // 于是回到家连上 Wi-Fi 也永远不触发重连。
                    val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (hasInternet && _connectionState.value == AIPConnectionState.DISCONNECTED) {
                        // P2-FIX: 失败次数多了就放慢，不是放弃。以前到上限就永久放弃——重置要靠
                        // 「连接成功」，而成功要先重连，所以放弃之后只能靠用户去设置里手动连。
                        val nowMs = System.currentTimeMillis()
                        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS &&
                            nowMs - lastNetworkReconnectMs < SLOW_RECONNECT_INTERVAL_MS
                        ) {
                            Log.d(TAG, "已达 $MAX_RECONNECT_ATTEMPTS 次，降为每 10 分钟最多重连一次")
                            return
                        }
                        lastNetworkReconnectMs = nowMs
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
                                    // 每次重连换一条候选，而不是在同一条上重试到死 ——
                                    // 后者正是"出门就连不上"的形状：局域网那条在外面
                                    // 永远超时，重试多少次都一样。
                                    // nextConnectUrl 自己记下 lastAttemptedUrl(候选的原始地址,
                                    // 不是经转发改写后的那个)。
                                    val target = nextConnectUrl(savedUrl)
                                    // 此处位于 appScope.launch 内,裸 this 指向 CoroutineScope;
                                    // getOrCreateDeviceId 需要 Context,故显式限定为 Application。
                                    aipClient.connect(
                                        target,
                                        freshToken(savedToken),
                                        DeviceIdProvider.getOrCreateDeviceId(this@GalaxyWearApplication),
                                    )
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

        // 出门直连:配过 tailnet 就把转发进程拉起来;它一就绪,若此刻没连着就立刻重连一次
        // (而不是等下一次网络变化 —— 在外面网络可能很久都不变)。
        ensureTailnet()
        appScope.launch {
            tailnetDaemon.state.collect { st ->
                if (st is com.galaxy.wear.network.TailnetDaemon.State.Ready &&
                    _connectionState.value.isTerminal
                ) {
                    val savedUrl = encryptedPrefs.getString(KEY_SERVER_URL, "") ?: ""
                    val savedToken = encryptedPrefs.getString(KEY_AUTH_TOKEN, "") ?: ""
                    if (savedUrl.isNotEmpty() && savedToken.isNotEmpty() && isAipClientReady()) {
                        Log.i(TAG, "tailnet 就绪且当前未连接 —— 立刻重连")
                        connect(nextConnectUrl(savedUrl), savedToken)
                    }
                }
            }
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
                    // liquid_event / state_event：只取其中给人看的内容（任务完成、进度、文字结果）。
                    // 其中的相位（to_phase）是电脑上的东西，手表不收、不显示。
                    aipClient.messages.collect { msg ->
                        when (msg.type) {
                            MsgType.LIQUID_EVENT,
                            MsgType.STATE_EVENT -> handleLiquidEvent(msg)
                            MsgType.DECISION_REQUEST -> handleDecisionRequest(msg)
                            MsgType.DECISION_WITHDRAW -> handleDecisionWithdraw(msg)
                            MsgType.AGENT_MESSAGE -> handleAgentMessage(msg)
                            MsgType.EXECUTION_PROPOSAL -> handleExecutionProposal(msg)
                            MsgType.COMMAND -> handleAgentCommand(msg)
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

    // 给人看的跨设备事件（liquid_event / state_event 里带 content 的那一类）：任务完成、进度、文字结果。
    // 三态（to_phase）是电脑上的东西，手表既不收也不显示 —— 没有 content 的 state_event 直接忽略。
    private fun handleLiquidEvent(event: AIPMessage) {
        // FIX: Wrap entire handler in try-catch to prevent Flow collection from terminating
        try {
            val payload = event.payload as? kotlinx.serialization.json.JsonObject
                ?: return
            val content = payload["content"]?.jsonObject ?: return
            val msgType = event.msgType

            // CRITICAL-3: Log only the message type, never the raw content which may contain
            // user-sensitive data (text results, device names, etc.).
            Log.i(TAG, "LiquidIsland: msg=$msgType received")
            when (msgType) {
                "task_done" -> {
                    // LIQUID-ISLAND: halo pulse + double-tap haptic
                    val deviceName = content["device_name"]?.jsonPrimitive?.content ?: "设备"
                    Log.i(TAG, "LiquidIsland: task done on $deviceName")
                    triggerHaptic(this, HapticType.TASK_DONE)
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiquidIsland: handleLiquidEvent error: ${e.message}")
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
    /**
     * 智能体主动发来的一条消息 —— 弹一条平常手表上那种消息通知。
     *
     * 这条消息在 [com.galaxy.wear.data.AIPClient] 里**已经**被记进会话上下文了
     * (并且用服务端 message_id 去过重)。能走到这里,说明它是新的一条 ——
     * 补发的那条在那一层就被拦掉,不会在手腕上震第二次。
     */
    private fun handleAgentMessage(event: com.galaxy.wear.data.AIPMessage) {
        try {
            val payload = event.payload as? JsonObject ?: return
            val text = payload["text"]?.jsonPrimitive?.content.orEmpty()
            if (text.isBlank()) return
            val messageId = payload["message_id"]?.jsonPrimitive?.content.orEmpty()
            val conversationId = payload["conversation_id"]?.jsonPrimitive?.content.orEmpty()
            Log.i(TAG, "AgentMessage: id=$messageId len=${text.length}")

            // 直接弹通知(原因同 handleDecisionRequest：后台不能启动前台服务)。
            WatchNotifications.showAgentMessageNotification(
                context = this,
                text = text,
                title = payload["title"]?.jsonPrimitive?.content.orEmpty(),
                messageId = messageId,
                conversationId = conversationId,
                // 只有协议说了期待回复,通知上才给回复入口 —— 每条都挂一个回复框,
                // 会让"只是告诉你一声"的那些也显得在等你答话。
                replyExpected = payload["reply_expected"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        } catch (e: Exception) {
            Log.e(TAG, "处理 agent_message 失败: ${e.message}")
        }
    }

    /**
     * 这条决策不用管了 —— 把通知收起来。
     *
     * 一条 decision_request 会被**并行分叉**给所有连着的手表与手机。此前某一台答完
     * 之后,其余每一台上那条还挂着:点它服务端是 no-op,可本地的 ReplyReceiver 会把
     * 通知消掉,于是用户以为自己答了,实际什么都没发生;更糟的是他可能在那边给了个
     * **不同**的答案。
     *
     * 服务端现在会在决策落定后发 decision_withdraw(SIP 分叉的 CANCEL 那一步)。
     * 这里接住它。
     *
     * reason 是封闭枚举(answered_elsewhere / timed_out / cancelled / superseded)。
     * 目前四种都是静默收起 —— 区分开是为了排障时看得出这条是怎么没的,以及将来
     * "超时"那种可以留一条痕迹而"别人答了"不该留。
     */
    /**
     * 中心在多台候选里挑一台之前,会问一轮「这件事你能不能做」。手表的答案是
     * **`unsupported`** —— 而且必须把这句话说出口,不能沉默。
     *
     * ## 为什么手表会被问到
     *
     * 中心按 `DeviceType.ANDROID` 挑候选,而 `ANDROID_WEAR` 就在这一类里 ——
     * 手表和手机会落进同一个候选池。
     *
     * ## 为什么沉默比说"不"更糟
     *
     * 中心把沉默记成 `no_response`。而「全场都是 no_response」被中心解释成
     * "这批设备根本不认识协商",于是**原样放行全部候选** —— 手表又回到候选里了。
     * 明确说一句 unsupported,手表当场出局,手机独得这一轮;这才是这轮问话的意义。
     *
     * ## 为什么答案是写死的
     *
     * 手表上没有任何任务执行路径:整个 app 不处理 `task_assign`,也不处理
     * `goal_execution`。它是通知、对话、通话和人在回路的决策界面,不是执行面。
     * 这不是保守起见留的余地,是此刻可查证的事实;哪天手表真有了执行面,
     * 这个方法要跟着改,而不是让它偷偷答应下来。
     */
    private fun handleExecutionProposal(event: AIPMessage) {
        try {
            val payload = event.payload as? JsonObject ?: return
            val proposalId = payload["proposal_id"]?.jsonPrimitive?.content ?: return
            Log.i(TAG, "ExecutionProposal: id=$proposalId → unsupported(手表没有执行面)")
            appScope.launch {
                try {
                    aipClient.sendCommand(
                        MsgType.EXECUTION_COMMITMENT.value,
                        buildJsonObject {
                            put("proposal_id", proposalId)
                            // 不带 device_id:AIPClient.deviceId 是 private,而且**本来就不该由这里带**。
                            // 中心以连接上的 device_id 为准 —— 设备自报的那个字段可以填别人的 id,
                            // 收了就等于允许冒名顶替。信封上的 device_id 由 sendCommand 带,那才是权威的。
                            // accepted 必须是真正的 Boolean:中心判的是 `is True`,
                            // 字符串 "false" 和 "true" 都会被当成"不接" —— 后者是巧合,不是设计。
                            put("accepted", false)
                            put("decline_reason", "unsupported")
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "回复 execution_proposal 失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理 execution_proposal 失败: ${e.message}")
        }
    }

    // ── 智能体经 devices__invoke 下发给手表的动作 ───────────────────────────

    /** 手表能替智能体做的事的真正实现(通知 / 触觉 / 状态)。判定与编排在 [AgentCommandExecutor] 里，可在 JVM 上测。 */
    private val agentCommands: AgentCommandExecutor by lazy { AgentCommandExecutor(AppWatchEffects()) }

    private inner class AppWatchEffects : WatchEffects {
        override fun notify(title: String, text: String, replyExpected: Boolean, conversationId: String): Boolean {
            // 和智能体主动发来的 agent_message 同一条路:记进会话，再弹通知。
            val messageId = "cmd_notify_${System.currentTimeMillis()}"
            conversationRecorder.recordAgentMessage(text = text, conversationId = conversationId, messageId = messageId)
            return WatchNotifications.showAgentMessageNotification(
                context = this@GalaxyWearApplication,
                text = text,
                title = title,
                messageId = messageId,
                conversationId = conversationId,
                replyExpected = replyExpected,
            )
        }

        override fun haptic(type: HapticType): Boolean = playHaptic(this@GalaxyWearApplication, type)

        override fun status(): JsonObject = buildJsonObject {
            put("connection", aipClient.connectionState.value.name.lowercase())
            put("app_version", BuildConfig.VERSION_NAME)
            put("notifications_enabled", WatchNotifications.canNotify(this@GalaxyWearApplication))
        }
    }

    private fun handleAgentCommand(event: AIPMessage) {
        val frame = event.payload as? JsonObject ?: return
        val cmd = AgentCommandParser.parse(frame)
        if (cmd == null) {
            // 没有 command_id 回了网关也认领不到，只能记一笔。
            Log.w(TAG, "收到格式不对的 command 帧(缺 command_id 或 command)，忽略")
            return
        }
        Log.i(TAG, "AgentCommand: id=${cmd.commandId} action=${cmd.action}")
        appScope.launch {
            val result = agentCommands.execute(cmd)
            try {
                aipClient.sendCommandResult(cmd.commandId, result.success, result.data, result.error)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "回 command_result 失败: ${e.message}")
            }
        }
    }

    private fun handleDecisionWithdraw(event: AIPMessage) {
        try {
            val payload = event.payload as? JsonObject ?: return
            val decisionId = payload["decision_id"]?.jsonPrimitive?.content ?: return
            val reason = payload["reason"]?.jsonPrimitive?.content ?: "cancelled"
            Log.i(TAG, "DecisionWithdraw: id=$decisionId reason=$reason")
            // 通知 id 必须和 WatchNotifications 弹它时用的那个一致 —— 两处都是
            // decisionId.hashCode()。不一致就收不掉,而且收不掉这件事没有任何报错。
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(decisionId.hashCode())
            // 岛上那张决策卡也要一并收掉：别处已经答了，这里再点选项只会答一个已落定的决策。
            dismissIslandItem(DecisionIslandIds.islandId(decisionId))
        } catch (e: Exception) {
            Log.w(TAG, "撤回决策通知失败: ${e.message}")
        }
    }

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
            // 直接弹通知，不经前台服务：手表在后台时 Android 12+ 不许应用启动前台服务，
            // 以前这里去拉前台服务会抛异常、被外层 catch 吞掉，决策通知就此消失。
            WatchNotifications.showDecisionNotification(
                context = this,
                title = title,
                summary = summary,
                decisionId = decisionId,
                options = optionIds,
                // 显示文字必须一并送过去 —— 只送 id 的话通知按钮印的是协议字面量。
                labels = decisionOptions.map { it.label },
            )

            pushIslandItem(
                IslandItem(
                    id = DecisionIslandIds.islandId(decisionId),
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
    /** 决策卡上说出来的回答（系统语音识别的结果）。回答的是这条决策，经同一条线上行。 */
    fun answerDecisionByVoice(decisionId: String, text: String) {
        replyToDecision(decisionId, voiceInput = text)
    }

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
                dismissIslandItem(DecisionIslandIds.islandId(decisionId))
            }
        }
    }

    // ── 配对令牌的续期 ───────────────────────────────────────────────────

    private val _needsRepair = MutableStateFlow(false)

    /**
     * 令牌已经不能用了、需要重新配对：网关明确拒绝了续期（过期 / 已撤销 / 这块表被移除），
     * 或者令牌已过期。界面据此提示「重新配对」，而不是静静地卡在「静默」。
     */
    val needsRepair: StateFlow<Boolean> = _needsRepair.asStateFlow()

    /**
     * 连接前保证令牌够新。快到期就先续；**续不了不阻塞连接**——旧令牌可能还没坏，断网时续不了
     * 是常态。只有网关明确说不行（或令牌已过期），才标记「需要重新配对」。
     *
     * 只续配对得来的令牌：连接用的令牌和配对存下的不是同一枚时（比如用户在设置里手填了静态令牌），
     * 不去动它。
     */
    private suspend fun freshToken(connectToken: String): String {
        if (pairClaimClient.storedToken() != connectToken) return connectToken
        val bases = buildList {
            add(encryptedPrefs.getString(KEY_SERVER_URL, "").orEmpty())
            // 续期走普通 HTTP，到不了本机转发口：tailnet 那条留给转发进程，这里只试其余候选。
            pairClaimClient.storedCandidates().filter { it.kind != "tailscale" }.forEach { add(it.url) }
        }
        return when (val r = pairClaimClient.renewIfDue(bases, DeviceIdProvider.getOrCreateDeviceId(this))) {
            is PairClaimClient.RenewResult.Renewed -> {
                encryptedPrefs.edit().putString(KEY_AUTH_TOKEN, r.token).apply()
                _needsRepair.value = false
                r.token
            }
            PairClaimClient.RenewResult.NeedsRepair -> {
                Log.w(TAG, "配对令牌不能用了，需要重新配对")
                _needsRepair.value = true
                connectToken
            }
            is PairClaimClient.RenewResult.Failed -> {
                Log.w(TAG, "令牌续期这次没成(${r.reason})，沿用旧令牌")
                connectToken
            }
            PairClaimClient.RenewResult.NotNeeded -> connectToken
        }
    }

    // FIX(connect): aipClient.connect() runs for the ENTIRE WebSocket session
    // lifetime — it only returns when the session ends. The previous
    // withTimeout(15_000) wrapper therefore killed every healthy session 15s
    // after connecting, after which nothing reconnected (the cancel path
    // schedules no retry). Establishment timeouts are enforced inside AIPClient
    // instead: 10s TCP/TLS connect timeout, 15s WS-upgrade request timeout, and
    // a 10s auth-ok watchdog that closes a session that never authenticates.
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
                aipClient.connect(serverUrl, freshToken(token), devId)
            } catch (e: CancellationException) {
                Log.d(TAG, "Connect cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
            }
        }
    }

    /**
     * 接入成功后的统一桥接入口。
     *
     * 为什么要有这么一个桥:配对客户端把令牌写进 galaxy_auth,而 connect() 读的是
     * galaxy_config/auth_token —— 两套存储不同文件、不同键。历史上这两处从不相交,
     * 于是"登录成功"也永远连不上。这里是唯一桥接点:把令牌与服务器地址落到 connect
     * 真正读取的那份,再立即连接;自动重连(onAvailable)之后也能凭它复连。
     */
    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "loginWithToken skipped — blank serverUrl or token")
            return
        }
        // 刚配完对:网关这次可能给了进 tailnet 的钥匙(或者换了网关、没给)—— 按最新的来。
        ensureTailnet()
        _needsRepair.value = false
        try {
            encryptedPrefs.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_AUTH_TOKEN, token)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Persisting login credentials failed: ${e.message}")
        }
        // ROUND-2-FIX: if a session is already up (e.g. re-login after token
        // expiry), connect() short-circuits on CONNECTED/AUTHENTICATED and the
        // live session would keep the OLD token forever. Disconnect first so
        // the new credentials actually take effect.
        if (isAipClientReady() && aipClient.isConnected()) {
            Log.i(TAG, "Re-login while connected — restarting session with new token")
            appScope.launch {
                try {
                    aipClient.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-relogin disconnect failed: ${e.message}")
                }
                connect(serverUrl, token)
            }
        } else {
            connect(serverUrl, token)
        }
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
    // NETWORK DISCOVERY: mDNS LAN auto-discovery
    // -----------------------------------------------------------------

    /**
     * Auto-discover gateway using mDNS (same Wi-Fi). Out-of-home reachability comes from the
     * candidates handed over at pairing, not from discovery.
     */
    suspend fun discoverGateway(): String? {
        // WARNING-9: Reuse cached discovery instances instead of creating new ones each call.
        // 1. Try mDNS LAN discovery first (zero-config, fastest)
        // CRITICAL-6: isWifiAvailable() relies on ConnectivityManager APIs that may behave
        // differently on Android 10+; ensure proper permissions (ACCESS_NETWORK_STATE).
        if (isWifiAvailable(this)) {
            Log.i(TAG, "Discovery: trying mDNS LAN...")
            // 2 秒保持手表侧原有的发现窗口 —— 共享类的默认值是 2.5 秒（手机首屏用户
            // 已经在等，多半秒换成功率划算），手表上多等半秒更肉眼可见，不跟着改。
            val lanUrl = gatewayDiscovery.discover(timeoutMs = MDNS_TIMEOUT_MS)?.wsUrl
            if (lanUrl != null) {
                Log.i(TAG, "Discovery: found via mDNS: $lanUrl")
                return lanUrl
            }
        }

        // 手表上没有「Tailscale 网段扫描」这一步：它进 tailnet 靠 App 里的转发进程，不是系统 VPN，
        // 系统网卡上永远看不到 100.x 地址，扫网段没有东西可扫。出门直连走配对时拿到的候选地址
        // （见 ConnectionPathPlanner / TailnetDaemon），不是靠发现。

        Log.w(TAG, "Discovery: no gateway found via mDNS")
        return null
    }

    /**
     * Quick check: what network types are available?
     */
    fun getNetworkStatus(): String {
        // WARNING-9: Reuse cached discovery instances.
        val wifi = isWifiAvailable(this)
        // 手表进 tailnet 靠的是 App 里的转发进程,不是系统 VPN —— 系统网卡上永远看不到
        // 100.x 地址,所以这里读转发进程的状态,而不是去枚举网卡。
        val tailnet = when (val st = tailnetDaemon.state.value) {
            is com.galaxy.wear.network.TailnetDaemon.State.Ready -> "tailnet ${st.tailnetIp}"
            com.galaxy.wear.network.TailnetDaemon.State.Starting -> "tailnet 连接中"
            com.galaxy.wear.network.TailnetDaemon.State.NeedsLogin -> "tailnet 需重新配对"
            is com.galaxy.wear.network.TailnetDaemon.State.Failed -> "tailnet 失败:${st.message.take(40)}"
            is com.galaxy.wear.network.TailnetDaemon.State.Unavailable -> null
            com.galaxy.wear.network.TailnetDaemon.State.Off -> null
        }
        return when {
            wifi && tailnet != null -> "Wi-Fi + $tailnet"
            wifi -> "Wi-Fi"
            tailnet != null -> tailnet
            else -> "No network"
        }
    }

    // CRITICAL-FIX: onTerminate() is NEVER called on real Android devices — it only
    // runs in emulator. We rely on onTrimMemory() for best-effort cleanup.
    //
    // ROUND-3-FIX: only release the NetworkCallback at TRIM_MEMORY_COMPLETE.
    // Previously this fired at `level >= TRIM_MEMORY_MODERATE`. TRIM_MEMORY_MODERATE
    // (and BACKGROUND) are ROUTINE background states the system delivers whenever a
    // backgrounded watch app sits mid-LRU under memory pressure — i.e. almost all the
    // time on Wear OS, since the watch face owns the foreground. Because the callback
    // is (re)registered ONLY in onCreate(), unregistering at MODERATE permanently
    // killed the onAvailable() auto-reconnect path for the rest of the process
    // lifetime: walk out of Wi-Fi range and back and the watch never reconnects until
    // a full relaunch. TRIM_MEMORY_COMPLETE is the only level that signals the process
    // is about to be reclaimed, so that is where cleanup is both safe and useful; at
    // every lower level the callback must stay registered so network recovery still
    // triggers a reconnect.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_COMPLETE) {
            networkCallback?.let { cb ->
                try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(cb)
                    Log.d(TAG, "NetworkCallback unregistered in onTrimMemory(COMPLETE)")
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

    // 内存紧张时**不**断开到智能体的连接。
    //
    // 以前这里主动 disconnect()，理由是「防泄漏」。可这条连接就是手表存在的全部意义：断了之后
    // 智能体的提问、下发的动作都送不到，而且要等下一次「满足条件的网络事件」才会重连
    // （还受自动重连次数限制）。进程挂着前台服务，系统真要回收会按优先级自己来；
    // 一条空闲的 WebSocket 占的内存微不足道，不值得拿「联系不上智能体」去换。
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory — system under memory pressure (connection kept)")
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
