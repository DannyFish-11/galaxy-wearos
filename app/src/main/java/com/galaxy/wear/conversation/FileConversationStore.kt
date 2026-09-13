package com.galaxy.wear.conversation

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 落在文件上的 [ConversationStore.Store]。
 *
 * ## 为什么不用 SharedPreferences
 * 手表里的凭据走 EncryptedSharedPreferences（见 `GalaxyWearApplication`），那是为了
 * 保护 auth_token。会话正文是另一类数据：量更大、整体读写、每次追加都要重写全部。
 * SharedPreferences 的形态是键值对，把一个几十 KB 的数组塞进单个 key 里读写，
 * 既没有它的好处，还要额外付一次加解密。
 *
 * ## 读失败时返回空，而不是抛
 * 文件损坏、版本迁移读不动 —— 这些都不该让手表启动不起来。丢历史是可惜，
 * 打不开 App 更糟。失败会记一条日志，不是静默的。
 *
 * ## 写是整体覆盖
 * [ConversationStore] 每次追加都带着完整列表调 [write]，上限 200 条，
 * 整体重写的代价可以忽略；换来的是不会出现"追加了一半"的半截文件。
 */
class FileConversationStore(private val file: File) : ConversationStore.Store {

    @Serializable
    private data class Entry(
        val id: String,
        val conversationId: String,
        val role: String,
        val text: String,
        val timestampMs: Long,
    )

    override fun read(): List<ConversationMessage> {
        if (!file.exists()) return emptyList()
        return try {
            JSON.decodeFromString<List<Entry>>(file.readText()).mapNotNull { e ->
                val role = runCatching { ConversationMessage.Role.valueOf(e.role) }.getOrNull()
                    ?: return@mapNotNull null // 未知角色（新版本写的）：跳过这一条，别丢掉整个文件
                ConversationMessage(e.id, e.conversationId, role, e.text, e.timestampMs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "会话历史读取失败，按空历史继续：${t.message}")
            emptyList()
        }
    }

    override fun write(messages: List<ConversationMessage>) {
        try {
            file.parentFile?.mkdirs()
            val text = JSON.encodeToString(
                messages.map { Entry(it.id, it.conversationId, it.role.name, it.text, it.timestampMs) }
            )
            // 先写临时文件再改名：中途断电不会留下半截 JSON，
            // 而半截 JSON 会让下次读取整个失败、历史全丢。
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text) // renameTo 在少数文件系统上会失败，退回直接写
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "会话历史写入失败：${t.message}")
        }
    }

    companion object {
        private const val TAG = "FileConversationStore"
        private const val FILE_NAME = "conversation.json"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** App 私有目录下的默认位置。 */
        fun forContext(context: Context): FileConversationStore =
            FileConversationStore(File(context.applicationContext.filesDir, FILE_NAME))
    }
}
