package com.galaxy.wear.ui

/**
 * HapticType — 触觉反馈类型
 */
enum class HapticType {
    PHASE_CHANGE,    // 三态切换
    TASK_DONE,       // 任务完成
    MESSAGE_ARRIVAL, // 消息到达
    DECISION_PROMPT, // 决策提示
    ERROR,           // 错误
}
