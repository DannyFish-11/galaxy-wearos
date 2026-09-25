plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

android {
    namespace = "com.galaxy.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galaxy.wear"
        minSdk = 30
        targetSdk = 35
        // D1-FIX: versionCode = major*100 + minor*10 + patch (201 = 2.0.1)
        // Aligned with Android app version for cross-device compatibility.
        versionCode = 201
        versionName = "2.0.1"

        // Only keep needed language resources
        resourceConfigurations += listOf("zh", "en")

        // Wear OS standalone 标记由 AndroidManifest.xml 的
        // <meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>
        // 声明(见 app/src/main/AndroidManifest.xml)。build.gradle 里没有 setMetadata 这个 DSL
        // 函数——原来那行是编译阻塞(Unresolved reference),且与清单里的声明重复,删除。
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 2.0+ uses composeCompiler plugin, not composeOptions
    // composeOptions block removed — obsolete in AGP 8.0+

    // Java 目标必须与 Kotlin(jvmTarget=17)一致,否则
    // "Inconsistent JVM-target: Java(1.8) vs Kotlin(17)" 让 compileDebugKotlin 在校验门就挂。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Bundle optimization: deliver features on-demand
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // GALAXY_SERVER_URL / API_VERSION / WS_PATH 三个字段删掉了 —— **没有任何代码读它们**
            // (实测:全仓 BuildConfig.GALAXY_SERVER_URL / .API_VERSION / .WS_PATH 各 0 次引用)。
            //
            // 留着比删掉更坏:它们让人以为 release 版会去连 `wss://localhost:9000`,
            // 而手表上 localhost 就是手表自己 —— 一个读代码的人会照着这条假线索去查
            // "为什么连不上"。真正的取址链路在 GalaxyWearApplication.discoverGateway():
            //   mDNS(2 秒窗口) → Tailscale 段扫描 → 都没有就让用户在设置里手填,
            //   手填的值进 EncryptedSharedPreferences 的 server_url。
            // 要再引入编译期默认地址,请先确认它真的会被读,否则就是又立一块假路牌。
            //
            // 这里原先还有两样东西,一并删掉:
            //  * CERT_PIN_PRIMARY / BACKUP —— 值是空串,AIPClient 见空就跳过固定;钉的域名
            //    "galaxy.ufo.ai" 本系统也从不连。
            //  * resValue 把 network_security_config 换成一份"全禁明文"的 release 版 ——
            //    而设备间只走内网、网关默认说明文 ws://,于是 release 版手表**任何内网
            //    地址都连不上**。现在只有一份配置,"明文只许对内网"由 CleartextPolicy 判。
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // 同 release:GALAXY_SERVER_URL / API_VERSION / WS_PATH 没有任何读取方,一并删掉。
            // 这里原先写着 `ws://10.0.2.2:9000`(模拟器宿主别名),注释还解释了为什么不是
            // localhost —— 那段解释是对的,但它描述的是一个**没人读的常量**,于是反而成了
            // 最像真的那块假路牌。
            // 本地联调的正确做法:让 mDNS 发现开发机,或在设置页手填(模拟器填 ws://10.0.2.2:9000,
            // 真机填局域网/Tailscale 地址)。
        }
    }

    // 多个依赖的 jar 各自带一份同名元数据(META-INF/LICENSE、NOTICE、INDEX.LIST……),
    // 打 APK 时 mergeDebugJavaResource 因"同名多份"直接失败。这些是 jar 元数据,
    // APK 里用不到,统一丢弃即可(assembleDebug 才走到这步,compileDebugKotlin 看不到)。
    // (netty 那两条原先是给 HiveMQ 带进来的 Netty 用的;HiveMQ 已删,留着无害。)
    packaging {
        // 手表进 tailnet 的那个进程(libgalaxytailnet.so)是**可执行文件**,要从
        // nativeLibraryDir 起。AGP 4.2 起默认不解压原生库(留在 APK 里按偏移映射),
        // nativeLibraryDir 里就没有真实文件可执行。打开它 = extractNativeLibs=true,
        // 安装时解压到只读且允许执行的目录(与安卓仓 llama-server 同一个坑、同一个解法)。
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**"
            )
        }
    }

    // 单测里让 android.* 的桩方法返回默认值,而不是抛
    // "Method w in android.util.Log not mocked"。
    //
    // 为什么需要:AIPClient 的 msgpack 编解码是纯函数,但它的 catch 分支写了
    // Log.w。于是**恰恰是失败路径**(喂坏数据该回 null)在单测里碰不得 ——
    // 而那正是最值得测的一条。
    //
    // 这行**不**许可什么:它只是把日志变成空操作,不会把被测逻辑变成假的。
    // 任何需要真 Android 行为的断言(Context、SharedPreferences、Looper……)
    // 在这里只会拿到 0/null/false,那种测试是假绿 —— 要测那些请上
    // Robolectric 或插桩测试,别靠这行蒙混。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Remove unused kotlin.Metadata annotations at build time
    kotlin {
        sourceSets.all {
            languageSettings {
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
            }
        }
    }
}

