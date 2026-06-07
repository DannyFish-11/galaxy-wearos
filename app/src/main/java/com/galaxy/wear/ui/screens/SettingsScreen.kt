package com.galaxy.wear.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.wear.compose.material.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.data.AIPConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * SettingsScreen — Server config & connection status
 *
 * - Server URL input
 * - Token (obscured)
 * - Connect / Disconnect
 * - Connection state indicator
 *
 * W4-FIX: Added isAmbient parameter for low-power UI.
 * W5-FIX: Added onRotaryScrollEvent for crown-based scrolling.
 * W10-FIX: URL/Token persisted via EncryptedSharedPreferences.
 */
@Composable
fun SettingsScreen(
    isAmbient: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyWearApplication
    val scope = rememberCoroutineScope()

    val connectionState by app.connectionState.collectAsState()

    // SECURITY-FIX: Use EncryptedSharedPreferences to protect auth_token and server_url.
    // Replaces plaintext SharedPreferences with AES256-GCM encrypted storage.
    val prefs = remember {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "galaxy_config",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to plaintext only on devices that don't support encrypted prefs
            android.util.Log.w("SettingsScreen", "EncryptedSharedPreferences init failed, using plaintext fallback: ${e.message}")
            context.getSharedPreferences("galaxy_config", Context.MODE_PRIVATE)
        }
    }

    var serverUrl by remember {
        mutableStateOf(prefs.getString("server_url", "") ?: "")
    }
    var token by remember {
        mutableStateOf(prefs.getString("auth_token", "") ?: "")
    }
    var isConnecting by remember { mutableStateOf(false) }
    // CRITICAL-FIX: Track discovery job to debounce rapid clicks — prevents
    // launching multiple parallel discoverGateway coroutines.
    var discoverJob by remember { mutableStateOf<Job?>(null) }

    // W10-FIX: Save settings when changed
    fun saveSettings() {
        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("auth_token", token)
            .apply()
    }

    // Reset isConnecting when connection state changes from CONNECTING
    LaunchedEffect(connectionState) {
        if (connectionState != AIPConnectionState.CONNECTING) {
            isConnecting = false
        }
    }

    // W5-FIX: Shared list state for PositionIndicator and rotary scrolling
    val listState = rememberScalingLazyListState()

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            // W5-FIX: Handle rotary (crown) scroll events
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent { event ->
                    scope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
        ) {
            item {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.title1,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }

            // Connection status indicator
            item {
                val (statusColor, statusText) = when (connectionState) {
                    AIPConnectionState.AUTHENTICATED -> Color(0xFFE0E0E0) to "已连接"
                    AIPConnectionState.CONNECTED -> Color(0xFF808080) to "连接中"
                    AIPConnectionState.CONNECTING -> Color(0xFF666666) to "正在连接"
                    AIPConnectionState.ERROR -> Color(0xFFCF6679) to "错误"
                    AIPConnectionState.DISCONNECTED -> Color(0xFF444444) to "未连接"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.caption2,
                        color = statusColor
                    )
                }
            }

            // Server URL input (simulated with chip for watch)
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "服务器",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // On a real watch, use a dedicated input screen
                    // Here we show a chip that would open an input dialog
                    Chip(
                        onClick = { /* Open URL input */ },
                        label = {
                            Text(
                                text = serverUrl.ifEmpty { "点击设置服务器地址" },
                                style = MaterialTheme.typography.caption1,
                                maxLines = 1
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Token input
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "令牌",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Chip(
                        onClick = { /* Open token input */ },
                        label = {
                            Text(
                                text = if (token.isEmpty()) "点击设置 Token" else "••••••••",
                                style = MaterialTheme.typography.caption1
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            // Connect / Disconnect buttons
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (connectionState == AIPConnectionState.DISCONNECTED ||
                        connectionState == AIPConnectionState.ERROR
                    ) {
                        Button(
                            onClick = {
                                if (serverUrl.isNotEmpty() && token.isNotEmpty()) {
                                    isConnecting = true
                                    // W10-FIX: Save before connecting
                                    saveSettings()
                                    app.connect(serverUrl, token)
                                    // Note: isConnecting is cleared by connectionState observer
                                }
                            },
                            enabled = serverUrl.isNotEmpty() && token.isNotEmpty() && !isConnecting,
                            modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
                        ) {
                            Text("连接", style = MaterialTheme.typography.button)
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    app.disconnect()
                                }
                            },
                            colors = ButtonDefaults.primaryButtonColors(
                                backgroundColor = Color(0xFF444444)
                            ),
                            modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
                        ) {
                            Text("断开", style = MaterialTheme.typography.button)
                        }
                    }
                }
            }

            // Auto-discovery + Quick preset URLs
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "网络 (${app.getNetworkStatus()})",
                    style = MaterialTheme.typography.caption3,
                    color = Color(0xFF555555),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // ONE-TAP DISCOVERY: mDNS LAN → Tailscale
            item {
                var isDiscovering by remember { mutableStateOf(false) }
                CompactChip(
                    onClick = {
                        // CRITICAL-FIX: Cancel previous discovery job before starting a new one
                        discoverJob?.cancel()
                        isDiscovering = true
                        discoverJob = scope.launch {
                            try {
                                val found = app.discoverGateway()
                                if (found != null) {
                                    serverUrl = found
                                    // W10-FIX: Auto-save discovered URL
                                    saveSettings()
                                } else {
                                    // CRITICAL-FIX: Notify user when auto-discovery fails.
                                    // Previously returned null silently leaving user confused.
                                    Toast.makeText(context, "未发现网关", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                // FIX: Prevent isDiscovering from staying true forever
                                Toast.makeText(context, "发现失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isDiscovering = false
                            }
                        }
                    },
                    label = {
                        Text(
                            if (isDiscovering) "扫描中..." else "自动发现网关",
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    colors = ChipDefaults.primaryChipColors()
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactChip(
                        onClick = {
                            serverUrl = "wss://localhost:9000"
                            saveSettings()
                        },
                        label = { Text("本地", style = MaterialTheme.typography.caption3) }
                    )
                    CompactChip(
                        onClick = {
                            serverUrl = "wss://100.64.0.1:9000"
                            saveSettings()
                        },
                        label = { Text("Tailscale", style = MaterialTheme.typography.caption3) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                CompactChip(
                    onClick = onBack,
                    label = { Text("返回", style = MaterialTheme.typography.caption2) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
