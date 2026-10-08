package com.galaxy.wear.auth

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 配对令牌什么时候到期、该不该续。
 *
 * V2 的能力令牌形如 `<前缀>.<base64url(JSON 载荷)>.<签名>`,载荷里有 `exp`(秒级时间戳)。
 * 手表**不验签**(验签是网关的事,密钥也不在手表上),只读 `exp` 来决定什么时候去续 ——
 * 读错了的后果是「多续一次」或「晚续一次」,续的时候网关会真正验。
 *
 * 默认 24 小时有效期。续期条件是旧令牌**还有效**(网关: 签名对、没过期、没撤销),所以必须在到期前
 * 续:过期了就只能重新配对。离到期不足 [RENEW_BEFORE_MS] 就续,给断网留足余地。
 *
 * 不是能力令牌的(比如手填的静态 API 令牌)读不出 `exp` → 当成「无需续期」,不去碰它。
 */
object CapabilityTokenExpiry {

    /** 离到期不足这么久就续。24h 的令牌,最多在到期前 6 小时才续。 */
    const val RENEW_BEFORE_MS: Long = 6L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 令牌的到期时刻(毫秒);读不出来(不是能力令牌 / 载荷坏了)返回 null。 */
    fun expiresAtMs(token: String): Long? {
        val parts = token.trim().split('.')
        if (parts.size != 3) return null
        return try {
            val claims = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).jsonObject
            claims.exp()
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.exp(): Long? = this["exp"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }

    /** 已经过期了(读得出 exp 且 exp 早于现在)。过期的令牌续不了,只能重新配对。 */
    fun isExpired(token: String, nowMs: Long): Boolean = expiresAtMs(token)?.let { it <= nowMs } ?: false

    /** 该去续了:读得出 exp、还没过期、离到期不足 [RENEW_BEFORE_MS]。 */
    fun shouldRenew(token: String, nowMs: Long, renewBeforeMs: Long = RENEW_BEFORE_MS): Boolean {
        val exp = expiresAtMs(token) ?: return false
        return exp > nowMs && exp - nowMs < renewBeforeMs
    }
}
