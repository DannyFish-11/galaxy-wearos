package com.galaxy.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.conversation.ConversationDisplay
import com.galaxy.wear.conversation.ConversationMessage
import com.galaxy.wear.ui.theme.NebulaDim
import com.galaxy.wear.ui.theme.SpaceBlack
import com.galaxy.wear.ui.theme.SurfaceGlass
import com.galaxy.wear.ui.theme.WhitePrimary
import com.galaxy.wear.ui.theme.WhiteSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConversationScreen —— 手表上那"一套上下文"。
 *
 * 在此之前手表是一次性的:说一句、答一句,答完就没了。命令回执只有设备页读,
 * 智能体主动发来的消息只弹一条通知、划掉就再也找不回来。用户问"它刚才说什么来着",
 * 表上没有任何地方能回答。
 *
 * 这一屏就是回答那句话的地方:一条时间轴,自己说的和它说的都在上面。
 *
 * ## 为什么最新的在下面
 * 和所有聊天一样,新消息往下长、进来直接停在底部。倒序(最新在上)在手表上能省一次
 * 滚动,但代价是读一段对话得从下往上看 —— 那和人读对话的方向是反的。
 *
 * ## 排版规则不在这里
 * 哪条显示时间、哪条算"接着上一句说的",都在 [ConversationDisplay] 里算好。
 * 那类规则出错的样子很隐蔽(每条都显示时间 → 屏幕被时间戳塞满;一条都不显示 →
 * 分不清隔了一天还是隔了一秒),放在 Composable 里就只能靠肉眼在表上看。
 */
@Composable
fun ConversationScreen(
    isAmbient: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyWearApplication
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    // 消息是被动到达的(它主动说话、命令回执)。界面开着的时候来一条,得当场出现 ——
    // 只在进入界面那一刻读一次的话,这一屏是死的:消息收到了、存下了、通知也弹了,
    // 唯独列表不动,看起来和"没收到"一模一样。
    val messages by app.conversationRecorder.messages.collectAsState()
    val rows = remember(messages) { ConversationDisplay.rowsFor(messages) }

    // 新消息进来就滚到底 —— 否则界面开着时来的那条长在屏幕外面,还是看不见。
    //
    // 末尾下标问自己要,不要照着下面的 item 手数一遍(标题 1 + 每行 + 带时间戳的多
    // 一条 + 返回 1)。手数出来的数字是一份重复的知识:谁往列表里加一个 item 而忘了
    // 改它,滚到底就永远差一格,而这种差法在表上很难看出来是 bug。
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SpaceBlack)
                .onRotaryScrollEvent { event ->
                    scope.launch { listState.scrollBy(event.verticalScrollPixels) }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
        ) {
            item {
                Text(
                    text = "会话",
                    style = MaterialTheme.typography.title3,
                    color = WhitePrimary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
            }

            if (rows.isEmpty()) {
                item {
                    // 空态要说清"接下来会发生什么",而不是只说一句"暂无记录" ——
                    // 后者读起来像功能坏了。
                    Text(
                        text = "还没有对话。\n说一句话,或等它来找你,\n这里就会留下记录。",
                        style = MaterialTheme.typography.caption2,
                        color = WhiteSecondary.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }

            rows.forEach { row ->
                if (row.showTimestamp) {
                    item(key = "ts_${row.message.id}") {
                        Text(
                            text = formatTimestamp(row.message.timestampMs),
                            style = MaterialTheme.typography.caption3,
                            color = WhiteSecondary.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                item(key = row.message.id) {
                    MessageRow(row = row, isAmbient = isAmbient)
                }
            }

            item {
                CompactChip(
                    onClick = onBack,
                    label = { Text("返回", style = MaterialTheme.typography.caption2) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .padding(top = 10.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageRow(row: ConversationDisplay.Row, isAmbient: Boolean) {
    val message = row.message
    val isUser = message.role == ConversationMessage.Role.USER
    val isSystem = message.role == ConversationMessage.Role.SYSTEM

    // 熄屏(ambient)下不画底色:常亮态要尽量少点亮像素,而且 OLED 上大片低亮度色块
    // 正是最费电、也最容易烧屏的画法。
    val background = when {
        isAmbient -> SpaceBlack
        isUser -> NebulaDim.copy(alpha = 0.35f)
        else -> SurfaceGlass
    }

    // 用 Column + background,不用 Card:Card 的点击态会让每一行看起来可以点开,
    // 而这里的行点开之后没有任何东西 —— 一个假的可点提示比没有更糟。
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // 连着说的第二句起就不再重复身份标记 —— 手表屏幕就这么大,
        // 每句都顶一行"我 / 星系"是把内容挤出去。
        if (!row.continuesPrevious) {
            Text(
                text = roleLabel(message.role),
                style = MaterialTheme.typography.caption3,
                fontWeight = FontWeight.Medium,
                color = if (isUser) WhitePrimary.copy(alpha = 0.7f)
                else WhiteSecondary.copy(alpha = 0.8f),
            )
        }
        Text(
            text = message.text,
            style = MaterialTheme.typography.body2,
            color = if (isSystem) WhiteSecondary.copy(alpha = 0.7f) else WhitePrimary,
        )
    }
}

private fun roleLabel(role: ConversationMessage.Role): String = when (role) {
    ConversationMessage.Role.USER -> "我"
    ConversationMessage.Role.ASSISTANT -> "星系"
    ConversationMessage.Role.SYSTEM -> "系统"
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
