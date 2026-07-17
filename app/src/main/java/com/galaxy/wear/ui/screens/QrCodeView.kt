package com.galaxy.wear.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import androidx.compose.ui.graphics.toArgb

/**
 * 二维码显示组件
 *
 * 使用 ZXing 的 MultiFormatWriter 生成 BitMatrix，
 * 然后通过 Android Canvas 绘制为 Bitmap，在 Compose 中显示。
 *
 * 适用于 Wear OS 小屏幕，不依赖外部 Compose 二维码库。
 *
 * @param data 二维码内容（通常是验证 URI）
 * @param size 二维码显示尺寸（Dp）
 * @param darkColor 深色模块颜色（默认黑色）
 * @param lightColor 浅色模块颜色（默认白色）
 */
@Composable
fun QrCodeView(
    data: String,
    size: Dp = 180.dp,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }

    // 使用 remember 缓存二维码 Bitmap，避免每次重组都重新生成
    val bitmap = remember(data, sizePx) {
        generateQrBitmap(data, sizePx, darkColor, lightColor)
    }

    Box(
        modifier = Modifier
            .size(size)
            .background(lightColor)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "授权二维码",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 全屏二维码页面
 *
 * 在 Wear OS 圆形屏幕上最大化显示二维码，方便手机扫描。
 *
 * @param data 二维码内容
 * @param onBack 返回回调
 */
@Composable
fun QrCodeFullScreen(
    data: String,
    onBack: () -> Unit
) {
    // 获取屏幕尺寸，尽可能大地显示二维码
    val density = LocalDensity.current
    val screenSize = with(density) { 200.dp } // Wear OS 屏幕大约 200dp

    // ROUND-2-FIX: onBack 此前是死参数 —— 全屏二维码没有任何退出入口
    // (滑动手势只会把整个 auth 导航目的地弹出,连授权流程一起取消)。
    // 轻点任意处关闭二维码、返回授权码界面。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onBack() })
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        QrCodeView(
            data = data,
            size = screenSize - 16.dp,
            darkColor = Color.Black,
            lightColor = Color.White
        )
    }
}

/**
 * 生成二维码 Bitmap
 *
 * @param data 二维码数据
 * @param sizePx 尺寸（像素）
 * @param darkColor 深色模块颜色
 * @param lightColor 浅色模块颜色
 * @return Bitmap? 生成失败返回 null
 */
private fun generateQrBitmap(
    data: String,
    sizePx: Int,
    darkColor: Color,
    lightColor: Color
): Bitmap? {
    return try {
        // 使用 ZXing 生成 BitMatrix
        val writer = MultiFormatWriter()
        val bitMatrix: BitMatrix = writer.encode(
            data,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx
        )

        // 转换为 Bitmap
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val darkArgb = darkColor.toArgb()
        val lightArgb = lightColor.toArgb()
        val paint = Paint().apply { isAntiAlias = false }

        // 计算每个模块的大小(两端都显式 .toFloat() → Float.div(Float) 单一重载,
        // 避免 K2 对 Float.div(Int) 的类型推断歧义级联到后面的 + / -)。
        val moduleWidth: Float = width.toFloat() / bitMatrix.width.toFloat()
        val moduleHeight: Float = height.toFloat() / bitMatrix.height.toFloat()

        // 绘制背景
        canvas.drawColor(lightArgb)

        // 绘制黑色模块
        paint.color = darkArgb
        for (y in 0 until bitMatrix.height) {
            for (x in 0 until bitMatrix.width) {
                if (bitMatrix[x, y]) {
                    // 显式 Float 局部,避开 K2 在 drawRect(Java float 重载)参数里对链式算术的
                    // 类型推断歧义(否则报 minus/plus overload ambiguity)。
                    val left: Float = x * moduleWidth
                    val top: Float = y * moduleHeight
                    // 稍微缩小模块以留出间隙,提高扫描成功率
                    val rectLeft: Float = left + 0.5f
                    val rectTop: Float = top + 0.5f
                    val rectRight: Float = left + moduleWidth - 0.5f
                    val rectBottom: Float = top + moduleHeight - 0.5f
                    canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, paint)
                }
            }
        }

        bitmap
    } catch (e: Exception) {
        android.util.Log.e("QrCodeView", "QR code generation failed: ${e.message}")
        null
    }
}