// ── 手表自己进 tailnet 的那个进程(本仓 tailnet/,Go)────────────────────────────
//
// 为什么要编一个 Go 程序进 APK:Wear OS 把 VPN 授权做成了桩,装不了 Tailscale。
// tailnet/ 用 tsnet 以用户态加入 tailnet(不需要 VPN 授权),在回环口上转发到网关。
// 出门在外、只带手表时,这是直连电脑的那条路。详见 tailnet/main.go 顶部。
//
// 只编 arm64-v8a:手表侧只支持 64 位(所有者决定;32 位 ARM 要 cgo + NDK 才能编)。
// 名字必须是 lib*.so,安装器才会把它解压进 nativeLibraryDir。
//
// 需要 Go(版本见 tailnet/go.mod;GOTOOLCHAIN=auto 时会自动取对应工具链)。
// 没有 Go 就**构建失败并说明原因** —— 不静默跳过:跳过的结果是一个"出门连不上"
// 的 APK,而没人知道是少了这个文件。
val tailnetSrc = rootProject.file("tailnet")
val tailnetJniDir = layout.buildDirectory.dir("generated/tailnet/jniLibs")
val buildTailnet by tasks.registering(Exec::class) {
    description = "把 tailnet/ 编成 arm64 Android 可执行文件,打进 jniLibs"
    inputs.files(fileTree(tailnetSrc) { include("*.go", "go.mod", "go.sum") })
    val out = tailnetJniDir.map { it.file("arm64-v8a/libgalaxytailnet.so") }
    outputs.file(out)
    workingDir = tailnetSrc
    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    environment("CGO_ENABLED", "0")
    commandLine("go", "build", "-trimpath", "-ldflags=-s -w", "-o", out.get().asFile.absolutePath, ".")
    doFirst {
        val ok = runCatching { ProcessBuilder("go", "version").start().waitFor() == 0 }.getOrDefault(false)
        if (!ok) {
            throw GradleException(
                "构建手表 APK 需要 Go(tailnet/ 是出门直连用的 tailnet 进程)。" +
                    "请安装 Go 并确保 `go` 在 PATH 里,版本见 tailnet/go.mod。"
            )
        }
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(tailnetJniDir.get().asFile)
tasks.named("preBuild") { dependsOn(buildTailnet) }

dependencies {
    // PR-SHARED-TRANSPORT: Reuse shared transport module from Android project
    // Eliminates code duplication of GatewayClient / AipTransportManager / BleGatewayClient / MqttGatewayClient.
    implementation(project(":shared-transport"))
    // PR-SHARED-PROTOCOL: Reuse shared protocol module from Android project
    // Unifies MsgType / AipMessage / AuthMessage / ReconnectionConfig across repos.
    implementation(project(":shared-protocol"))

    // Wear OS — core only, no Horologist
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Wear OS — Tiles (for GalaxyTileService)
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.tiles:tiles-material:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.4.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.0")

    // Compose — minimal set
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-text")
    // Debug only
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core — only what's needed
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // SECURITY-FIX: EncryptedSharedPreferences for secure credential storage.
    // Replaces plaintext SharedPreferences to protect auth_token and other sensitive data.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking — Ktor WebSocket with OkHttp engine (compact for wearables)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    // AIPClient / DeviceFlowManager 用到 ContentNegotiation 与 Logging 两个客户端插件
    // (io.ktor.client.plugins.contentnegotiation / .logging),此前【没声明这两个 artifact】,
    // 导致 ContentNegotiation/Logging/LogLevel/Logger 全部无法解析 —— 补上。
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-client-logging:2.3.12")

    // Wear Tiles 的 onTileRequest 必须返回 Guava ListenableFuture 且用 Futures.immediateFuture 构造。
    // androidx 只传递了 listenablefuture 桩(仅含 ListenableFuture 接口),没有 Futures 工具类 →
    // GalaxyTileService 的 Futures.* 无法解析。补上完整 Guava(android 变体)。
    implementation("com.google.guava:guava:33.3.1-android")

    // Serialization — JSON + MessagePack dual-format
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.msgpack:msgpack-core:0.9.8")

    // 二维码(设备登录 QrCodeView / DeviceAuthScreen 用):此前 QrCodeView import 了
    // com.google.zxing.* 却【没声明依赖】,BitMatrix/MultiFormatWriter 全部无法解析,
    // 是 QrCodeView 一连串编译错的总根因。补上 ZXing core。
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // W18-FIX: Wear OS core library for AmbientLifecycleObserver (W4)
    implementation("androidx.wear:wear:1.3.0")
    // Wear OS — Input (for rotary input / hardware buttons)
    implementation("androidx.wear:wear-input:1.1.0")

    // (HiveMQ MQTT client 删了:全仓没有一处 import 它,却传递性拖进整套 Netty。)

    // WebRTC —— 实时语音通话的媒体通道。
    //
    // 为什么不走已有的 AIP WebSocket:AIP 建在 TCP 上,丢一个包后面全被堵住,而重传
    // 回来的是过期音频。实时语音里迟到的音频没有价值,只会把延迟越堆越高。手表走独立
    // 蜂窝数据时这一点尤其致命。
    //
    // 为什么是 io.getstream 这个坐标:Google 官方的 org.webrtc:google-webrtc 停更在
    // 2019 年(1.0.32006)且随 JCenter 一起没了。Stream 这一份是当前在维护的 libwebrtc
    // Android 预编译包,包名仍是 org.webrtc,minSdk 21,含 arm64-v8a / armeabi-v7a /
    // x86 / x86_64 四个 ABI —— 手表用得上的两个都在。
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
