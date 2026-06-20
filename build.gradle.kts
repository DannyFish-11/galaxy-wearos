// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.22" apply false
    // 把序列化插件放上根 classpath：被包含的兄弟模块 shared-protocol 以无版本号方式
    // apply 'org.jetbrains.kotlin.plugin.serialization'，需由消费方(本 Wear 构建)提供版本，
    // 否则配置阶段报 Plugin [id: '...serialization'] was not found。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
}
