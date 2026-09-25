package com.galaxy.wear.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * PairClaimClient — 手表侧的设备接纳客户端。
 *
 * 一步换令牌
 * ==========
 * 桌面面板出示名片（二维码 + 6 位短码），手表把**码**连同**自己的身份**交给
 * `/api/v1/pair/claim`，当场换回一枚属于自己的能力令牌，外加"接下来往哪儿连"的
 * 候选路径清单。
 *
 * 换掉了什么
 * ==========
 * 此前走的是 `/auth/oauth/device/start` → 轮询 `/poll` 的 OAuth 2.0 Device Flow
 * (RFC 8628)：手表显示一个码，你去手机或电脑上打开一个网址、登录、输码、授权，
 * 手表这边最长轮询 30 分钟。Android 那侧又是另一套「申请 → 桌面批准 → 领取」。
 * 三种设备三条路，而它们要接的是同一台机器。
 *
 * 现在三仓统一到 `/api/v1/pair/claim` 这一条。对手表来说这还顺带去掉了"必须有
 * 另一块屏幕才能完成授权"这个前提 —— 6 位短码的字符集刻意去掉了 0/O/1/I/L 这些
 * 易混字符，就是为了能被口述、能在小屏上输。
 *
 * 令牌签给谁
 * ==========
 * 签给**本机**。名片只证明"这个人手里有一张桌面签发、还没过期的邀请"，不证明
 * "这个人就是名片上那台机器"——邀请本来就可转交。所以 [deviceId] 必须是本表
 * 连 `/ws/device/{id}` 时自报的那个 id：服务端把它写进令牌 subject，设备入口再
 * 拿 subject 与握手时自报的 id 对一次。两者不一致就是"已配对却连不上"。
 *
 * 为什么令牌进加密存储、路径不进
 * ==============================
 * 令牌等价于这块表能对你的机器做什么，走 EncryptedSharedPreferences；
 * 候选路径只是地址，加密没有收益，却会让"当前走哪条路"这类诊断多一层
 * Keystore 依赖 —— 而那正是排障时最需要能直接看到的东西。
 */
class PairClaimClient(private val context: Context) {

    /** 一条候选路径。[priority] 从 1 起连续编号，设备端按它依次试。 */
    data class Candidate(val kind: String, val url: String, val priority: Int)

    /**
     * 进 tailnet 的配置:往哪个 headscale 登记,以及(首次加入前)那把一次性钥匙。
     *
     * 钥匙用掉就删([clearTailnetAuthKey]):之后手表凭自己的节点身份重连,
     * 留着一把已作废的钥匙只会在排障时误导人。
     */
    data class TailnetJoin(val controlUrl: String, val pendingAuthKey: String?)

