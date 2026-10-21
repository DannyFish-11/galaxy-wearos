package com.galaxy.wear.auth

/**
 * 配对结果怎么对人说。
 *
 * 为什么要映射
 * ============
 * 网关 / 客户端给的是稳定的机器码（`too_many_attempts`、`no_headscale_url`……）。界面只拿到码就只能
 * 糊成一句「失败」—— 而下一步该做什么完全不同：重输、等几分钟、去设置填地址、去电脑上配 headscale。
 *
 * 另外一类容易被丢掉的信息：**配对成功了，但出门直连没开成**（网关没配 headscale、电脑没加入
 * tailnet……）。配对本身不受影响，所以界面一旦直接跳走，用户就只会在第一次出门时发现「连不上」，
 * 而没有任何线索说原因在这里。
 *
 * 纯 Kotlin，不碰 Android。
 */
object PairingMessages {

    /** 接入失败时对人说的话。小屏，一句话，说清下一步。 */
    fun claimErrorText(error: String?): String = when (error) {
        "too_many_attempts" -> "错太多次，等几分钟"
        "network_error" -> "连不上网关"
        "need_server_address" -> "先在设置里填网关地址"
        "cleartext_not_allowed" -> "明文只能连内网地址，请改用 wss:// 或内网地址"
        "no_token_issued" -> "被拒绝接入"
        "missing_device_id" -> "本机标识为空"
        "need_code_or_link" -> "先输入短码"
        null, "", "claim_rejected" -> "码无效或已过期"
        else -> if (error.startsWith("bad_response")) "网关回应异常" else "码无效或已过期"
    }

    /**
     * 配对成功、但出门直连没开成时，对人说的话；一切正常（或网关没说）返回 null。
     *
     * @param reason 网关 `tailnet_join_unavailable.reason`
     */
    fun tailnetNotice(reason: String?): String? {
        if (reason.isNullOrEmpty()) return null
        val why = when (reason) {
            "no_headscale_url", "no_api_key" -> "智能体那边还没配 headscale"
            "api_key_rejected" -> "智能体那边的 headscale 密钥已失效"
            "headscale_unreachable", "headscale_error" -> "智能体连不上 headscale"
            "no_such_user" -> "headscale 上没有对应用户"
            // 手表连的是智能体（网关入口），不是「电脑」这台设备；智能体所在的机器没入 tailnet，手表出门就找不到它。
            // 配了 headscale 网关启动时就自动入网，不看任何模式；没入成多半是那台机器没装 Tailscale 客户端。
            "desktop_not_on_tailnet" -> "智能体那边还没加入 tailnet（在它那台机器上装好 Tailscale，重启后会自动加入）"
            else -> reason
        }
        return "出门直连未开启：$why。在家的局域网直连不受影响"
    }

    /**
     * 「自动发现网关」没找到时对人说的话。
     *
     * 只说「未发现网关」，人就只会反复点。智能体一直往局域网广播自己（不看任何模式），
     * 找不到最常见的原因是手表和它不在同一个 Wi-Fi（或路由器挡了组播）。
     * 所以告诉人两条出路：连到同一个 Wi-Fi，或手填地址。
     */
    fun gatewayNotFoundText(): String = "没找到智能体。请让手表和它连同一个 Wi-Fi，或手填地址"
}
