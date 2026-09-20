package com.galaxy.wear.conversation

/**
 * 把线上的消息翻译成会话记录。
 *
 * ## 为什么需要「配对」而不是「看内容像不像」
 * 手表问出去的语音查询，回复是以 `command_result` 回来的 —— 而 `command_result`
 * 同时也是**设备命令**的结果（开灯、锁屏之类）。两者在线上是同一个类型。
 *
 * 靠"payload 里有没有 text 字段"来分辨是不合格的判据：某个设备命令的结果里恰好
 * 带一个 text 字段，就会被当成智能体说的话记进对话里 —— 而这种误记不会报错，
 * 只会让会话里凭空多出一句没人说过的话。
 *
 * 所以这里走**相关 id 配对**：发出去时记下"这个 correlation_id 是一次语音提问"，
 * 回来时只认领这些 id。没配上的 `command_result` 一律不是对话内容。
 *
 * ## 待配对表为什么有上限
 * 发出去的查询未必都有回复（断网、服务端异常）。不设上限，这张表只增不减，
 * 是一条必然发生的泄漏。超出时丢最旧的：最旧的那条等回复也等不到了。
 *
 * 纯 Kotlin，不依赖 Android 运行时 —— 这层全是判定逻辑，应当能直接单测。
 */
class ConversationRecorder(
    private val store: ConversationStore,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxPendingQueries: Int = DEFAULT_MAX_PENDING,
) {

    private val lock = Any()

    /** correlation_id → 这次提问的会话号。用 LinkedHashMap 保持插入序，便于丢最旧。 */
    private val pendingQueries = LinkedHashMap<String, String>()

    /**
     * 记一条用户说的话（语音输入、或通知上的快捷回复）。
     *
     * @param correlationId 这次请求的相关 id；回复回来时靠它认领。空串表示不等回复。
     */
    fun recordUserQuery(
        text: String,
        correlationId: String = "",
        conversationId: String = "",
    ): ConversationMessage? {
        if (text.isBlank()) return null
        val message = ConversationMessage(
            id = idFactory(),
            conversationId = conversationId,
            role = ConversationMessage.Role.USER,
            text = text,
            timestampMs = clock(),
        )
        if (correlationId.isNotBlank()) {
            synchronized(lock) {
                pendingQueries[correlationId] = conversationId
                while (pendingQueries.size > maxPendingQueries) {
                    pendingQueries.remove(pendingQueries.keys.first())
                }
            }
        }
        return if (store.append(message)) message else null
    }

    /**
     * 一条 `command_result` 回来了。
     *
     * 只有能和先前的提问配上对的才算对话内容；配不上的是设备命令结果，返回 null。
     *
     * @param replyText 回复正文（服务端放在 `payload.data.text` / `.response`）。
     */
    fun recordCommandResult(correlationId: String, replyText: String): ConversationMessage? {
        if (correlationId.isBlank()) return null
        val conversationId = synchronized(lock) {
            if (!pendingQueries.containsKey(correlationId)) return null
            pendingQueries.remove(correlationId)
        } ?: ""
        if (replyText.isBlank()) return null
        val message = ConversationMessage(
            id = idFactory(),
            conversationId = conversationId,
            role = ConversationMessage.Role.ASSISTANT,
            text = replyText,
            timestampMs = clock(),
        )
        return if (store.append(message)) message else null
    }

    /**
     * 智能体主动发来的一条消息（AIP `agent_message`）。
     *
     * 与上面两条不同：它不配对任何提问 —— 那正是这条协议类型存在的理由。
     *
     * @param messageId 服务端给的消息 id；用它去重，断线补发才不会记成两条。
     *                  为空时退回本地生成（那样补发会重复，但总比丢掉好）。
     */
    fun recordAgentMessage(
        text: String,
        conversationId: String = "",
        messageId: String = "",
        timestampMs: Long = clock(),
    ): ConversationMessage? {
        if (text.isBlank()) return null
        val message = ConversationMessage(
            id = messageId.ifBlank { idFactory() },
            conversationId = conversationId,
            role = ConversationMessage.Role.ASSISTANT,
            text = text,
            timestampMs = timestampMs,
        )
        return if (store.append(message)) message else null
    }

    /**
     * 读会话历史。[conversationId] 为空串时返回全部,按发生时间升序。
     *
     * 界面从这里读,而不是自己再持一份 [ConversationStore] —— 两份实例会各存各的,
     * 于是通知里回的那句话在会话列表里看不到。
     */
    /** 可观察的全量会话。界面直接 collect 它,不用自己轮询。 */
    val messages: kotlinx.coroutines.flow.StateFlow<List<ConversationMessage>>
        get() = store.snapshot

    fun history(conversationId: String = ""): List<ConversationMessage> =
        store.conversation(conversationId)

    /** 清空会话历史。用户主动清除时用。 */
    fun clearHistory() = store.clear()

    /** 当前等待回复的提问数。给测试与诊断读。 */
    internal fun pendingCount(): Int = synchronized(lock) { pendingQueries.size }

    companion object {
        /**
         * 待配对表上限。发出去的查询未必都有回复（断网、服务端异常），
         * 不设上限就是一条必然发生的泄漏。
         */
        const val DEFAULT_MAX_PENDING = 32
    }
}
