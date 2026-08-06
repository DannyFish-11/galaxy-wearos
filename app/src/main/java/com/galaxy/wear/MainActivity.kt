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
import com.galaxy.wear.auth.PairClaimClient
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.screens.PairClaimScreen
import com.galaxy.wear.ui.screens.DevicesScreen
import com.galaxy.wear.ui.screens.HomeScreen
import com.galaxy.wear.ui.screens.SettingsScreen
import com.galaxy.wear.ui.screens.VoiceScreen
import com.galaxy.wear.ui.theme.GalaxyWearTheme
import com.ufo.galaxy.shared.protocol.DeviceIdProvider
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

    // ROUND-2-FIX: Android 13+ requires a runtime request for POST_NOTIFICATIONS;
    // declaring it in the manifest alone is not enough. Without the grant, the
    // HITL decision notifications (and the foreground-service status
    // notification) are silently suppressed by the system.
    //
    // 改成 RequestMultiplePermissions:可打扰性传感新增了 BODY_SENSORS,而
    // 单权限 launcher 连着 launch 两次时,第二次会在第一个对话框还挂着的时候
    // 被系统丢掉 —— 那样 BODY_SENSORS 永远问不出来。一次问完不存在这个竞态。
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            if (granted) return@forEach
            when (permission) {
                android.Manifest.permission.POST_NOTIFICATIONS ->
                    android.util.Log.w("MainActivity", "POST_NOTIFICATIONS denied — decision/status notifications will be hidden")
                android.Manifest.permission.BODY_SENSORS ->
                    // 如实标注:拒绝不会让功能瘫掉,只是心率那一路退出加权、
                    // 可打扰性判断的 confidence 变低。
                    android.util.Log.w("MainActivity", "BODY_SENSORS denied — 可打扰性判断将只用运动/亮屏证据,置信度降低")
                else ->
                    android.util.Log.w("MainActivity", "权限被拒: $permission")
            }
        }
    }

    /** 首次启动时一次性把缺的运行时权限问全。 */
    private fun requestMissingPermissions() {
        val wanted = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
            add(android.Manifest.permission.BODY_SENSORS)
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // ROUND-2-FIX + 可打扰性传感:首次启动一次性请求通知与心率权限。
        requestMissingPermissions()

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
                // 单实例 PairClaimClient，供 "auth" 路由的短码接入使用。
                val pairClaimClient = remember { PairClaimClient(app) }
                // FIX: close the manager's Ktor HttpClient when the Activity
                // composition is destroyed — previously it leaked for the
                // process lifetime once the auth screen was opened.
                DisposableEffect(pairClaimClient) {
                    onDispose { pairClaimClient.dispose() }
                }
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
                        // 设备接入界面。三仓统一到 /api/v1/pair/claim 之后，这里不再是
                        // OAuth device flow —— 桌面出示短码，手表输进去，一次 HTTP 换回
                        // 属于本表的令牌。不再需要"另一块屏幕"才能完成接入。
                        composable("auth") {
                            val serverUrl = app.encryptedPrefs.getString("server_url", "") ?: ""
                            PairClaimScreen(
                                pairClaimClient = pairClaimClient,
                                serverUrl = serverUrl,
                                // 必须是连 WS 时自报的那个 id —— 令牌签给它，
                                // 设备入口再拿令牌 subject 与它对一次。不一致就是
                                // "已配对却连不上"。
                                deviceId = DeviceIdProvider.getOrCreateDeviceId(app),
                                deviceName = android.os.Build.MODEL,
                                onPaired = {
                                    // 令牌已由 PairClaimClient 落进 galaxy_auth；
                                    // 经 loginWithToken 桥到 connect 路径读取的那份配置并连上。
                                    pairClaimClient.storedToken()?.let {
                                        app.loginWithToken(serverUrl, it)
                                    }
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                },
                                onCancelled = { navController.popBackStack() }
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
