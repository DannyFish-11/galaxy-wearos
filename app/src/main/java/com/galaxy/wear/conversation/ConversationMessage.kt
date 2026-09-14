package com.galaxy.wear.conversation

/**
 * 会话里的一条消息。
 *
 * @param id            这条消息的唯一标识。断线重连后服务端可能补发,靠它去重 ——
 *                      靠"内容+时间"判重会误杀真实重复(用户确实可能连说两句一样的)。
 * @param conversationId 所属会话。跨设备看到同一串上下文的依据:多设备各自的钟不一致,
 *                      靠时间戳拼不出来,而同一时刻还可能有两段对话在进行。
 *                      本地发起、服务端还没给出会话号时为空串。
 * @param role          谁说的。
 * @param text          正文。
 * @param timestampMs   发生时间(不是到达时间)。补传的消息按它归位。
 */
data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val text: String,
    val timestampMs: Long,
) {
    /** 发话方。 */
    enum class Role {
        /** 用户说的(手表上的语音输入、通知里的快捷回复)。 */
        USER,

        /** 智能体说的 —— 可能是回答,也可能是它主动发来的一条消息。 */
        ASSISTANT,

        /** 系统提示(连接状态、错误),与对话正文区分开,界面可以弱化显示。 */
        SYSTEM,
    }
}
