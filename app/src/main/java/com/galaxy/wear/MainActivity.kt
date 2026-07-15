package com.galaxy.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleObserver
import androidx.navigation.NavHostController
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.galaxy.wear.auth.DeviceFlowManager
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.screens.DeviceAuthScreen
import com.galaxy.wear.ui.screens.DevicesScreen
import com.galaxy.wear.ui.screens.HomeScreen
import com.galaxy.wear.ui.screens.SettingsScreen
import com.galaxy.wear.ui.screens.VoiceScreen
import com.galaxy.wear.ui.theme.GalaxyWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * Main Wear OS Activity — Galaxy Watch Entry Point
 *
 * W3-FIX: Added lifecycle-aware coroutine scope to prevent Activity leaks.
 * W4-FIX: Added AmbientLifecycleObserver for Always-on display support.
 *
 * Navigation:
 *   home → agents → voice → settings
 * All screens follow Wear OS circular design guidelines.
 */
class MainActivity : ComponentActivity() {

    // W3-FIX: Activity-bound coroutine scope, cancelled in onDestroy()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // W4-FIX: Ambient mode state — controls low-power UI
    val isAmbient = mutableStateOf(false)

    // W3-FIX: LifecycleObserver to auto-cancel UI coroutines on destroy
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            activityScope.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // W3-FIX: Register lifecycle observer for leak prevention
        lifecycle.addObserver(lifecycleObserver)

        // W4-FIX: Register AmbientLifecycleObserver for Always-on display
        // Using Wear OS androidx.wear:wear AmbientLifecycleObserver (type-safe, no reflection)
        val ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    isAmbient.value = true
                }
                override fun onExitAmbient() {
                    isAmbient.value = false
                }
                override fun onUpdateAmbient() {
                    // Low-power periodic update (every 60s in ambient)
                }
            }
        )
        lifecycle.addObserver(ambientObserver)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            GalaxyWearTheme {
                val navController = rememberSwipeDismissableNavController()
                val app = application as GalaxyWearApplication
                // STAGE-2b: 单实例 DeviceFlowManager,供 "auth" 路由的设备流登录使用。
                val deviceFlowManager = remember { DeviceFlowManager(app) }
                val phase by app.phase.collectAsState()
                val islandItems by app.islandItems.collectAsState()
                // W4-FIX: Read ambient state to control animations
                val ambient by isAmbient

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    SwipeDismissableNavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("home") {
                            HomeScreen(
                                phase = phase,
                                isAmbient = ambient,
                                onDevices = { navController.navigate("agents") },
                                onVoice = { navController.navigate("voice") },
                                onSettings = { navController.navigate("settings") },
                                islandItems = islandItems,
                            )
                        }
                        composable("agents") {
                            DevicesScreen(
                                isAmbient = ambient,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("voice") {
                            VoiceScreen(
                                isAmbient = ambient,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                isAmbient = ambient,
                                onBack = { navController.popBackStack() },
                                onLogin = { navController.navigate("auth") }
                            )
                        }
                        // STAGE-2b: 设备流(RFC 8628)登录界面 —— 此前 DeviceAuthScreen 写好却
                        // 没有任何导航入口(孤儿)。这里接进导航;成功后把令牌桥接进 connect 路径。
                        composable("auth") {
                            val serverUrl = app.encryptedPrefs.getString("server_url", "") ?: ""
                            DeviceAuthScreen(
                                deviceFlowManager = deviceFlowManager,
                                serverUrl = serverUrl,
                                onAuthSuccess = {
                                    // 设备流令牌已存入 galaxy_auth;经 loginWithToken 落到
                                    // connect 路径读取的 galaxy_config 并立即连接。
                                    deviceFlowManager.getToken()?.let {
                                        app.loginWithToken(serverUrl, it.accessToken)
                                    }
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                },
                                onAuthCancelled = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // W3-FIX: Cancel all UI coroutines to prevent Activity leaks
        if (activityScope.isActive) {
            activityScope.cancel()
        }
        lifecycle.removeObserver(lifecycleObserver)
        super.onDestroy()
    }
}
