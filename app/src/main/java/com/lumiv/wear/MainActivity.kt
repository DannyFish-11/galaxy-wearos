package com.lumiv.wear

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
import com.lumiv.wear.auth.DeviceFlowManager
import com.lumiv.wear.domain.model.Phase
import com.lumiv.wear.ui.screens.DevicesScreen
import com.lumiv.wear.ui.screens.DecisionScreen
import com.lumiv.wear.ui.screens.DeviceAuthScreen
import com.lumiv.wear.ui.screens.HomeScreen
import com.lumiv.wear.ui.screens.QrCodeFullScreen
import com.lumiv.wear.ui.screens.SettingsScreen
import com.lumiv.wear.ui.screens.VoiceScreen
import com.lumiv.wear.ui.theme.LumivWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Main Wear OS Activity — Lumiv Watch Entry Point
 *
 * W3-FIX: Added lifecycle-aware coroutine scope to prevent Activity leaks.
 * W4-FIX: Added AmbientLifecycleObserver for Always-on display support.
 *
 * Navigation:
 *   home -> agents -> voice -> settings
 *   device_auth -> qr_code (OAuth 2.0 Device Flow)
 * All screens follow Wear OS circular design guidelines.
 */
class MainActivity : ComponentActivity() {

    // W3-FIX: Activity-bound coroutine scope, cancelled in onDestroy()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // W4-FIX: Ambient mode state — controls low-power UI
    val isAmbient = mutableStateOf(false)

    // DEVICE-FLOW: Lazy-created DeviceFlowManager for OAuth 2.0 Device Authorization Grant
    private val deviceFlowManager by lazy { DeviceFlowManager(this) }

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
            LumivWearTheme {
                val navController = rememberSwipeDismissableNavController()
                val app = application as LumivWearApplication
                val phase by app.phase.collectAsState()
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
                                onAgents = { navController.navigate("agents") },
                                onVoice = { navController.navigate("voice") },
                                onSettings = { navController.navigate("settings") },
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
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("decision") {
                            DecisionScreen(
                                title = "需要确认",
                                summary = "请选择一个选项",
                                options = emptyList(),
                                onOptionSelected = { navController.popBackStack() },
                                onVoiceReply = { navController.popBackStack() },
                                onDismiss = { navController.popBackStack() }
                            )
                        }
                        // DEVICE-FLOW: OAuth 2.0 Device Authorization Grant screen
                        composable("device_auth") {
                            // Get server URL from BuildConfig or EncryptedSharedPreferences
                            val serverUrl = remember {
                                val savedUrl = app.encryptedPrefs.getString("server_url", "")
                                if (!savedUrl.isNullOrEmpty()) savedUrl else "https://lumiv.ufo.ai"
                            }
                            DeviceAuthScreen(
                                deviceFlowManager = deviceFlowManager,
                                serverUrl = serverUrl,
                                onAuthSuccess = {
                                    // Pop back to home on success
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                },
                                onAuthCancelled = {
                                    navController.popBackStack()
                                }
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
        // DEVICE-FLOW: Clean up DeviceFlowManager
        runCatching { deviceFlowManager.dispose() }
        lifecycle.removeObserver(lifecycleObserver)
        super.onDestroy()
    }
}
