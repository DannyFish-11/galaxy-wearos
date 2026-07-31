package com.galaxy.wear.domain.model

/**
 * 决策选项 —— 一个可点选的答案。
 *
 * 从 `ui/screens/DecisionScreen.kt` 挪到 domain 层,理由与 [Phase] 当初那次
 * 一致(见 Phase.kt 的 LOW-FIX):它是**领域概念**,不是某个界面的私有类型。
 *
 * 原来的位置造成了一处层次倒挂:`domain/DecisionRequestParsing.kt`(纯解析)
 * 必须 `import com.galaxy.wear.ui.screens.DecisionOption` —— 领域层反过来
 * 依赖一个 Compose 界面文件。除了架构上说不通,还有个实际后果:这段纯逻辑
 * 没法脱离 Android/Compose 单独编译,想给它写纯 JVM 测试就得把整个界面拖进来。
 *
 * @param id            回传给 V2 的**协议 id**(如 `approve`/`deny`)。
 * @param label         给人看的**显示文字**(如「批准」)。两者不可混用 ——
 *                      通知按钮曾经把 id 直接当文字印在手腕上。
 * @param iconRes       图标资源 id;``0`` 表示「没指定,由界面自己挑」。
 *                      刻意**不**默认成 `android.R.drawable.*` —— 领域模型引用
 *                      `android.R` 就不再是领域模型了,纯 JVM 环境下连编译都过不去,
 *                      这段纯解析逻辑也就写不了单测。默认值留给界面层决定。
 * @param isDestructive 是否为破坏性选项,供界面做视觉区分。
 */
data class DecisionOption(
    val id: String,
    val label: String,
    val iconRes: Int = 0,
    val isDestructive: Boolean = false,
)
