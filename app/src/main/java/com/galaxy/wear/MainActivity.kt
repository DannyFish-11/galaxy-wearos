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
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.screens.DevicesScreen
import com.galaxy.wear.ui.screens.HomeScreen
import com.galaxy.wear.ui.screens.SettingsScreen
import com.galaxy.wear.ui.screens.VoiceScreen
import com.galaxy.wear.ui.theme.GalaxyWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
