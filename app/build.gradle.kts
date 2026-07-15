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
            buildConfigField("String", "GALAXY_SERVER_URL", "\"wss://localhost:9000\"")
            // SECURITY-FIX: API_VERSION aligned with Android app (v3.0)
            buildConfigField("String", "API_VERSION", "\"v3.0\"")
            buildConfigField("String", "WS_PATH", "\"/ws\"")
            // SECURITY: SSL certificate pinning hashes. Replace with real SHA-256 pins in production.
            buildConfigField("String", "CERT_PIN_PRIMARY", "\"\"")
            buildConfigField("String", "CERT_PIN_BACKUP", "\"\"")
            // B4-FIX: release builds use strict network_security_config without cleartext whitelist
            resValue("xml", "network_security_config", "@xml/network_security_config_release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // 调试默认指向模拟器宿主别名 10.0.2.2(= 开发机)，明文 ws 便于本地联调。
            // 旧默认 wss://localhost 在手表上 localhost 指向手表自身、且本地后端多为明文 → 连不上。
            // 真机请在设置里改成局域网/Tailscale IP(见 README，如 ws://192.168.1.100:9000)。
            buildConfigField("String", "GALAXY_SERVER_URL", "\"ws://10.0.2.2:9000\"")
            // SECURITY-FIX: API_VERSION aligned with Android app (v3.0)
            buildConfigField("String", "API_VERSION", "\"v3.0\"")
            buildConfigField("String", "WS_PATH", "\"/ws\"")
            buildConfigField("String", "CERT_PIN_PRIMARY", "\"\"")
            buildConfigField("String", "CERT_PIN_BACKUP", "\"\"")
        }
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

    // Serialization — JSON + MessagePack dual-format
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.msgpack:msgpack-core:0.9.8")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // W18-FIX: Wear OS core library for AmbientLifecycleObserver (W4)
    implementation("androidx.wear:wear:1.3.0")
    // Wear OS — Input (for rotary input / hardware buttons)
    implementation("androidx.wear:wear-input:1.1.0")

    // HiveMQ MQTT Client
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
