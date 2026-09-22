package com.galaxy.wear.data

/**
 * 连接的五个状态。
 *
 * 单独一个文件,理由同 [AipPureLogic]:它原先长在 `AIPClient.kt` 里,而那个文件
 * import 了 Ktor、Context、PowerManager —— 于是这十行连枚举值都验不了。
 */
enum class AIPConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR;

    /**
     * 终态 = 不会自己再往前走了。
     *
     * 重连调度读这一位决定"还要不要再试"。把 CONNECTING 算进来会让它停在半路,
     * 把 DISCONNECTED 漏掉会让它永远重试 —— 两种错都很安静。
     */
    val isTerminal: Boolean
        get() = this == DISCONNECTED || this == ERROR
}