    /** 接纳结果。[ok] 为 true 时 [token] 非空。 */
    data class ClaimResult(
        val ok: Boolean,
        val token: String? = null,
        val candidates: List<Candidate> = emptyList(),
        val gatewayDeviceId: String? = null,
        val scopes: List<String> = emptyList(),
        val error: String? = null,
        /** 网关给了进 tailnet 的钥匙 —— 出门直连可用。 */
        val tailnetJoin: TailnetJoin? = null,
        /** 网关没给钥匙的原因(比如没配 headscale)。在家的局域网直连不受影响。 */
        val tailnetUnavailableReason: String? = null,
    )

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(jsonFormat) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
            }
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy {
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
    }

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * 凭短码或链接接纳本表，当场领取令牌并落盘。
     *
     * @param serverUrl 网关地址。可以是 ws/wss 形态，内部会转成 http/https。
     * @param deviceId 本表连 WS 时自报的那个 id —— 令牌签给它。
     * @param code 面板上的 6 位短码（大小写不敏感）。与 [link] 二选一。
     * @param link 扫码得到的 `galaxy://pair?...` 链接。与 [code] 二选一。
     */
    suspend fun claim(
        serverUrl: String,
        deviceId: String,
        deviceName: String? = null,
        code: String? = null,
        link: String? = null,
    ): ClaimResult {
        if (code.isNullOrBlank() && link.isNullOrBlank()) {
            return ClaimResult(ok = false, error = "need_code_or_link")
        }
        if (deviceId.isBlank()) {
            return ClaimResult(ok = false, error = "missing_device_id")
        }

        val url = buildApiUrl(serverUrl, PATH_CLAIM)
        return try {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        if (!code.isNullOrBlank()) put("code", code.trim().uppercase())
                        if (!link.isNullOrBlank()) put("link", link.trim())
                        put("device_id", deviceId)
                        if (!deviceName.isNullOrBlank()) put("name", deviceName)
                        put("device_type", "wearos")
                    }
                )
            }

            if (response.status.value == HTTP_TOO_MANY_REQUESTS) {
                // 服务端按来源节流猜错次数。**可恢复**，与"码不对"要分开报 ——
                // 否则用户会在小屏上一遍遍重输一个其实没问题的码。
                return ClaimResult(ok = false, error = "too_many_attempts")
            }

            val body = runCatching { jsonFormat.parseToJsonElement(response.bodyAsText()).jsonObject }
                .getOrNull()
                ?: return ClaimResult(ok = false, error = "bad_response_${response.status.value}")

            // 缺字段按 false 处理：拿不到"成功"的证据就不能当成成功。
            if (body["success"]?.jsonPrimitive?.booleanOrNull != true) {
                val why = body["error"]?.jsonPrimitive?.contentOrNull ?: "claim_rejected"
                Log.w(TAG, "[PAIR] claim rejected: $why")
                return ClaimResult(ok = false, error = why)
            }

            val token = body["capability_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return ClaimResult(ok = false, error = "no_token_issued")

            val candidates = parseCandidates(body["candidates"] as? JsonArray)
            val tailnet = (body["tailnet_join"] as? JsonObject)?.let { tj ->
                val url = tj["control_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val key = tj["auth_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.isBlank() || key.isBlank()) null else TailnetJoin(url, key)
            }
            val tailnetWhyNot = (body["tailnet_join_unavailable"] as? JsonObject)
                ?.get("reason")?.jsonPrimitive?.contentOrNull
            persist(token, candidates)
            persistTailnet(tailnet)

            ClaimResult(
                ok = true,
                token = token,
                candidates = candidates,
                gatewayDeviceId = body["gateway_device_id"]?.jsonPrimitive?.contentOrNull,
                scopes = (body["token_scopes"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList(),
                tailnetJoin = tailnet,
                tailnetUnavailableReason = tailnetWhyNot,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[PAIR] claim failed: ${e.message}")
            ClaimResult(ok = false, error = "network_error")
        }
    }

    /** 已存下来的令牌；没有返回 null。 */
    fun storedToken(): String? =
        encryptedPrefs.getString(KEY_GATEWAY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** 已存下来的候选路径（按 priority 排好序）；没配过返回空表。 */
    fun storedCandidates(): List<Candidate> {
        val raw = plainPrefs.getString(KEY_CANDIDATES, null) ?: return emptyList()
        return runCatching { parseCandidates(jsonFormat.parseToJsonElement(raw).jsonArray) }
            .getOrElse {
                // 存坏了 ≠ 没配过。留痕 —— 静默当成空表会让手表退回单地址逻辑，
                // 表现成"换个网就连不上"，而没人知道是这份缓存坏了。
                Log.w(TAG, "[PAIR] 候选路径缓存存在但解不出（不等于没配过）: ${it.message}")
                emptyList()
            }
    }

    /** 上次连通成功的那条路径的 kind；没有返回 null。 */
    fun lastGoodKind(): String? =
        plainPrefs.getString(KEY_LAST_GOOD, null)?.takeIf { it.isNotBlank() }

    /** 记住这次是哪条路通的 —— 下次先试它，省掉整轮试探。 */
    fun rememberGoodKind(kind: String) {
        plainPrefs.edit().putString(KEY_LAST_GOOD, kind).apply()
    }

    /** 进 tailnet 的配置;从没拿到过返回 null。 */
    fun storedTailnetJoin(): TailnetJoin? {
        val url = plainPrefs.getString(KEY_TAILNET_CONTROL_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val key = encryptedPrefs.getString(KEY_TAILNET_AUTH_KEY, null)?.takeIf { it.isNotBlank() }
        return TailnetJoin(url, key)
    }

    /** 钥匙已经用掉(手表已加入 tailnet)。 */
    fun clearTailnetAuthKey() {
        encryptedPrefs.edit().remove(KEY_TAILNET_AUTH_KEY).apply()
    }

    /**
     * 回环转发口。首次随机选、之后固定 —— 转发地址在子进程重启后不变,
     * AIPClient 自己的重连循环才不会拨一个已经换掉的端口。
     */
    fun tailnetListenPort(pick: () -> Int): Int {
        val cur = plainPrefs.getInt(KEY_TAILNET_LISTEN_PORT, 0)
        if (cur > 0) return cur
        val p = pick()
        plainPrefs.edit().putInt(KEY_TAILNET_LISTEN_PORT, p).apply()
        return p
    }

    /**
     * 每次配对都以网关这一次的回答为准:给了钥匙就换上;没给就清掉旧的 ——
     * 换了一台没配 headscale 的网关,留着旧 headscale 的配置只会让手表去登记一个
     * 不相干的控制服务器。
     */
    private fun persistTailnet(join: TailnetJoin?) {
        if (join == null) {
            plainPrefs.edit().remove(KEY_TAILNET_CONTROL_URL).apply()
            encryptedPrefs.edit().remove(KEY_TAILNET_AUTH_KEY).apply()
            return
        }
        plainPrefs.edit().putString(KEY_TAILNET_CONTROL_URL, join.controlUrl).apply()
        encryptedPrefs.edit().putString(KEY_TAILNET_AUTH_KEY, join.pendingAuthKey.orEmpty()).apply()
    }

    private fun persist(token: String, candidates: List<Candidate>) {
        encryptedPrefs.edit().putString(KEY_GATEWAY_TOKEN, token).apply()
        val arr = candidates.joinToString(",", "[", "]") { c ->
            """{"kind":"${c.kind}","url":"${c.url}","priority":${c.priority}}"""
        }
        plainPrefs.edit()
            .putString(KEY_CANDIDATES, if (candidates.isEmpty()) "" else arr)
            // 换了网关就把"上次通的那条"清掉 —— 留着会让下次先去试一条属于
            // 旧网关的路径，白等一轮超时。
            .putString(KEY_LAST_GOOD, "")
            .apply()
    }

    private fun parseCandidates(arr: JsonArray?): List<Candidate> {
        if (arr == null) return emptyList()
        val out = ArrayList<Candidate>(arr.size)
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@forEachIndexed
            out.add(
                Candidate(
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "unknown",
                    url = url,
                    // 缺 priority 时按数组次序兜底，而不是丢掉这一条 ——
                    // 丢掉等于少一条可达路径，而那正是这个字段存在的理由。
                    priority = o["priority"]?.jsonPrimitive?.intOrNull ?: (i + 1),
                )
            )
        }
        return out.sortedBy { it.priority }
    }

    private fun buildApiUrl(baseUrl: String, path: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val url = when {
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            else -> trimmed
        }
        return "$url/${path.trimStart('/')}"
    }

    fun dispose() {
        runCatching { httpClient.close() }
    }

    companion object {
        private const val TAG = "PairClaimClient"

        /** 三仓统一的接纳端点。V2 侧对它**免鉴权** —— 还没配对的设备手里没有任何令牌。 */
        const val PATH_CLAIM = "/api/v1/pair/claim"

        private const val PREFS_FILE = "galaxy_auth"
        private const val PLAIN_PREFS_FILE = "galaxy_pairing"
        const val KEY_GATEWAY_TOKEN = "gateway_token"
        const val KEY_CANDIDATES = "gateway_candidates_json"
        const val KEY_LAST_GOOD = "last_good_candidate_kind"
        const val KEY_TAILNET_CONTROL_URL = "tailnet_control_url"
        const val KEY_TAILNET_LISTEN_PORT = "tailnet_listen_port"

        /** 一次性钥匙等价于"能以一台新设备的身份进你的 tailnet",进加密存储。 */
        const val KEY_TAILNET_AUTH_KEY = "tailnet_auth_key"
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
