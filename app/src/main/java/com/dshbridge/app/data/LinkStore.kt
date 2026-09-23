package com.dshbridge.app.data

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * 链接记录的持久化：单个 JSON 数组存进 SharedPreferences。
 *
 * 数据量是"人手维护的十几条"，不需要 Room；这里保证写入原子（synchronized）并按最近打开排序。
 */
class LinkStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    /** 按最近打开时间倒序返回全部记录 */
    fun all(): List<LinkRecord> = synchronized(lock) {
        read().sortedWith(compareByDescending<LinkRecord> { it.lastOpenedAt }.thenByDescending { it.createdAt })
    }

    fun find(id: String): LinkRecord? = synchronized(lock) { read().firstOrNull { it.id == id } }

    /**
     * 新增记录；若已存在同址记录则直接复用并刷新"最近打开"。
     * 扫码重复扫同一个地址不应该产生重复条目。
     */
    fun addOrTouch(url: String, title: String = ""): LinkRecord = synchronized(lock) {
        val list = read().toMutableList()
        val now = System.currentTimeMillis()
        val existingIndex = list.indexOfFirst { sameUrl(it.url, url) }
        val record = if (existingIndex >= 0) {
            val merged = list[existingIndex].copy(
                lastOpenedAt = now,
                title = title.ifBlank { list[existingIndex].title }
            )
            list[existingIndex] = merged
            merged
        } else {
            val created = LinkRecord(
                id = UUID.randomUUID().toString(),
                url = url,
                title = title,
                createdAt = now,
                lastOpenedAt = now
            )
            list.add(created)
            created
        }
        write(list)
        record
    }

    /** 标记为最近打开（用于点击已有条目） */
    fun touch(id: String) = synchronized(lock) {
        val list = read().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(lastOpenedAt = System.currentTimeMillis())
            write(list)
        }
    }

    fun delete(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    // ---- 内部 ----

    private fun read(): MutableList<LinkRecord> {
        val raw = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i -> LinkRecord.fromJson(array.getJSONObject(i)) }
                .filterNotNull()
                .toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun write(list: List<LinkRecord>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** 同址判定：忽略大小写、末尾斜杠与首尾空白 */
    private fun sameUrl(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()

    private companion object {
        const val PREFS = "dsh_links"
        const val KEY_LIST = "list_json"
    }
}
