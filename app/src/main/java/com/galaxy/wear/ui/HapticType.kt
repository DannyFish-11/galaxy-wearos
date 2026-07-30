package com.galaxy.wear.ui

/**
 * 触觉类别 —— 手表上"不用看就知道发生了什么"的词汇表。
 *
 * @param alerting 这一下是**不请自来**的(消息/决策/出错),还是伴随用户
 *   自己动作的确认反馈(点按/三态切换)?
 *
 *   这个区分不是修辞。Android 触觉设计原则要求「很频繁的事件要非常轻」,
 *   同时要求「同类交互必须同一种反馈,用户才建立得起联想」。两条合起来:
 *   - **alerting = true** 的类别必须**两两可区分** —— 用户在不看表的情况下
 *     要能靠手感分辨"是消息、是做完了、还是需要我拿主意";
 *   - **alerting = false** 的类别允许共用最轻的那一下 —— 它们本来就伴随着
 *     可见的动作,手感只负责"按到了"这件事,再去细分反而是噪音。
 *
 *   这条规则由 `HapticVocabularyTest` 强制执行。
 */
enum class HapticType(val alerting: Boolean) {
    /** 屏幕上的点按确认。全表最频繁的事件 → 必须最轻。 */
    UI_TAP(alerting = false),

    /** 三态切换(SILENT/LIMINAL/MANIFEST)。伴随可见的界面变化。 */
    PHASE_CHANGE(alerting = false),

    /** 消息到达。 */
    MESSAGE_ARRIVAL(alerting = true),

    /** 开始听你说话(多由侧键唤起,此时很可能没在看屏幕)。 */
    LISTENING_START(alerting = true),

    /** 任务完成。 */
    TASK_DONE(alerting = true),

    /** 需要你拿主意(HITL)。全表最重要的一类。 */
    DECISION_PROMPT(alerting = true),

    /** 出错了。 */
    ERROR(alerting = true),
}
