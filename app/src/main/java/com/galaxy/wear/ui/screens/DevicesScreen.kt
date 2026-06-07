package com.galaxy.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.domain.model.Device
import kotlinx.coroutines.launch

/**
 * DevicesScreen — Connected Galaxy devices status panel
 *
 * PR-ARCH-UNIFICATION: Replaced AgentsScreen (which showed fragmented agents)
 * with a unified device status panel. The watch communicates with OpenClawd
 * as a whole — not with individual agents. This screen shows which devices
 * are connected to the Galaxy mesh, nothing more.
 *
 * Architecture note: Device status comes from the gateway via AIP messages.
 * No direct agent targeting — all commands go through OpenClawd.
 */
@Composable
fun DevicesScreen(
    isAmbient: Boolean = false,
    onBack: () -> Unit
) {
    // LOW-FIX: Device list now comes from Application-level StateFlow instead
    // of hard-coded values. The list is populated from gateway state-sync messages.
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as GalaxyWearApplication
    val devices by app.devices.collectAsState()

    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
        ) {
            item {
                Text(
                    text = "设备",
                    style = MaterialTheme.typography.title1,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }

            item {
                Text(
                    text = "Galaxy Mesh 状态",
                    style = MaterialTheme.typography.caption3,
                    color = Color(0xFF666666),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(devices.size) { index ->
                val device = devices[index]
                DeviceStatusChip(
                    device = device,
                    isAmbient = isAmbient,
                    onClick = {
                        // PR-ARCH-UNIFICATION: Send a status query through
                        // the unified OpenClawd path — never target agents directly.
                        coroutineScope.launch {
                            if (app.isAipClientReady()) {
                                app.aipClient.sendCommand(
                                    "query_device_status",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("device_name", device.name)
                                        put("device_type", device.type)
                                    }
                                )
                            }
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "所有命令统一经 OpenClawd 路由",
                    style = MaterialTheme.typography.caption3,
                    color = Color(0xFF444444),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("返回", style = MaterialTheme.typography.button)
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusChip(
    device: Device,
    isAmbient: Boolean = false,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                device.name,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        secondaryLabel = {
            val statusText = when {
                device.isLocal -> "本机"
                device.online -> "在线"
                else -> "离线"
            }
            val statusColor = when {
                device.isLocal -> Color(0xFF4CAF50)  // Green for local
                device.online -> Color(0xFF808080)   // Gray for online remote
                else -> Color(0xFF444444)            // Dark for offline
            }
            Text(
                statusText,
                style = MaterialTheme.typography.caption3,
                color = statusColor
            )
        },
        icon = {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when {
                            device.isLocal -> Color(0xFF4CAF50)
                            device.online -> Color(0xFF2196F3)
                            else -> Color(0xFF333333)
                        },
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
        },
        colors = if (device.online || device.isLocal)
            ChipDefaults.primaryChipColors()
        else
            ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 2.dp)
    )
}
