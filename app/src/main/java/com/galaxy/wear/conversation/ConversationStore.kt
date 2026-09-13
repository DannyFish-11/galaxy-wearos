package com.galaxy.wear.conversation

/**
 * 手表上的**会话上下文**。
 *
 * 在此之前手表一条会话记录都没有
 * ================================
 * 整个手表工程里没有任何一处存过对话:46 个源文件里,`message` / `conversation` /
 * `history` 这些词一次都没出现过。表现出来就是 —— 你对着手表说一句,它回一句,
 * 抬手再看时什么都没有,连刚才问过什么都翻不出来。
 *
 * 而这不是因为数据拿不到。两个方向**本来就在线上跑**:
 *
 *  · 发:[com.galaxy.wear.data.AIPClient.sendVoiceQuery] 把转写文本送上去;
 *  · 收:服务端以 `command_result` 回来,正文在 `payload.data.text`。
 *
 * 只是收回来那条**没有任何代码读它** —— 问出去的答案到了手表就被丢掉。
 * 所以"上下文"缺的不是协议,是一个存的地方。
 *
 * ## 为什么有上限,以及为什么是丢最旧的
 * 手表存储小,而对话是只增不减的。不设上限,迟早把用户的存储吃光;
 * 满了丢最新的等于"越用越看不到刚说的话",只能丢最旧的。
 *
 * ## 为什么去重
 * 断线重连后服务端可能补发,同一条会到两次。靠 [ConversationMessage.id] 去重 ——
 * 靠"内容+时间"判重会误杀真实重复(用户确实可能连说两句一样的话)。
 *
 * 纯 Kotlin,存储抽成 [Store]:存取是纯逻辑,不该为了验证它去起一个 Android 运行时。
 */
class ConversationStore(
    private val store: Store,
    /** 最多保留多少条。超出后从最旧的开始丢。 */
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
) {

    /** 持久化后端。实现只需保证 [write] 返回时已经落下去。 */
    interface Store {
        fun read(): List<ConversationMessage>
        fun write(messages: List<ConversationMessage>)
    }

    private val lock = Any()

    /** 内存镜像。构造时从 [store] load 一次,之后以它为准。 */
    private val messages: MutableList<ConversationMessage> =
        store.read().sortedBy { it.timestampMs }.toMutableList()

    private val seenIds: MutableSet<String> =
        messages.mapTo(mutableSetOf()) { it.id }

    /**
     * 记一条。返回是否真的记进去了(false = 这条 id 已经有了)。
     *
     * 去重与裁剪都在这里做,调用方不需要知道上限是多少。
     */
    fun append(message: ConversationMessage): Boolean = synchronized(lock) {
        if (message.id.isBlank()) return false
        if (!seenIds.add(message.id)) return false

        messages.add(message)
        // 乱序到达(补传)时按发生时间归位 —— 界面按时间读,不能靠到达顺序。
        messages.sortBy { it.timestampMs }

        while (messages.size > maxMessages) {
            val dropped = messages.removeAt(0)
            seenIds.remove(dropped.id)
        }
        store.write(messages.toList())
        true
    }

    /** 全部消息,按发生时间升序。 */
    fun all(): List<ConversationMessage> = synchronized(lock) { messages.toList() }

    /** 某一段会话的消息。[conversationId] 为空串时返回全部。 */
    fun conversation(conversationId: String): List<ConversationMessage> = synchronized(lock) {
        if (conversationId.isBlank()) messages.toList()
        else messages.filter { it.conversationId == conversationId }
    }

    /** 最近 [count] 条,按发生时间升序(即最后 count 条,顺序不倒置)。 */
    fun recent(count: Int): List<ConversationMessage> = synchronized(lock) {
        if (count <= 0) emptyList() else messages.takeLast(count)
    }

    /** 清空。用户主动清除历史时用。 */
    fun clear() = synchronized(lock) {
        messages.clear()
        seenIds.clear()
        store.write(emptyList())
    }

    companion object {
        /**
         * 默认上限。
         *
         * 200 条不是随手取的:手表屏幕一次显示 3~4 条,200 条够往回翻很久;
         * 而按每条平均 100 字算,总量在几十 KB 量级,对手表存储无压力。
         */
        const val DEFAULT_MAX_MESSAGES = 200
    }
}
