package com.galaxy.wear.auth

import com.galaxy.wear.data.AipPureLogic
import com.ufo.galaxy.shared.protocol.CleartextPolicy

/**
 * 配对 / 续期请求往哪儿发。
 *
 * 为什么单独一个纯函数
 * ====================
 * 此前 `PairClaimClient.buildApiUrl` 只做了「ws→http」的字面替换,有三处问题:
 *
 *  1. **不看明文策略。** 连 WebSocket 时 `AIPClient.connect` 会用 [CleartextPolicy] 拦下「拿明文连公网地址」,
 *     但配对请求绕过了它 —— 配对码、刚签发的令牌、headscale 一次性钥匙,都可以在一个用户手填的
 *     `http://公网地址` 上明文来回。网络安全配置又是全局放行明文的(明文判定交给代码),
 *     所以这里不拦就没有人拦。
 *  2. **不处理没写协议的地址。** `192.168.1.5:9000` 被原样拼成 `192.168.1.5:9000/api/v1/pair/claim`,
 *     Ktor 直接报错,界面只显示「网络错误」。
 *  3. **不剥路径。** 设置里存的服务器地址常常是完整的 WebSocket 地址
 *     (`ws://host:9000/ws/device/<id>`),拼出来是 `…/ws/device/<id>/api/v1/pair/renew`,404。
 *
 * 纯 Kotlin,不碰 Android。
 */
object PairUrl {

    /**
     * 把用户 / 配置里的网关地址整理成 HTTP 基址(`http(s)://host[:port]`,无路径无结尾斜杠)。
     *
     * @return 基址;地址为空或明文策略不允许(明文只许对内网地址)时返回 null。
     */
    fun httpBase(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val ws = AipPureLogic.normalizeScheme(trimmed)
        if (!CleartextPolicy.isPermitted(ws)) return null
        val isTls = ws.startsWith("wss://")
        val rest = ws.removePrefix("wss://").removePrefix("ws://")
        val hostPort = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.isEmpty()) return null
        return (if (isTls) "https://" else "http://") + hostPort
    }

    /** [httpBase] 加上 API 路径。 */
    fun apiUrl(rawUrl: String, path: String): String? =
        httpBase(rawUrl)?.let { "$it/${path.trimStart('/')}" }
}
