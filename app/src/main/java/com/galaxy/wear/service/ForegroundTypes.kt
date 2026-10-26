package com.galaxy.wear.service

/**
 * 常驻服务([GalaxyWearService])以什么前台服务类型起。
 *
 *  * **Android 14+:specialUse**。dataSync 在 Android 15 起每 24 小时只许跑 6 小时,到点系统调 onTimeout,
 *    服务不停就抛 RemoteServiceException 把整个应用崩掉;而且 BOOT_COMPLETED 接收器不许启动 dataSync。
 *    这个服务的本职是「常驻、保持到网关的连接」,正好是 dataSync 的限制要挡的那种用法。
 *  * **Android 14+ 另加 health**,当且仅当给了身体传感器权限、并且是手表上的 App 界面把服务拉起来的。
 *    身体传感器是「使用期间」权限:App 在后台时,只有在前台时起的 health 类型前台服务才读得到心率
 *    (智能体调 `heart_rate`、可打扰性的常驻采样都靠它)。而在后台以 health 类型起前台服务,Android 14 起
 *    直接抛 `SecurityException` —— 开机自启、粘性重启都算后台,所以那时只用 specialUse。
 *  * Android 10–13:dataSync(那里没有 6 小时的期限)。更老的不分类型。
 *
 * 纯 Kotlin,单测直接跑。
 */
object ForegroundTypes {

    enum class Kind { SPECIAL_USE, DATA_SYNC, HEALTH }

    /** 这次起服务要的类型。[sdk] 是 `Build.VERSION.SDK_INT`。 */
    fun forWatch(sdk: Int, bodySensorsGranted: Boolean, fromVisibleUi: Boolean): Set<Kind> = when {
        sdk >= 34 && bodySensorsGranted && fromVisibleUi -> setOf(Kind.SPECIAL_USE, Kind.HEALTH)
        sdk >= 34 -> setOf(Kind.SPECIAL_USE)
        sdk >= 29 -> setOf(Kind.DATA_SYNC)
        else -> emptySet()
    }

    /** 带 health 起不来时退回的类型。 */
    fun plain(sdk: Int): Set<Kind> = forWatch(sdk, bodySensorsGranted = false, fromVisibleUi = false)

    /**
     * 回到 App 界面时要不要再起一次服务,把 health 补上:身体传感器权限已给、服务在跑([running] 不为 null)、
     * 却还没带 health(权限是服务起来之后才给的,或服务是在后台重启的)。
     */
    fun needsUpgrade(sdk: Int, bodySensorsGranted: Boolean, running: Set<Kind>?): Boolean =
        sdk >= 34 && bodySensorsGranted && running != null && Kind.HEALTH !in running
}
