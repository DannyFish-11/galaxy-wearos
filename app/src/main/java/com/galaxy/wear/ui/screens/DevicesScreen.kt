package com.galaxy.wear.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.data.DeviceInfo
import com.galaxy.wear.data.DeviceListResult
import com.galaxy.wear.ui.theme.*
import com.ufo.galaxy.shared.protocol.MsgType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DevicesScreen — PR-DEVICE-LIST-QUERY: Dynamic device status from gateway
 *
 * No longer hardcoded. Queries V2 gateway for actual connected devices
 * via AIPClient.queryDeviceList(). Falls back to showing local watch
 * + instructions if gateway is unreachable.
 */
@Composable
fun DevicesScreen(
    isAmbient: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyWearApplication
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    // Device list state
    var deviceList by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // FIX: aipClient is lateinit and can be uninitialized when Application
    // init failed — never touch it without isAipClientReady() or this screen
    // crashes with UninitializedPropertyAccessException during composition.
    val clientReady = app.isAipClientReady()

    // Always include local watch
    val localDevice = DeviceInfo(
        deviceId = if (clientReady) app.aipClient.getDeviceId() else "",
        displayName = "本机手表",
        deviceType = "wear_os",
        status = "online"
    )

    // Fetch device list on screen open
    LaunchedEffect(Unit) {
        if (!app.isAipClientReady()) {
            // AIPClient unavailable — degrade to local-only view instead of crashing.
            isLoading = false
            deviceList = listOf(localDevice)
            errorMessage = "服务未就绪,仅显示本机"
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null

        // Send query to gateway
        val result = app.aipClient.queryDeviceList()
        if (result is DeviceListResult.Error) {
            errorMessage = result.message
            // Show local device only
            deviceList = listOf(localDevice)
        }

        // Collect response from message flow
        app.aipClient.messages.collectLatest { msg ->
            when (msg.type) {
                MsgType.COMMAND_RESULT -> {
                    val payload = msg.payloadObject
                    // query_devices 的响应把设备数组放在 data.devices(command_result 的 payload
                    // 只暴露 id/success/data),而不是顶层 devices;两处都兜一下,真实响应才接得住,
                    // 否则永远走 5s 超时兜底只显示本机。
                    val dataObj = payload["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    val devicesField = payload["devices"] ?: dataObj?.get("devices")
                    if (devicesField != null) {
                        val parsed = app.aipClient.parseDeviceList(devicesField)
                        val merged = mutableListOf<DeviceInfo>()

                        // Add local watch first if not in gateway response
                        val hasLocal = parsed.any { it.deviceId == localDevice.deviceId }
                        if (!hasLocal) {
                            merged.add(localDevice)
                        }
                        merged.addAll(parsed)

                        deviceList = merged
                        isLoading = false
                        errorMessage = null
                    }
                }
                MsgType.EVENT -> {
                    val payload = msg.payloadObject
                    // FIX: AIPClient normalizes event messages to {event, data}
                    // (see AIPClient.handleMessage "event" branch) — read those
                    // keys; the previous "event_type"/"device_id" keys never
                    // existed, so this branch was dead code.
                    val eventType = payload["event"]?.jsonPrimitive?.content
                    if (eventType == "device_disconnected") {
                        val did = payload["data"]
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?.get("device_id")?.jsonPrimitive?.content
                        if (did != null) {
                            deviceList = deviceList.map {
                                if (it.deviceId == did) it.copy(status = "offline") else it
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // Timeout fallback
    LaunchedEffect(Unit) {
        delay(5000)
        if (isLoading) {
            isLoading = false
            if (deviceList.isEmpty()) {
                deviceList = listOf(localDevice)
                errorMessage = "网关未响应，显示本机"
            }
        }
    }

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
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
            // Title
            item {
                Text(
                    text = "设备",
                    style = MaterialTheme.typography.title2,
                    color = WhitePrimary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }

            // Subtitle
            item {
                Text(
                    text = "Galaxy Mesh",
                    style = MaterialTheme.typography.caption3,
                    color = WhiteSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            // Loading
            if (isLoading) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(vertical = 20.dp),
                        indicatorColor = GrayLiminal,
                        strokeWidth = 2.dp
                    )
                }
                item {
                    Text(
                        text = "同步中...",
                        style = MaterialTheme.typography.caption3,
                        color = WhiteSecondary.copy(alpha = 0.4f)
                    )
                }
            }

            // Error
            val err = errorMessage
            if (err != null && !isLoading) {
                item {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.caption3,
                        color = ErrorRed.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Device list
            items(deviceList.size) { index ->
                DeviceItemChip(
                    device = deviceList[index],
                    isLocal = deviceList[index].deviceId == localDevice.deviceId,
                    onClick = {
                        scope.launch {
                            if (app.isAipClientReady()) {
                                app.aipClient.sendCommand(
                                    "query_device_status",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("device_id", deviceList[index].deviceId)
                                    }
                                )
                            }
                        }
                    }
                )
            }

            // Spacer + note
            if (deviceList.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "所有命令统一经 OpenClawd 路由",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFF333333),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            // Back button
            item {
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                ) {
                    Text("返回", style = MaterialTheme.typography.button)
                }
            }
        }
    }
}

// device.status 是网关/本机自行约定的字符串("online"/"offline"/"connected"/...),
// DeviceInfo 本身没有 displayStatus/statusColor/isLocal 这几个计算属性(它是纯数据
// 载体,跟 AIPClient 共用),按本屏幕自己的展示需要在这里本地算,不往共享模型上加。
private fun deviceIsOnline(device: DeviceInfo): Boolean =
    device.status == "online" || device.status == "connected"

private fun deviceStatusLabel(device: DeviceInfo): String =
    if (deviceIsOnline(device)) "在线" else "离线"

/**
 * Single device chip — glass-morphism card style
 */
@Composable
private fun DeviceItemChip(device: DeviceInfo, isLocal: Boolean, onClick: () -> Unit) {
    val online = deviceIsOnline(device)
    val statusColor = if (online) SuccessGreen else WhiteSecondary.copy(alpha = 0.5f)

    Chip(
        onClick = onClick,
        label = {
            Text(
                device.displayName,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                deviceStatusLabel(device),
                style = MaterialTheme.typography.caption3,
                color = statusColor
            )
        },
        icon = {
            // Status dot with glow
            Box(
                modifier = Modifier.size(8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Glow behind
                if (isLocal || online) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(statusColor.copy(alpha = 0.2f), CircleShape)
                    )
                }
                // Dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
            }
        },
        colors = if (isLocal || online)
            ChipDefaults.primaryChipColors()
        else
            ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .padding(vertical = 2.dp)
    )
}
