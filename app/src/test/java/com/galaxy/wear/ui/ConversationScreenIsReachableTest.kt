package com.galaxy.wear.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话界面:**写了,得走得到**。
 *
 * 这条守的不是逻辑,是接线 —— 和 `CandidatePathsAreActuallyUsedTest` 同一类。
 *
 * 存储、记录、通知都做完之后,最容易剩下的缺陷是最蠢的那个:界面写好了,但
 * 首页没有入口,或者 chip 导航到的路由名和 NavHost 注册的那个差一个字。两种都
 * 照样编译通过、照样一片绿,只是用户点下去什么都不会发生 —— 而"手表上有一套
 * 上下文"这件事,对用户来说就等于**没做**。
 *
 * 编译器管不到这层:路由是字符串。所以钉在这里。
 */
class ConversationScreenIsReachableTest {

    private val route = "conversation"

    private fun source(relative: String): String {
        val f = File(locateMainSourceRoot(), relative)
        assertTrue("源文件不存在:${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `NavHost 注册了会话路由,并且真的落到 ConversationScreen 上`() {
        val src = source("com/galaxy/wear/MainActivity.kt")
        assertTrue(
            "NavHost 没有注册 \"$route\" 路由 —— 导航过去会抛 IllegalArgumentException",
            src.contains("composable(\"$route\")"),
        )
        assertTrue(
            "注册了路由却没调 ConversationScreen —— 点进去是一片空白",
            src.contains("ConversationScreen("),
        )
        assertTrue(
            "ConversationScreen 没被 import —— 上一条断言会被同名的别的东西蒙混过去",
            src.contains("import com.galaxy.wear.ui.screens.ConversationScreen"),
        )
    }

    @Test
    fun `首页把会话入口接到了那个路由上`() {
        val src = source("com/galaxy/wear/MainActivity.kt")
        assertTrue(
            "HomeScreen 的 onConversation 没有导航到 \"$route\" —— 首页那个按钮点了没反应",
            src.contains("onConversation = { navController.navigate(\"$route\") }"),
        )
    }

    @Test
    fun `首页真的有一个会话入口,而不只是多了个参数`() {
        val src = source("com/galaxy/wear/ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen 没收 onConversation 参数",
            src.contains("onConversation: () -> Unit"),
        )
        assertTrue(
            "HomeScreen 收了 onConversation 却没有任何地方调它 —— 等于没有入口",
            src.contains("onConversation()"),
        )
    }

    @Test
    fun `会话界面读的是可观察的快照,不是进来那一刻读一次`() {
        val src = source("com/galaxy/wear/ui/screens/ConversationScreen.kt")
        assertTrue(
            "界面没有 collect 会话快照 —— 开着界面时来的消息不会出现,看起来像没收到",
            src.contains("conversationRecorder.messages.collectAsState()"),
        )
        // history() 是一次性快照,给非界面的调用方用的。界面用它就是那个"死列表"缺陷。
        assertTrue(
            "界面用了一次性的 history() 而不是可观察的快照",
            !src.contains("conversationRecorder.history("),
        )
    }

    @Test
    fun `排版规则来自 ConversationDisplay,没有在界面里重写一遍`() {
        // 规则写两份必然会漂,而且界面里那份没法测 —— 只能靠肉眼在表上看。
        val src = source("com/galaxy/wear/ui/screens/ConversationScreen.kt")
        assertTrue(
            "界面没有走 ConversationDisplay.rowsFor —— 那套排版判定被绕开了",
            src.contains("ConversationDisplay.rowsFor("),
        )
        assertTrue(
            "界面里自己算了一遍时间间隔 —— 这是 ConversationDisplay 的事",
            !src.contains("TIMESTAMP_GAP_MS"),
        )
    }

    @Test
    fun `滚到底的下标不是手数出来的`() {
        // 手数的 item 数是一份重复的知识:谁加一个 item 忘了改它,滚到底就永远差一格。
        val src = source("com/galaxy/wear/ui/screens/ConversationScreen.kt")
        assertTrue(
            "滚到底没有问列表自己要末尾下标",
            src.contains("layoutInfo.totalItemsCount"),
        )
    }

    private fun locateMainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过:${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }
}
