# Wear OS release (R8) rules.
#
# 这份规则此前只有一条 keep Application 和一条指向 `com.galaxy.wear.data.AIPMessage` 的规则 ——
# 后者那个类早已不存在(现在是共享协议里的类型别名),规则等于没写。release 包又从未被构建过,
# 所以「debug 好、release 才崩」这类问题没有人看得见。CI 现在会构建 release(见
# .github/workflows/wear-compile.yml),缺类 / 缺规则在那里就会红,而不是到了真机才发现。
#
# 写法原则:只为**确实靠反射 / 按名字加载 / JNI 回调**的东西写 keep,其余让 R8 去收缩。
# 每一条都写明为什么,没写原因的 keep 等于以后没人敢删。

# ── 调试信息(崩溃栈能对回源码行) ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ───────────────────────────────────────────────
# @Serializable 类的序列化器靠 Companion.serializer() 与 `$$serializer` 取到,编译期看不出引用关系,
# R8 会把它们当死代码删掉 → 运行时 SerializationException。覆盖本应用与共享协议 / 传输模块。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.galaxy.wear.** { *** Companion; }
-keepclasseswithmembers class com.galaxy.wear.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.ufo.galaxy.shared.** { *** Companion; }
-keepclasseswithmembers class com.ufo.galaxy.shared.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.galaxy.wear.**$$serializer { *; }
-keep,includedescriptorclasses class com.ufo.galaxy.shared.**$$serializer { *; }

# ── msgpack-core ────────────────────────────────────────────────────────
# MessageBuffer 在类加载时按名字挑实现(Class.forName "…buffer.MessageBufferU" 等),
# 编译期没有引用 → 被收缩掉就是 ClassNotFoundException,而且只在 release 里。
-keep class org.msgpack.core.buffer.** { *; }
-dontwarn org.msgpack.**

# ── WebRTC(实时通话) ───────────────────────────────────────────────────
# libwebrtc 的 Java 类被 JNI 从 native 侧按名字回调。收缩 / 混淆它们 → 通话一建就崩。
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── Ktor + OkHttp ───────────────────────────────────────────────────────
# Ktor 引擎通过 ServiceLoader 发现:META-INF/services 里点名的容器类不能被收缩。
-keep class * implements io.ktor.client.HttpClientEngineContainer
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
# Ktor 日志插件可选地引用 slf4j / JMX,Android 上没有。
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# ── EncryptedSharedPreferences(Tink) ──────────────────────────────────
# Tink 的 protobuf-lite 消息靠反射读字段;字段被改名 → 打开加密偏好时抛
# InvalidProtocolBufferException("Field … not found"),表现是「release 里一启动就读不出令牌」。
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**

# ── Guava(android 变体,Tiles 用 Futures) ──────────────────────────────
-dontwarn sun.misc.Unsafe
-dontwarn com.google.common.**
