package com.galaxy.wear.ui.screens

import android.util.Log
import androidx.compose.foundation.background
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
import com.galaxy.wear.data.AIPMessage
import com.galaxy.wear.data.DeviceInfo
import com.galaxy.wear.data.DeviceListResult
import com.galaxy.wear.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    // Always include local watch
    val localDevice = DeviceInfo(
        deviceId = app.aipClient.deviceId,
        deviceName = "本机手表",
        deviceType = "wear_os",
        status = "online",
        isLocal = true
    )

    // Fetch device list on screen open
    LaunchedEffect(Unit) {
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
            when (msg) {
                is AIPMessage.Result -> {
                    val payload = msg.payload
                    if (payload.containsKey("devices")) {
                        @Suppress("UNCHECKED_CAST")
                        val resultMap = payload as Map<String, Any>
                        val parsed = app.aipClient.parseDeviceList(resultMap)
                        val merged = mutableListOf<DeviceInfo>()

                        // Add local watch first if not in gateway response
                        val hasLocal = parsed.any { it.isLocal || it.deviceId == app.aipClient.deviceId }
                        if (!hasLocal) {
                            merged.add(localDevice)
                        }
                        merged.addAll(parsed)

                        deviceList = merged
                        isLoading = false
                        errorMessage = null
                    }
                }
                is AIPMessage.Event -> {
                    if (msg.eventType == "device_disconnected") {
                        val did = msg.payload["device_id"] as? String
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
            if (errorMessage != null && !isLoading) {
                item {
                    Text(
                        text = errorMessage!!,
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

/**
 * Single device chip — glass-morphism card style
 */
@Composable
private fun DeviceItemChip(device: DeviceInfo, onClick: () -> Unit) {
    val statusColor = Color(device.statusColor)

    Chip(
        onClick = onClick,
        label = {
            Text(
                device.deviceName,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                device.displayStatus,
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
                if (device.isLocal || device.displayStatus == "在线") {
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
        colors = if (device.isLocal || device.displayStatus == "在线")
            ChipDefaults.primaryChipColors()
        else
            ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .padding(vertical = 2.dp)
    )
}
