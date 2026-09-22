package com.galaxy.wear.data

import kotlinx.serialization.json.*

/**
 * AIPClient 里**不碰 Android 的那部分**。
 *
 * 为什么单独一个文件
 * ==================
 * 这四段是纯粹的字符串 / JSON / 字节逻辑,和手表、和 Android 框架都没关系。
 * 但它们原先长在 `AIPClient` 里,而那个类的构造要 `android.content.Context`;
 * 本仓单测工具链只有 `junit:junit` 一个依赖 —— 没有 Robolectric,造不出 Context。
 * 结果是:**整条协议链路 1200 行,测试覆盖 0**,连"末尾斜杠会不会拼出双斜杠"这种
 * 一行就能验的事都没人验过。
 *
 * 所以把它们抬出水面。函数体逐字未改,行为不变。
 *
 * 这个文件里的硬规矩
 * ==================
 * **不许 import android.\*,也不许读任何实例状态。** 一旦破例,它就又只能在
 * Android 环境里跑,上面那些测试会静悄悄地全部失效 —— 文件还在,只是没人能调用。
 * `AIPClientPureLogicTest` 里有一条守卫钉着这条规矩。
 *
 * 日志怎么办
 * ==========
 * 原来 msgpack 编解码失败时会 `Log.w`。日志是 Android 的东西,不能留在这儿,
 * 但异常信息不该丢 —— 所以改成 `onError` 回调,由 `AIPClient` 那边照原样打出来。
 */
internal object AipPureLogic {

    /**
     * Build WebSocket URL with auto-path attachment.
     * W2-FIX: Unified port 9000 across all configurations.
     * W13-FIX: If URL has no /ws path, appends /{API_VERSION}/ws/device/{deviceId}.
     */
    internal fun buildWsUrl(baseUrl: String, devId: String): String {
        val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return when {
            url.contains("/ws") -> url  // Already has path, don't modify
            else -> {
                // R5-FIX: Use V2 gateway path without API_VERSION prefix
                "$url/ws/device/$devId"
            }
        }
    }

    /**
     * DEVICE: 解析设备列表响应。
     *
     * 整份解析是**尽力而为**:任何一处缺字段都回落到占位值,整体解析失败回空表。
     * 这是有意的 —— 设备列表是展示用的,少一个字段不该让整屏空掉。
     */
    internal fun parseDeviceList(payload: JsonElement): List<DeviceInfo> {
        return try {
            val array = payload.jsonArray
            array.map { element ->
                val obj = element.jsonObject
                DeviceInfo(
                    deviceId = obj["device_id"]?.jsonPrimitive?.content ?: "unknown",
                    displayName = obj["display_name"]?.jsonPrimitive?.content ?: "Unknown Device",
                    deviceType = obj["device_type"]?.jsonPrimitive?.content ?: "unknown",
                    status = obj["status"]?.jsonPrimitive?.content ?: "unknown",
                    capabilities = obj["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: emptyList(),
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val pureJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * C4: Unpack a MessagePack byte array into a JSON string.
     * Uses recursive Value-to-JsonElement conversion for reliable JSON output.
     * Returns null if unpacking fails (caller falls back to JSON).
     */
    fun unpackMsgpack(data: ByteArray, onError: (String) -> Unit = {}): String? {
        val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)
        return try {
            val value = unpacker.unpackValue()
            // C4: Convert MessagePack Value to kotlinx JsonElement recursively
            val jsonElement = msgpackValueToJsonElement(value)
            pureJson.encodeToString(JsonElement.serializer(), jsonElement)
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
            null
        } finally {
            runCatching { unpacker.close() }
        }
    }

    /**
     * C4: Recursively convert MessagePack Value to kotlinx.serialization JsonElement.
     * This ensures proper JSON type mapping (not just toString).
     */
    private fun msgpackValueToJsonElement(
        value: org.msgpack.value.Value
    ): JsonElement {
        return when {
            value.isNilValue -> JsonNull
            value.isBooleanValue -> JsonPrimitive(value.asBooleanValue().boolean)
            value.isIntegerValue -> {
                val intVal = value.asIntegerValue()
                when {
                    intVal.isInLongRange -> JsonPrimitive(intVal.toLong())
                    else -> JsonPrimitive(intVal.toBigInteger().toString())
                }
            }
            value.isFloatValue -> JsonPrimitive(value.asFloatValue().toDouble())
            value.isStringValue -> JsonPrimitive(value.asStringValue().asString())
            value.isBinaryValue -> {
                // Encode binary as Base64 string
                val bytes = value.asBinaryValue().asByteArray()
                JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes))
            }
            value.isArrayValue -> {
                val array = value.asArrayValue()
                JsonArray(array.map { msgpackValueToJsonElement(it) })
            }
            value.isMapValue -> {
                val map = value.asMapValue().map()
                JsonObject(map.mapKeys {
                    // CRITICAL-FIX: Handle non-string keys gracefully — if key is not
                    // a string (e.g., integer key), fall back to toString() instead
                    // of letting asStringValue() throw MessageTypeCastException.
                    try { it.key.asStringValue().asString() }
                    catch (_: Exception) { it.key.toString() }
                }.mapValues { msgpackValueToJsonElement(it.value) })
            }
            value.isExtensionValue -> {
                // Handle extension types as JSON object with type and data
                val ext = value.asExtensionValue()
                buildJsonObject {
                    put("ext_type", ext.type)
                    put("data", java.util.Base64.getEncoder().encodeToString(ext.data))
                }
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    /**
     * C4: Pack a JSON string into a MessagePack byte array.
     * Returns null if packing fails (caller falls back to JSON).
     */
    fun packMsgpack(json: String, onError: (String) -> Unit = {}): ByteArray? {
        return try {
            val parsed = pureJson.parseToJsonElement(json)
            val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
            try {
                packJsonElement(packer, parsed)
                packer.toByteArray()
            } finally {
                runCatching { packer.close() }
            }
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
            null
        }
    }

    private fun packJsonElement(packer: org.msgpack.core.MessagePacker, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                packer.packMapHeader(element.size)
                element.forEach { (key, value) ->
                    packer.packString(key)
                    packJsonElement(packer, value)
                }
            }
            is JsonArray -> {
                packer.packArrayHeader(element.size)
                element.forEach { packJsonElement(packer, it) }
            }
            is JsonPrimitive -> {
                val primitive = element
                when {
                    primitive.isString -> packer.packString(primitive.content)
                    primitive.content == "true" -> packer.packBoolean(true)
                    primitive.content == "false" -> packer.packBoolean(false)
                    primitive.content == "null" -> packer.packNil()
                    else -> {
                        // Try int first, then float
                        primitive.content.toLongOrNull()?.let { packer.packLong(it) }
                            ?: primitive.content.toDoubleOrNull()?.let { packer.packDouble(it) }
                            ?: packer.packString(primitive.content)
                    }
                }
            }
            JsonNull -> packer.packNil()
        }
    }
}
