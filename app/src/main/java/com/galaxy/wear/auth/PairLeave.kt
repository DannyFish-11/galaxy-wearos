package com.galaxy.wear.auth

/**
 * 解除配对：网关的回答怎么算、怎么对人说。
 *
 * 加入是这块表自己的事（配对即接入），退出也是：表上点「解除配对」，凭手里那枚配对令牌调
 * `POST /api/v1/pair/leave`，智能体那边作废令牌、把它移出名册和 tailnet。
 *
 * 不论网关怎么回，本机都忘掉这次配对 —— 人要退出，不能因为此刻连不上智能体就退不掉。
 * 连不上时那枚令牌最多 24 小时自然失效，也可以在面板上移除。
 *
 * 纯 Kotlin，不碰 Android。
 */
object PairLeave {

    /** 设备凭自己的配对令牌退出。V2 侧对它免鉴权（手里只有配对令牌），端点自己核验。 */
    const val PATH = "/api/v1/pair/leave"

    enum class Outcome {
        /** 智能体那边已作废令牌、移出名册。 */
        LEFT,

        /** 那边本来就不认这枚令牌了（过期 / 已撤销 / 已被移除）。 */
        ALREADY_GONE,

        /** 一个网关都没连上（或回了说不清的话）。 */
        UNREACHABLE,
    }

    /** @param success 回应体里的 `success`；解不出为 null。 */
    fun outcomeOf(status: Int, success: Boolean?): Outcome = when {
        status in 200..299 && success == true -> Outcome.LEFT
        status == 401 || status == 403 -> Outcome.ALREADY_GONE
        else -> Outcome.UNREACHABLE
    }

    fun text(outcome: Outcome): String = when (outcome) {
        Outcome.LEFT -> "已解除配对，智能体那边也已移除这块表"
        Outcome.ALREADY_GONE -> "已解除配对（智能体那边早已不认这块表）"
        Outcome.UNREACHABLE -> "本机已解除配对；没连上智能体，它那边的令牌 24 小时内自然失效，也可在面板上移除"
    }
}
