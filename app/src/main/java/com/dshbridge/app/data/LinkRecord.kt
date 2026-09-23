package com.dshbridge.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 一条链接记录。
 *
 * [url] 保存的是"完整可访问地址"，可能带 `?auth=<secretToken>`（扫码得到的免密地址）。
 * 列表展示时用 [displayUrl] 隐去 query，避免把免密 token 明文显示在首页。
 */
data class LinkRecord(
    val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
    val lastOpenedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("createdAt", createdAt)
        put("lastOpenedAt", lastOpenedAt)
    }

    /** 首页展示用：scheme://host[:port][/path]，隐去 query（可能含免密 token） */
    val displayUrl: String
        get() {
            val parsed = url.toHttpUrlOrNull() ?: return url
            return buildString {
                append(parsed.scheme).append("://").append(parsed.host)
                val defaultPort = (parsed.scheme == "https" && parsed.port == 443) ||
                    (parsed.scheme == "http" && parsed.port == 80)
                if (!defaultPort) append(':').append(parsed.port)
                if (parsed.encodedPath.isNotEmpty() && parsed.encodedPath != "/") {
                    append(parsed.encodedPath)
                }
            }
        }

    /** 未命名时退回主机名 */
    val displayTitle: String
        get() = title.ifBlank { url.toHttpUrlOrNull()?.host ?: url }

    companion object {
        fun fromJson(o: JSONObject): LinkRecord? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            return LinkRecord(
                id = id,
                url = url,
                title = o.optString("title"),
                createdAt = o.optLong("createdAt"),
                lastOpenedAt = o.optLong("lastOpenedAt")
            )
        }
    }
}
