package com.galaxy.wear.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 「现在大概率能走局域网吗」的快速判断。
 *
 * 这段逻辑原先长在 `MdnsDiscovery` 里，但它**从来不是发现逻辑**：它只问连通性，
 * 不发一个包、不解析一个服务。`MdnsDiscovery` 整体收敛到
 * `com.ufo.galaxy.network.GatewayDiscovery`（安卓仓 `:shared-transport`，手机手表共用
 * 同一份）之后，这一小块留在手表侧 —— 它是本端在决定「要不要花 2 秒去发现」时的
 * 前置门，与发现本身无关，搬进共享模块只会让那个类多背一个不属于它的职责。
 *
 * 判断保持原样，一行未改：没有 `ACCESS_NETWORK_STATE` 权限时直接返回 false（查不了
 * 就不假装能查），并且要求 `NET_CAPABILITY_VALIDATED` —— 连上了但没通的 Wi-Fi
 * （门户认证页、断网的路由器）不算可用，否则会白等一个发现窗口。
 */
@Suppress("DEPRECATION")
fun isWifiAvailable(context: Context): Boolean {
    val hasPermission = context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return false

    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            val networkInfo = cm.activeNetworkInfo
            networkInfo != null &&
                networkInfo.type == ConnectivityManager.TYPE_WIFI &&
                networkInfo.isConnected
        }
    } catch (e: Exception) {
        false
    }
}
