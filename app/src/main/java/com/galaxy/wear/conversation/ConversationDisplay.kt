package com.galaxy.wear.conversation

/**
 * 会话列表的**排版决定**——从消息列表算出每一行怎么显示。
 *
 * 单独拎出来，是因为这里全是判定：哪条该显示时间、哪条算"接着上一句说的"。
 * 把它写在 Composable 里就只能靠肉眼在表上看，而这类规则出错的样子恰恰很隐蔽
 * （每条都显示时间 → 屏幕被时间戳塞满；一条都不显示 → 分不清隔了一天还是隔了一秒）。
 */
object ConversationDisplay {

    /**
     * 距上一条超过这么久，就重新显示一次时间。
     *
     * 五分钟不是随手取的：手表屏幕一次只放得下三四条，每条都带时间戳会把内容挤没；
     * 而一段连续对话里相邻两句通常在几十秒内，五分钟足以把"接着说"和"过了一阵又说"
     * 分开。
     */
    const val TIMESTAMP_GAP_MS: Long = 5 * 60 * 1000L

    /**
     * 一行的显示信息。
     *
     * @param message      这条消息本身。
     * @param showTimestamp 这一行要不要显示时间。
     * @param continuesPrevious 是不是和上一条同一个人连着说的（可以省掉重复的身份标记）。
     */
    data class Row(
        val message: ConversationMessage,
        val showTimestamp: Boolean,
        val continuesPrevious: Boolean,
    )

    /**
     * 把消息列表算成显示行。输入按时间升序（[ConversationStore] 保证）。
     *
     * 规则：
     *  · 第一条总是显示时间——否则整屏没有任何时间参照；
     *  · 距上一条超过 [TIMESTAMP_GAP_MS] 时重新显示；
     *  · 与上一条同一个人、且没有重新显示时间，算作"接着说"。
     */
    fun rowsFor(messages: List<ConversationMessage>): List<Row> {
        var previous: ConversationMessage? = null
        return messages.map { message ->
            val prev = previous
            val showTimestamp = prev == null ||
                (message.timestampMs - prev.timestampMs) >= TIMESTAMP_GAP_MS
            val continues = prev != null && prev.role == message.role && !showTimestamp
            previous = message
            Row(message = message, showTimestamp = showTimestamp, continuesPrevious = continues)
        }
    }
}
