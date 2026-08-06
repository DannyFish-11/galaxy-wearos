package com.galaxy.wear.domain.model

/**
 * 谁有资格改写三态 —— 把这条规则从注释变成代码。
 *
 * 起因
 * ====
 * [Phase] 原来有两个写入方：本机连接态（CONNECTED→LIMINAL、AUTHENTICATED→MANIFEST）
 * 和桌面下发的相位。谁后写谁赢，于是鉴权一成功手表就常驻「显现」，而桌面那边其实
 * 什么也没发生。用户看到一台正在忙的机器，实际它在发呆。
 *
 * 根子上是两件事被塞进了同一个字段：
 * - **三态**说的是"那台电脑上的主体在干什么" —— 远端的属性；
 * - **连接态**说的是"我这块表连上没有" —— 本机链路的属性。
 *
 * 手机端一直是分开的（PhaseStateMachine 由远端驱动，连接态另有 connected 布尔）。
 * 这里把手表归位到同一口径。
 *
 * 为什么做成一个对象而不是两行 when
 * ==================================
 * 写在 Application 里的话，这条规则就只是一段注释加几行散落的赋值 —— 下一个人往
 * 连接态分支里补一句 `_phase.value = MANIFEST` 不会触发任何东西。做成纯函数，
 * 规则就能被钉住：[PhaseAuthorityTest] 里那几条正是在守"连接态永远抬不高相位"。
 */
object PhaseAuthority {

    /**
     * 桌面下发的相位 —— **唯一**能产生 LIMINAL / MANIFEST 的入口。
     *
     * 认不出来的字符串一律 SILENT：新版桌面加了第四种状态时，旧表宁可显示"静默"
     * 也不该把它误判成"显现"。往低了猜是安全的，往高了猜会让人以为它在干活。
     */
    fun fromDesktop(wireValue: String): Phase = when (wireValue.trim().lowercase()) {
        "manifest" -> Phase.MANIFEST
        "liminal" -> Phase.LIMINAL
        else -> Phase.SILENT
    }

    /**
     * 链路状态变化时相位该变成什么。
     *
     * 链路**在**：原样保留。连接态对相位没有任何话语权 —— 它不知道那台电脑在干嘛。
     * 链路**断**：回落 SILENT。注意这不是"本机判定了相位"，而是**我们不再知道**了；
     * 而"不知道"绝不能继续渲染成「显现」—— 手里那个值是断线前的说法，
     * 拿它冒充现状就是在撒谎。
     */
    fun onLinkChange(current: Phase, linkUp: Boolean): Phase = if (linkUp) current else Phase.SILENT
}
